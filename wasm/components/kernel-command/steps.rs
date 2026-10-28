//! The kernel's step definitions: the shared steps (a step text the features
//! of more than one context use) and kernel/command's own
//! (dhf/spec/01-start-a-project/command.feature,
//! dhf/spec/11-project-in-a-folder/command.feature).
//!
//! Shared steps and the context they set (docs/wow/harness.md, "A shared
//! step's context keys are the kernel's"):
//! - `a project set up by rdm-spec`: paths["project"], cwd.
//! - `"rdm-spec ARGS" is run`: last (the run), in cwd or a folder that is no project.
//! - `it exits N`: reads last.

use crate::world::*;
use rdm_kernel::edn::{self, Edn};

const SRC: &str = "kernel-command/steps.rs";

pub fn register(s: &mut Steps) {
    s.def(SRC, r"a project set up by rdm-spec", a_project_set_up);
    s.def(SRC, r#""rdm-spec ([^"]*)" is run"#, rdm_spec_is_run);
    s.def(SRC, r"it exits (\d+)", it_exits);
    s.def(
        SRC,
        r"its standard output, read as EDN, is \{:help \[\{:task T :usage U :does D\} \.\.\.\]\}, one map per task in the order below, T the task's name as a string",
        help_is_the_table,
    );
    s.def(SRC, r"it lists the task (\S+) with its usage (.+), described as (.+)", help_lists_the_task);
    s.def(SRC, r"it exits 2, names (\S+), and lists every task", unknown_task_refused);
    // Ticket 04: how every task reports.
    s.def(SRC, r"a copy of rdm-spec's own project in which the configured results directory does not exist", copy_of_own_project);
    s.def(SRC, r"it exits 2 and names the missing directory", names_missing_directory);
    s.def(SRC, r"the configured results directory is (\S+)", configured_results);
    s.def(SRC, r"the environment variable (\S+) is (\S+)", environment_variable);
    s.def(SRC, r#""rdm-spec ([^"]*)" is run with no --results"#, rdm_spec_is_run);
    s.def(SRC, r"it reads (\S+)", it_reads);
    s.def(SRC, r"a spec that passes every check", spec_passing);
    s.def(SRC, r"a feature file in which one scenario carries no level tag", feature_without_level);
    s.def(SRC, r#"the error stream holds one line per finding: "FILE:LINE: RULE: what is wrong", FILE and LINE left out where none applies"#, error_lines);
    s.def(SRC, r"the standard output holds only the task's result, as EDN", stdout_is_edn);
    s.def(SRC, r"a project whose spec has a scenario with no level and a view older than the model", level_and_stale_view);
    s.def(SRC, r"it exits 1 and names both the missing level and the stale view", names_level_and_view);
    // Ticket 05: a project in one folder of a larger repository.
    s.def(SRC, r"a repository whose project is its folder wallet/, holding wallet/dhf/harness/config\.edn, whose commits tagged spec-2 and spec-3 change one component's scenarios, and whose dhf/cycles/03/cycle\.edn names the cut spec-3", wallet_repository);
    s.def(SRC, r"files outside wallet/ at the same paths a project at the root would use: dhf/harness/config\.edn and dhf/spec/model\.edn", decoys_outside);
    s.def(SRC, r#""rdm-spec ([^"]*)" is run in wallet/"#, run_in_wallet);
    s.def(SRC, r"it exits as it would for the same project at a repository's root, and (.+)", exits_as_at_root);
    s.def(SRC, r"it reads nothing outside wallet/", reads_nothing_outside);
}

/// A project set up by rdm-spec: this build's "rdm-spec init" (ED-12).
pub fn a_project_set_up(w: &mut World, _: &[String]) -> StepResult {
    let room = w.fresh_dir("room")?;
    let ran = w.rdm_spec("init project", &room)?;
    if ran.exit != Some(0) {
        return broken(format!("init did not set the project up: {}", ran.summary()));
    }
    let dir = room.join("project");
    w.paths.insert("project".into(), dir.clone());
    w.cwd = Some(dir);
    Ok(())
}

/// An upper-case placeholder of a step (DIR, ROOM, R1) is the path a step
/// of the scenario made for it (dhf/spec/README.md).
pub fn placeholders(w: &World, text: &str) -> String {
    text.split(' ')
        .map(|word| {
            let (head, tail) = word.split_once('/').map(|(h, t)| (h, Some(t))).unwrap_or((word, None));
            match w.paths.get(head) {
                Some(p) if head.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit()) => match tail {
                    Some(t) => format!("{}/{t}", p.display()),
                    None => p.display().to_string(),
                },
                _ => word.to_string(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

pub fn rdm_spec_is_run(w: &mut World, c: &[String]) -> StepResult {
    let cwd = w.workdir()?;
    let args = placeholders(w, &c[0]);
    w.rdm_spec(&args, &cwd)?;
    Ok(())
}

pub fn it_exits(w: &mut World, c: &[String]) -> StepResult {
    let code: i32 = c[0].parse().or_broken("an exit code")?;
    w.last()?.expect_exit(code)
}

/// The help result read as EDN: the maps of its :help vector.
fn help_rows(w: &World) -> Result<Vec<(String, String, String)>, Wrong> {
    let ran = w.last()?;
    ran.expect_exit(0)?;
    let v = edn::read(&ran.stdout).map_err(|e| Wrong::Fail(format!("standard output is not EDN ({e}): {}", ran.summary())))?;
    let Edn::Map(entries) = &v else { return fail(format!("not a map: {}", ran.stdout)) };
    check(entries.len() == 1 && v.get("help").is_some(), || format!("not {{:help [...]}}: {}", ran.stdout))?;
    let Some(Edn::Vector(items)) = v.get("help") else { return fail(format!(":help is not a vector: {}", ran.stdout)) };
    let mut rows = Vec::new();
    for m in items {
        let Edn::Map(kv) = m else { return fail(format!("an entry that is not a map: {}", edn::print(m))) };
        let keys: Vec<String> = kv.iter().map(|(k, _)| edn::print(k)).collect();
        check(kv.len() == 3, || format!("an entry with keys {keys:?}, not :task :usage :does"))?;
        let get = |k: &str| m.get(k).and_then(|x| x.as_str()).map(String::from);
        match (get("task"), get("usage"), get("does")) {
            (Some(t), Some(u), Some(d)) => rows.push((t, u, d)),
            _ => return fail(format!("an entry without string :task, :usage and :does: {}", edn::print(m))),
        }
    }
    Ok(rows)
}

pub fn help_is_the_table(w: &mut World, _: &[String]) -> StepResult {
    let rows = help_rows(w)?;
    let table = w.outline_rows(&w.scenario.id().unwrap_or_default())?;
    let got: Vec<&str> = rows.iter().map(|r| r.0.as_str()).collect();
    let want: Vec<&str> = table.iter().map(|r| r["task"].as_str()).collect();
    check(got == want, || format!("help lists the tasks {got:?}, the table {want:?}"))
}

pub fn help_lists_the_task(w: &mut World, c: &[String]) -> StepResult {
    let rows = help_rows(w)?;
    let found = rows.iter().find(|r| r.0 == c[0]);
    check(found.is_some(), || format!("help does not list {}", c[0]))?;
    let (_, usage, does) = found.unwrap();
    check(usage == &c[1], || format!("{}'s usage is {usage:?}, not {:?}", c[0], c[1]))?;
    check(does == &c[2], || format!("{} is described as {does:?}, not {:?}", c[0], c[2]))
}

pub fn unknown_task_refused(w: &mut World, c: &[String]) -> StepResult {
    let tasks: Vec<String> = w.outline_rows("spec-0176")?.into_iter().map(|r| r["task"].clone()).collect();
    let ran = w.last()?;
    ran.expect_exit(2)?;
    check(ran.stderr.contains(&c[0]), || format!("the error stream does not name {}: {}", c[0], ran.summary()))?;
    check(ran.stdout.is_empty(), || format!("a refusal printed a result: {}", ran.summary()))?;
    let words: Vec<&str> = ran.stderr.split(|ch: char| !(ch.is_alphanumeric() || ch == '-')).collect();
    let missing: Vec<&String> = tasks.iter().filter(|t| !words.contains(&t.as_str())).collect();
    check(missing.is_empty(), || format!("the tasks {missing:?} are not listed: {}", ran.summary()))
}

// ---- ticket 04: how every task reports ----

fn copy_tree(from: &std::path::Path, to: &std::path::Path) -> StepResult {
    let meta = std::fs::symlink_metadata(from).or_broken(&format!("reading {}", from.display()))?;
    if meta.is_dir() {
        std::fs::create_dir_all(to).or_broken("making a folder")?;
        for e in std::fs::read_dir(from).or_broken("listing a folder")?.flatten() {
            copy_tree(&e.path(), &to.join(e.file_name()))?;
        }
    } else if meta.file_type().is_symlink() {
        let t = std::fs::read_link(from).or_broken("reading a link")?;
        std::os::unix::fs::symlink(t, to).or_broken("copying a link")?;
    } else {
        std::fs::copy(from, to).or_broken("copying a file")?;
    }
    Ok(())
}

/// A copy of the project the tests run in, holding what a clean room holds:
/// the paths its configuration keeps (docs/wow/harness.md, "A fixture copied
/// from the room"), never its implementation or what its runs wrote.
fn copy_of_own_project(w: &mut World, _: &[String]) -> StepResult {
    let repo = w.repo.clone();
    let text = std::fs::read_to_string(repo.join("dhf/harness/config.edn")).or_broken("reading the configuration")?;
    let config = edn::read(&text).or_broken("reading the configuration")?;
    let keep: Vec<String> = config
        .get("clean-room")
        .and_then(|c| c.get("keep"))
        .map(|k| k.items().iter().filter_map(|x| x.as_str().map(String::from)).collect())
        .unwrap_or_default();
    let results = config.get("results-dir").and_then(|r| r.as_str()).unwrap_or("dhf/allure-results").to_string();
    let copy = w.fresh_dir("copy")?;
    for k in &keep {
        if repo.join(k).exists() {
            copy_tree(&repo.join(k), &copy.join(k))?;
        }
    }
    if copy.join(&results).exists() {
        return broken(format!("the copy holds {results}"));
    }
    w.vars.insert("missing".into(), results);
    w.cwd = Some(copy);
    Ok(())
}

fn names_missing_directory(w: &mut World, _: &[String]) -> StepResult {
    let missing = w.vars.get("missing").cloned().ok_or(Wrong::Broken("no missing directory was set up".into()))?;
    let ran = w.last()?;
    ran.expect_exit(2)?;
    check(ran.stderr.lines().any(|l| l.starts_with("cannot-run:") && l.contains(&missing)), || format!("the refusal does not name {missing}: {}", ran.summary()))?;
    check(ran.stdout.is_empty(), || format!("a task that cannot run printed a result: {}", ran.summary()))
}

/// A project set up by rdm-spec whose configured results directory exists:
/// the skeleton configures dhf/allure-results.
fn configured_results(w: &mut World, c: &[String]) -> StepResult {
    a_project_set_up(w, &[])?;
    let project = w.path("project")?;
    let text = std::fs::read_to_string(project.join("dhf/harness/config.edn")).or_broken("reading the configuration")?;
    let config = edn::read(&text).or_broken("reading the configuration")?;
    if config.get("results-dir").and_then(|r| r.as_str()) != Some(c[0].as_str()) {
        return broken(format!("the skeleton does not configure {}", c[0]));
    }
    std::fs::create_dir_all(project.join(&c[0])).or_broken("making the results directory")?;
    std::fs::create_dir_all(project.join("other")).or_broken("making another results directory")?;
    Ok(())
}

fn environment_variable(w: &mut World, c: &[String]) -> StepResult {
    if c[1] == "unset" {
        w.env.insert(c[0].clone(), None);
        return Ok(());
    }
    let dir = w.fresh_dir(&c[1])?;
    w.paths.insert(c[1].clone(), dir.clone());
    w.env.insert(c[0].clone(), Some(dir.display().to_string()));
    Ok(())
}

/// The task's result names the results directory it read, as status's does
/// ({:status {:results DIR ...}}, dhf/spec/05-verify-by-results/status.feature).
fn it_reads(w: &mut World, c: &[String]) -> StepResult {
    let want = placeholders(w, &c[0]);
    let ran = w.last()?;
    ran.expect_exit(0)?;
    let v = edn::read(&ran.stdout).map_err(|e| Wrong::Fail(format!("not EDN ({e}): {}", ran.summary())))?;
    let read = match &v {
        Edn::Map(m) if m.len() == 1 => m[0].1.get("results").and_then(|r| r.as_str()).map(String::from),
        _ => None,
    };
    check(read.as_deref() == Some(want.as_str()), || format!("it read {read:?}, not {want}: {}", ran.summary()))
}

fn spec_passing(w: &mut World, _: &[String]) -> StepResult {
    a_project_set_up(w, &[])
}

/// A stamped feature file of the project, one scenario with no level tag.
fn write_feature_without_level(w: &mut World) -> Result<String, Wrong> {
    let project = w.path("project")?;
    let rel = "dhf/spec/sauna/booking.feature";
    std::fs::create_dir_all(project.join("dhf/spec/sauna")).or_broken("making a folder")?;
    std::fs::write(
        project.join(rel),
        "Feature: Booking\n  Books a sauna.\n\n  Scenario: A guest books a slot\n    Given a free slot\n    When the guest books it\n    Then the slot is taken\n"
            .replace("  Scenario:", "  @spec-0001\n  Scenario:"),
    )
    .or_broken("writing the feature file")?;
    std::fs::write(project.join("dhf/spec/ids.edn"), "{:issued 1}\n").or_broken("writing the ledger")?;
    w.vars.insert("feature".into(), rel.into());
    Ok(rel.into())
}

fn feature_without_level(w: &mut World, _: &[String]) -> StepResult {
    a_project_set_up(w, &[])?;
    write_feature_without_level(w)?;
    Ok(())
}

fn finding_shape(line: &str) -> bool {
    let re = regex::Regex::new(r"^(?:[^\s:]+(?::\d+)?: )?(?:warning: )?[a-z0-9][a-z0-9-]*: \S.*$").unwrap();
    re.is_match(line)
}

fn error_lines(w: &mut World, _: &[String]) -> StepResult {
    let feature = w.vars.get("feature").cloned().unwrap_or_default();
    let ran = w.last()?;
    check(!ran.stderr.trim().is_empty(), || "the error stream names no finding".into())?;
    for line in ran.stderr.lines() {
        check(finding_shape(line), || format!("not a finding line: {line:?}"))?;
    }
    check(ran.stderr.lines().any(|l| l.starts_with(&format!("{feature}:5: "))), || format!("no finding names {feature}:5, the scenario: {}", ran.stderr))
}

fn stdout_is_edn(w: &mut World, _: &[String]) -> StepResult {
    let ran = w.last()?;
    check(edn::read(&ran.stdout).is_ok(), || format!("standard output is not one EDN value: {:?}", ran.stdout))
}

fn level_and_stale_view(w: &mut World, _: &[String]) -> StepResult {
    a_project_set_up(w, &[])?;
    write_feature_without_level(w)?;
    let project = w.path("project")?;
    std::fs::create_dir_all(project.join("dhf/views")).or_broken("making dhf/views")?;
    let stale = format!("%% model-sha256: {}\nC4Context\n  title System\n", "0".repeat(64));
    std::fs::write(project.join("dhf/views/context.mmd"), stale).or_broken("writing a view")?;
    Ok(())
}

fn names_level_and_view(w: &mut World, _: &[String]) -> StepResult {
    let feature = w.vars.get("feature").cloned().unwrap_or_default();
    let ran = w.last()?;
    ran.expect_exit(1)?;
    check(ran.stderr.lines().any(|l| l.starts_with(&format!("{feature}:")) && !l.contains("warning:")), || format!("the missing level is not named: {}", ran.summary()))?;
    check(ran.stderr.lines().any(|l| l.starts_with("dhf/views/context") && !l.contains("warning:")), || format!("the stale view is not named: {}", ran.summary()))
}

// ---- ticket 05: a project in one folder of a larger repository ----

const LEDGER_V2: &str = "Feature: Ledger\n  Keeps the wallet's entries.\n\n  @spec-0001 @e2e\n  Scenario: An entry is recorded\n    Given an empty ledger\n    When an entry is added\n    Then the ledger holds it\n";
const LEDGER_V3: &str = "Feature: Ledger\n  Keeps the wallet's entries.\n\n  @spec-0001 @e2e\n  Scenario: An entry is recorded\n    Given an empty ledger\n    When an entry of 10 is added\n    Then the ledger holds it, dated\n";
const WALLET_MODEL: &str = "{:system {:id :wallet :name \"Wallet\" :description \"Keeps a wallet's entries\"}\n :people [{:id :owner :name \"Owner\" :description \"Keeps the wallet\" :actor #{:human}}]\n :containers [{:id :app :name \"app\" :kind :runs :technology \"see stack\" :description \"The wallet\"\n               :contexts [{:id :pay :name \"pay\" :description \"Payments\"\n                           :components [{:id :pay/ledger :name \"Ledger\" :spec \"pay/ledger\" :description \"Keeps entries\"}]}]}]\n :relationships [{:from :owner :to :app :description \"Keeps entries with\"}]\n :views [{:key :context :level :context :description \"The wallet in its world\"}]}\n";
const WALLET_NEEDS: &str = "{\"UN-001\" {:text \"An owner keeps the wallet's entries.\" :sources []}}\n";

/// Makes a project's history in a repository: at `at` (\".\" or a folder), the
/// skeleton init writes, the wallet's spec, two commits tagged spec-2 and
/// spec-3 that change the Ledger's scenario, and cycle 03 naming spec-3.
fn wallet_history(w: &mut World, repo: &std::path::Path, at: &str) -> StepResult {
    let scratch = w.fresh_dir("init")?;
    let ran = w.rdm_spec("init wallet", &scratch)?;
    if ran.exit != Some(0) {
        return broken(format!("init did not make the wallet: {}", ran.summary()));
    }
    let made = scratch.join("wallet");
    let _ = std::fs::remove_dir_all(made.join(".git"));
    let project = if at == "." { repo.to_path_buf() } else { repo.join(at) };
    copy_tree(&made, &project)?;
    std::fs::write(project.join("dhf/spec/model.edn"), WALLET_MODEL).or_broken("writing the model")?;
    std::fs::write(project.join("dhf/spec/needs.edn"), WALLET_NEEDS).or_broken("writing the needs")?;
    std::fs::write(project.join("dhf/spec/ids.edn"), "{:issued 1}\n").or_broken("writing the ledger")?;
    std::fs::create_dir_all(project.join("dhf/spec/pay")).or_broken("making dhf/spec/pay")?;
    std::fs::write(project.join("dhf/spec/pay/ledger.feature"), LEDGER_V2.replace("Feature: Ledger", "@UN-001\nFeature: Ledger")).or_broken("writing the feature")?;
    w.git(repo, &["add", "-A"])?;
    w.git(repo, &["commit", "-q", "--no-verify", "-m", "The ledger"])?;
    w.git(repo, &["tag", "spec-2"])?;
    std::fs::write(project.join("dhf/spec/pay/ledger.feature"), LEDGER_V3.replace("Feature: Ledger", "@UN-001\nFeature: Ledger")).or_broken("writing the feature")?;
    std::fs::create_dir_all(project.join("dhf/cycles/03")).or_broken("making the cycle")?;
    std::fs::write(
        project.join("dhf/cycles/03/cycle.edn"),
        "{:cut \"spec-3\" :from \"spec-2\" :scope :whole :builder \"builder\" :grader \"grader\" :spec-owner \"spec-owner\"}\n",
    )
    .or_broken("writing cycle.edn")?;
    w.git(repo, &["add", "-A"])?;
    w.git(repo, &["commit", "-q", "--no-verify", "-m", "The ledger dates its entries"])?;
    w.git(repo, &["tag", "spec-3"])?;
    Ok(())
}

fn wallet_repository(w: &mut World, _: &[String]) -> StepResult {
    let repo = w.fresh_dir("monorepo")?;
    w.git(&repo, &["init", "-q"])?;
    std::fs::write(repo.join("README.md"), "# A larger repository\n").or_broken("writing a README")?;
    wallet_history(w, &repo, "wallet")?;
    w.paths.insert("repo".into(), repo.clone());
    w.paths.insert("wallet".into(), repo.join("wallet"));
    w.cwd = Some(repo.join("wallet"));
    Ok(())
}

/// Files outside wallet/ where a project at the root would keep them, in both
/// cuts, naming a component and a prefix the wallet does not: a task that
/// read them would say so.
fn decoys_outside(w: &mut World, _: &[String]) -> StepResult {
    let repo = w.path("repo")?;
    std::fs::create_dir_all(repo.join("dhf/harness")).or_broken("making dhf/harness")?;
    std::fs::create_dir_all(repo.join("dhf/spec/decoy")).or_broken("making dhf/spec")?;
    std::fs::write(repo.join("dhf/harness/config.edn"), "{:id-prefix \"decoy\" :spec-dir \"dhf/spec\" :results-dir \"decoy-results\"}\n").or_broken("writing a decoy")?;
    std::fs::write(
        repo.join("dhf/spec/model.edn"),
        "{:system {:id :decoy :name \"Decoy\" :description \"Not the wallet\"} :containers [{:id :d :name \"d\" :kind :runs :description \"Decoy\" :contexts [{:id :decoy :name \"decoy\" :description \"Decoy\" :components [{:id :decoy/thing :name \"Thing\" :spec \"decoy/thing\" :description \"Decoy\"}]}]}]}\n",
    )
    .or_broken("writing a decoy")?;
    std::fs::write(repo.join("dhf/spec/decoy/thing.feature"), "Feature: Thing\n\n  @decoy-0001 @e2e\n  Scenario: A decoy changes\n    Given a decoy\n").or_broken("writing a decoy")?;
    // Into both cuts: spec-2 and spec-3 are moved onto commits holding them.
    w.git(&repo, &["checkout", "-q", "spec-2"])?;
    w.git(&repo, &["add", "-A"])?;
    w.git(&repo, &["commit", "-q", "--no-verify", "-m", "Decoys"])?;
    w.git(&repo, &["tag", "-f", "spec-2"])?;
    w.git(&repo, &["checkout", "-q", "spec-3", "--", "wallet"])?;
    std::fs::write(repo.join("dhf/spec/decoy/thing.feature"), "Feature: Thing\n\n  @decoy-0001 @e2e\n  Scenario: A decoy changed\n    Given a decoy, changed\n").or_broken("writing a decoy")?;
    w.git(&repo, &["add", "-A"])?;
    w.git(&repo, &["commit", "-q", "--no-verify", "-m", "The ledger dates its entries; decoys change"])?;
    w.git(&repo, &["tag", "-f", "spec-3"])?;
    w.git(&repo, &["branch", "-f", "main"])?;
    w.git(&repo, &["checkout", "-q", "main"])?;
    w.snaps.insert("decoys".into(), snapshot(&repo.join("dhf")));
    Ok(())
}

/// The task's arguments with ROOM a folder of the scenario's: a new path for
/// fence, which builds it, an empty folder for verify.
fn task_args(w: &mut World, task: &str, name: &str) -> Result<String, Wrong> {
    if task.contains("ROOM") {
        let room = if task.starts_with("verify") { w.fresh_dir(name)? } else { w.dir.join(name) };
        w.paths.insert("ROOM".into(), room);
    }
    Ok(placeholders(w, task))
}

fn run_in_wallet(w: &mut World, c: &[String]) -> StepResult {
    let wallet = w.path("wallet")?;
    let args = task_args(w, &c[0], "ROOM")?;
    w.rdm_spec(&args, &wallet)?;
    w.vars.insert("task".into(), c[0].clone());
    w.paths.insert("wallet-room".into(), w.paths.get("ROOM").cloned().unwrap_or_default());
    Ok(())
}

/// Committed files of a folder, relative to it, .git left out.
fn tree(dir: &std::path::Path) -> Vec<String> {
    files(dir)
}

fn exits_as_at_root(w: &mut World, c: &[String]) -> StepResult {
    let in_folder = w.last()?.clone();
    let task = w.vars.get("task").cloned().unwrap_or_default();
    // The same project at a repository's root: its own history, the same cuts.
    let twin = w.fresh_dir("twin")?;
    w.git(&twin, &["init", "-q"])?;
    wallet_history(w, &twin, ".")?;
    let args = task_args(w, &task, "ROOM-twin")?;
    let at_root = w.rdm_spec(&args, &twin)?;
    w.last = Some(in_folder.clone());
    check(in_folder.exit == at_root.exit, || format!("in wallet/ it exits {:?}; at a root, {:?}: {}", in_folder.exit, at_root.exit, in_folder.summary()))?;
    let wallet = w.path("wallet")?;
    let reads = c[0].as_str();
    let task_name = task.split_whitespace().next().unwrap_or_default();
    match task_name {
        "fence" => {
            in_folder.expect_exit(0)?;
            let room = w.path("wallet-room")?;
            let twin_room = w.path("ROOM")?;
            check(room.join("dhf/harness/config.edn").is_file() && !room.join("wallet").exists(), || format!("ROOM does not hold wallet/'s files at its own root: {:?}", tree(&room)))?;
            let got = tree(&room);
            let want = tree(&twin_room);
            check(got == want, || format!("{reads}: ROOM holds {got:?}; the same project at a root gives {want:?}"))?;
            let config = std::fs::read(room.join("dhf/harness/config.edn")).unwrap_or_default();
            check(config == std::fs::read(wallet.join("dhf/harness/config.edn")).unwrap_or_default(), || "ROOM's configuration is not wallet/'s".into())
        }
        "verify" => {
            let ratified = wallet.join("dhf/cycles/03/ratified.edn");
            let text = std::fs::read_to_string(&ratified).map_err(|_| Wrong::Fail(format!("{reads}: no wallet/dhf/cycles/03/ratified.edn: {}", in_folder.summary())))?;
            let r = edn::read(&text).map_err(|e| Wrong::Fail(format!("ratified.edn is not EDN: {e}")))?;
            check(r.get("cut").and_then(|x| x.as_str()) == Some("spec-3"), || "ratified.edn does not name the cut spec-3".into())?;
            let checkout = r.get("checkout").and_then(|x| x.as_str()).ok_or(Wrong::Fail("ratified.edn names no checkout".into()))?;
            let dir = wallet.join(checkout);
            check(dir.join("dhf/harness/config.edn").is_file() && !dir.join("wallet").exists(), || format!("{reads}: the checkout {checkout} is not wallet/ at its root"))?;
            let feature = std::fs::read_to_string(dir.join("dhf/spec/pay/ledger.feature")).unwrap_or_default();
            check(feature.contains("an entry of 10"), || "the checkout is not wallet/ at spec-3".into())
        }
        "checklist" => {
            in_folder.expect_exit(0)?;
            let text = std::fs::read_to_string(wallet.join("dhf/cycles/03/checklist.json")).map_err(|_| Wrong::Fail(format!("{reads}: no wallet/dhf/cycles/03/checklist.json")))?;
            let v: serde_json::Value = serde_json::from_str(&text).map_err(|e| Wrong::Fail(format!("checklist.json is not JSON: {e}")))?;
            let ids: Vec<String> = v["items"].as_array().map(|a| a.iter().filter_map(|i| i["id"].as_str().map(String::from)).collect()).unwrap_or_default();
            check(ids == ["spec-0001"], || format!("{reads}: the items are {ids:?}"))?;
            check(v["cut"] == "spec-3", || "the checklist is not of spec-3".into())?;
            let title = v["items"][0]["title"].as_str().unwrap_or_default().to_string();
            check(title == "An entry is recorded", || format!("the item's title is {title:?}"))
        }
        "scopes" => {
            in_folder.expect_exit(0)?;
            check(in_folder.stdout.contains("pay/ledger"), || format!("{reads}: the Ledger is not named: {}", in_folder.summary()))?;
            check(in_folder.stdout == at_root.stdout, || format!("it names {:?}; at a root, {:?}", in_folder.stdout, at_root.stdout))
        }
        other => broken(format!("no check for the task {other}")),
    }
}

fn reads_nothing_outside(w: &mut World, _: &[String]) -> StepResult {
    let repo = w.path("repo")?;
    let ran = w.last()?.clone();
    let all = format!("{}{}", ran.stdout, ran.stderr);
    check(!all.contains("decoy") && !all.contains("Decoy"), || format!("it read the files outside wallet/: {}", ran.summary()))?;
    check(snapshot(&repo.join("dhf")) == w.snaps.get("decoys").cloned().unwrap_or_default(), || "the files outside wallet/ changed, or something was written beside them".into())?;
    if let Ok(room) = w.path("wallet-room") {
        let held = files(&room);
        check(!held.iter().any(|f| f.contains("decoy")), || format!("ROOM holds files from outside wallet/: {held:?}"))?;
    }
    Ok(())
}
