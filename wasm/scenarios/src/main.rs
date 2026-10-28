//! The scenario driver (ED-10): `scenarios --results DIR [ID ...]`, run in the
//! project's root by wasm/test.sh. It finds by looking every feature file, in
//! any slice, named for a component this build holds (wasm/components.edn),
//! runs each scenario and outline row, and writes one Allure result for each
//! into DIR, under the results contract (the glossary's "Allure result";
//! dhf/spec/05-verify-by-results/runner.feature). It exits 0 when every result
//! passed, save the pending scenarios' (dhf/spec/01-start-a-project/pending.md),
//! 1 when another did not, 2 when it cannot run or finds no scenario.

mod gherkin;
mod world;

#[path = "../../components/kernel-command/steps.rs"]
mod kernel_command_steps;
#[path = "../../components/project-init/steps.rs"]
mod project_init_steps;
#[path = "../../components/project-adopt/steps.rs"]
mod project_adopt_steps;
#[path = "../../components/project-hooks/steps.rs"]
mod project_hooks_steps;

use gherkin::{Feature, Scenario};
use rdm_kernel::edn::{self, Edn};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::{SystemTime, UNIX_EPOCH};
use world::{Steps, World, Wrong};

struct Component {
    id: String,
    context: String,
    name: String,
    stem: String,
}

fn now() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)
}

fn uuid() -> String {
    let mut b = [0u8; 16];
    if let Ok(mut f) = std::fs::File::open("/dev/urandom") {
        use std::io::Read;
        let _ = f.read_exact(&mut b);
    }
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

fn die(msg: &str) -> ! {
    eprintln!("cannot-run: {msg}");
    std::process::exit(2)
}

fn read_edn(path: &Path) -> Edn {
    let text = std::fs::read_to_string(path).unwrap_or_else(|e| die(&format!("{}: {e}", path.display())));
    edn::read(&text).unwrap_or_else(|e| die(&format!("{}: {e}", path.display())))
}

fn kw_name(e: Option<&Edn>) -> String {
    match e {
        Some(Edn::Keyword(k)) => k.clone(),
        Some(Edn::Str(s)) => s.clone(),
        _ => String::new(),
    }
}

/// The model's components with their context and the file stem of their spec.
fn components(model: &Edn) -> Vec<Component> {
    let mut out = Vec::new();
    let mut add = |c: &Edn, context: &str| {
        let id = kw_name(c.get("id"));
        let spec = c.get("spec").and_then(|s| s.as_str()).unwrap_or("");
        let stem = spec.rsplit('/').next().unwrap_or("").to_string();
        out.push(Component { id, context: context.to_string(), name: c.get("name").and_then(|s| s.as_str()).unwrap_or("").to_string(), stem });
    };
    for c in model.get("kernel").map(|k| k.items()).unwrap_or(&[]) {
        add(c, "kernel");
    }
    for container in model.get("containers").map(|k| k.items()).unwrap_or(&[]) {
        for ctx in container.get("contexts").map(|k| k.items()).unwrap_or(&[]) {
            let context = kw_name(ctx.get("id"));
            for c in ctx.get("components").map(|k| k.items()).unwrap_or(&[]) {
                add(c, &context);
            }
        }
    }
    out
}

fn register() -> Steps {
    let mut steps = Steps::default();
    kernel_command_steps::register(&mut steps);
    project_init_steps::register(&mut steps);
    project_adopt_steps::register(&mut steps);
    project_hooks_steps::register(&mut steps);
    steps
}

struct Outcome {
    status: &'static str,
    message: Option<String>,
}

fn main() {
    let mut args = std::env::args().skip(1);
    let mut results: Option<PathBuf> = std::env::var_os("RESULTS_DIR").map(PathBuf::from);
    let mut only: Vec<String> = Vec::new();
    while let Some(a) = args.next() {
        if a == "--results" {
            results = args.next().map(PathBuf::from);
        } else {
            only.push(a);
        }
    }
    let Some(results) = results.filter(|r| !r.as_os_str().is_empty()) else { die("no results directory: set RESULTS_DIR or give --results DIR") };
    std::fs::create_dir_all(&results).unwrap_or_else(|e| die(&format!("{}: {e}", results.display())));
    let repo = std::env::current_dir().unwrap_or_else(|e| die(&e.to_string()));

    let config = read_edn(&repo.join("dhf/harness/config.edn"));
    let spec_dir = config.get("spec-dir").and_then(|s| s.as_str()).unwrap_or("dhf/spec").to_string();
    let needs_file = config.get("needs").and_then(|s| s.as_str()).unwrap_or("dhf/spec/needs.edn").to_string();
    let model = read_edn(&repo.join(config.get("model").and_then(|s| s.as_str()).unwrap_or("dhf/spec/model.edn")));
    let built = read_edn(&repo.join("wasm/components.edn"));
    let built_ids: Vec<String> = built.get("components").map(|c| c.items()).unwrap_or(&[]).iter().filter_map(|e| e.as_str().map(String::from)).collect();
    let pending: BTreeMap<String, String> = match built.get("pending") {
        Some(Edn::Map(m)) => m.iter().map(|(k, v)| (k.as_str().unwrap_or("").to_string(), v.as_str().unwrap_or("").to_string())).collect(),
        _ => BTreeMap::new(),
    };
    let comps: Vec<Component> = components(&model).into_iter().filter(|c| built_ids.contains(&c.id)).collect();
    if comps.len() != built_ids.len() {
        die("wasm/components.edn names a component the model does not hold");
    }

    // Every feature file named for a built component, in any slice: by looking.
    let mut found: Vec<(PathBuf, usize)> = Vec::new();
    let mut dirs = vec![repo.join(&spec_dir)];
    while let Some(d) = dirs.pop() {
        let Ok(entries) = std::fs::read_dir(&d) else { continue };
        for e in entries.flatten() {
            let p = e.path();
            if p.is_dir() {
                dirs.push(p);
            } else if p.extension().is_some_and(|x| x == "feature") {
                let stem = p.file_stem().unwrap().to_string_lossy().into_owned();
                if let Some(i) = comps.iter().position(|c| c.stem == stem) {
                    found.push((p, i));
                }
            }
        }
    }
    found.sort();
    let mut features = Vec::new();
    let mut owners = Vec::new();
    for (p, i) in &found {
        let rel = p.strip_prefix(&repo).unwrap().to_string_lossy().into_owned();
        let text = std::fs::read_to_string(p).unwrap_or_else(|e| die(&format!("{rel}: {e}")));
        features.push(gherkin::parse(&rel, &text).unwrap_or_else(|e| die(&e)));
        owners.push(*i);
    }
    let features = Rc::new(features);
    let steps = register();

    let base = std::env::temp_dir().join(format!("rdm-spec-scenarios-{}", &uuid()[..8]));
    std::fs::create_dir_all(&base).unwrap_or_else(|e| die(&format!("{}: {e}", base.display())));

    let mut counts: BTreeMap<&'static str, usize> = BTreeMap::new();
    let mut not_passed: Vec<String> = Vec::new();
    let mut pending_not_passed: Vec<String> = Vec::new();
    let mut total = 0;
    for (fi, f) in features.iter().enumerate() {
        let comp = &comps[owners[fi]];
        for s in &f.scenarios {
            let id = s.id().unwrap_or_default();
            if !only.is_empty() && !only.contains(&id) {
                continue;
            }
            let rows: Vec<Vec<(String, String)>> = if s.outline {
                s.rows().into_iter().map(|(h, r)| h.into_iter().zip(r).collect()).collect()
            } else {
                vec![Vec::new()]
            };
            for row in rows {
                total += 1;
                let dir = base.join(format!("s{total}"));
                std::fs::create_dir_all(&dir).unwrap_or_else(|e| die(&e.to_string()));
                let r = run_scenario(&repo, &features, f, s, row, &dir, &steps, comp, &needs_file, &results);
                let _ = std::fs::remove_dir_all(&dir);
                *counts.entry(r.0).or_default() += 1;
                if r.0 != "passed" {
                    let label = format!("{id}{} ({}: {})", r.1, r.0, r.2.unwrap_or_default().lines().next().unwrap_or(""));
                    if pending.contains_key(&id) {
                        pending_not_passed.push(label);
                    } else {
                        not_passed.push(label);
                    }
                }
            }
        }
    }
    let _ = std::fs::remove_dir_all(&base);
    if total == 0 {
        die("no scenario was found");
    }
    for p in &pending_not_passed {
        eprintln!("pending: {p}");
    }
    for p in &not_passed {
        eprintln!("not passed: {p}");
    }
    let summary: Vec<String> = counts.iter().map(|(k, v)| format!("{v} {k}")).collect();
    println!(
        "scenarios: {total} results, {}; {} pending not passed, {} other not passed",
        summary.join(", "),
        pending_not_passed.len(),
        not_passed.len()
    );
    std::process::exit(if not_passed.is_empty() { 0 } else { 1 });
}

#[allow(clippy::too_many_arguments)]
fn run_scenario(
    repo: &Path,
    features: &Rc<Vec<Feature>>,
    f: &Feature,
    s: &Scenario,
    row: Vec<(String, String)>,
    dir: &Path,
    steps: &Steps,
    comp: &Component,
    needs_file: &str,
    results: &Path,
) -> (&'static str, String, Option<String>) {
    let header: Vec<String> = row.iter().map(|x| x.0.clone()).collect();
    let values: Vec<String> = row.iter().map(|x| x.1.clone()).collect();
    let mut world = World::new(repo, features.clone(), s.clone(), dir.to_path_buf());
    let start = now();
    let wip = s.tags.iter().any(|t| t == "wip");
    let mut step_results = Vec::new();
    let mut outcome = Outcome { status: "passed", message: None };
    let all: Vec<&gherkin::Step> = f.background.iter().chain(s.steps.iter()).collect();
    for step in all {
        let text = gherkin::substitute(&step.text, &header, &values);
        let name = format!("{} {}", step.keyword, text);
        let st = now();
        if wip || outcome.status != "passed" {
            step_results.push(json!({"name": name, "status": "skipped", "stage": "finished", "start": st, "stop": st}));
            continue;
        }
        let matches: Vec<_> = steps.defs.iter().filter_map(|d| d.pattern.captures(&text).map(|c| (d, c))).collect();
        let res: Result<(), Wrong> = match matches.len() {
            0 => Err(Wrong::Broken(format!("no step definition matches \"{text}\""))),
            1 => {
                let (d, caps) = &matches[0];
                let captured: Vec<String> = caps.iter().skip(1).map(|m| m.map(|m| m.as_str().to_string()).unwrap_or_default()).collect();
                let run = d.run;
                let w = &mut world;
                match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| run(w, &captured))) {
                    Ok(r) => r,
                    Err(p) => Err(Wrong::Broken(format!(
                        "the step definition panicked: {}",
                        p.downcast_ref::<String>().cloned().or(p.downcast_ref::<&str>().map(|s| s.to_string())).unwrap_or_default()
                    ))),
                }
            }
            _ => Err(Wrong::Broken(format!(
                "\"{text}\" matches more than one step definition: {}",
                matches.iter().map(|(d, _)| format!("{} ({})", d.pattern.as_str(), d.source)).collect::<Vec<_>>().join("; ")
            ))),
        };
        let sp = now();
        let mut sr = json!({"name": name, "stage": "finished", "start": st, "stop": sp});
        match res {
            Ok(()) => sr["status"] = json!("passed"),
            Err(w) => {
                let (status, msg) = match w {
                    Wrong::Fail(m) => ("failed", m),
                    Wrong::Broken(m) => ("broken", m),
                };
                sr["status"] = json!(status);
                sr["statusDetails"] = json!({"message": msg, "trace": format!("{}:{}: {}", f.path, step.line, name)});
                outcome = Outcome { status, message: Some(msg) };
            }
        }
        step_results.push(sr);
    }
    if wip {
        outcome = Outcome { status: "skipped", message: Some("planned (@wip): not run".into()) };
    }
    let stop = now();
    let id = s.id().unwrap_or_default();

    // Tags: the feature's, the rule's and the scenario's, in that order, each once.
    let mut tags: Vec<String> = Vec::new();
    for t in f.tags.iter().chain(s.rule_tags.iter()).chain(s.tags.iter()) {
        if !tags.contains(t) {
            tags.push(t.clone());
        }
    }
    let mut labels = vec![
        json!({"name": "feature", "value": f.name}),
        json!({"name": "epic", "value": comp.context}),
        json!({"name": "suite", "value": comp.context}),
        json!({"name": "subSuite", "value": comp.id}),
    ];
    if let Some(r) = &s.rule {
        labels.insert(1, json!({"name": "story", "value": r}));
    }
    for t in &tags {
        labels.push(json!({"name": "tag", "value": t}));
    }
    labels.push(json!({"name": "framework", "value": "gherkin"}));
    labels.push(json!({"name": "spec", "value": id}));
    labels.push(json!({"name": "context", "value": comp.context}));
    labels.push(json!({"name": "component", "value": comp.name}));
    if wip {
        labels.push(json!({"name": "planned", "value": "true"}));
    }
    let links: Vec<Value> = tags
        .iter()
        .filter(|t| t.starts_with("UN-"))
        .map(|t| json!({"type": "issue", "name": t, "url": format!("{needs_file}#{t}")}))
        .collect();
    let history = if values.is_empty() { id.clone() } else { format!("{id}|{}", values.join("|")) };
    let uuid = uuid();
    let mut attachments = Vec::new();
    for (n, (name, text)) in world.attachments.iter().enumerate() {
        let source = format!("{uuid}-attachment-{n}.txt");
        if std::fs::write(results.join(&source), text).is_ok() {
            attachments.push(json!({"name": name, "source": source, "type": "text/plain"}));
        }
    }
    let mut result = json!({
        "uuid": uuid,
        "historyId": history,
        "name": s.name,
        "fullName": format!("{}:{}", f.path, s.name),
        "status": outcome.status,
        "stage": "finished",
        "start": start,
        "stop": stop,
        "steps": step_results,
        "parameters": row.iter().map(|(k, v)| json!({"name": k, "value": v})).collect::<Vec<_>>(),
        "labels": labels,
        "links": links,
        "attachments": attachments,
    });
    if let Some(m) = &outcome.message {
        result["statusDetails"] = json!({"message": m, "trace": format!("{}:{}", f.path, s.line)});
    }
    let path = results.join(format!("{uuid}-result.json"));
    if let Err(e) = std::fs::write(&path, serde_json::to_vec_pretty(&result).unwrap()) {
        die(&format!("{}: {e}", path.display()));
    }
    let row_label = if values.is_empty() { String::new() } else { format!(" [{}]", values.join(" | ")) };
    (outcome.status, row_label, outcome.message)
}
