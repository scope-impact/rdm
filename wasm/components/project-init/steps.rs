//! The step definitions of project/init (dhf/spec/01-start-a-project/
//! init.feature). Context keys: paths["room"] (the folder init runs in),
//! paths["DIR"], snaps["before"] (of "sauna").

use crate::world::*;
use rdm_kernel::edn::{self, Edn};
use std::path::{Path, PathBuf};

const SRC: &str = "project-init/steps.rs";

pub fn register(s: &mut Steps) {
    s.def(SRC, r#"no directory at the path "([^"]+)""#, no_directory_at);
    s.def(SRC, r"no directory at DIR/(\S+)", no_directory_under_dir);
    s.def(SRC, r#"a directory at the path "([^"]+)""#, a_directory_at);
    s.def(SRC, r#"a project just initialised at "([^"]+)""#, just_initialised);
    s.def(SRC, r#""rdm-spec ([^"]*)" is run in "([^"]+)""#, run_in_folder);
    s.def(SRC, r#""([^"]+)" is a git repository with no commit yet, whose working tree holds exactly:"#, holds_exactly);
    s.def(SRC, r"init lists every file it wrote and the next steps", lists_what_it_wrote);
    s.def(SRC, r"DIR/(\S+) is a git repository holding the skeleton", dir_holds_the_skeleton);
    s.def(SRC, r"it exits 2 and names the path", exits_2_naming_the_path);
    s.def(SRC, r#"nothing in "([^"]+)" changes"#, nothing_changes);
    s.def(SRC, r#"(?:the harness configuration|it) sets the id prefix to "([^"]+)""#, sets_the_prefix);
    s.def(SRC, r"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens", bad_prefix);
    s.def(SRC, r"it exits 2 and says the prefix UN is taken by need tags", prefix_un);
    s.def(SRC, r"it passes", it_passes);
}

fn room(w: &mut World) -> Result<PathBuf, Wrong> {
    if let Some(p) = w.paths.get("room") {
        return Ok(p.clone());
    }
    let r = w.fresh_dir("room")?;
    w.paths.insert("room".into(), r.clone());
    w.cwd = Some(r.clone());
    Ok(r)
}

fn no_directory_at(w: &mut World, c: &[String]) -> StepResult {
    let r = room(w)?;
    check(!r.join(&c[0]).exists(), || format!("{} exists", c[0]))
}

fn no_directory_under_dir(w: &mut World, c: &[String]) -> StepResult {
    let dir = w.fresh_dir("DIR")?;
    w.paths.insert("DIR".into(), dir.clone());
    room(w)?;
    check(!dir.join(&c[0]).exists(), || format!("DIR/{} exists", c[0]))
}

fn a_directory_at(w: &mut World, c: &[String]) -> StepResult {
    let r = room(w)?;
    let d = r.join(&c[0]);
    std::fs::create_dir_all(&d).or_broken("making the directory")?;
    std::fs::write(d.join("notes.txt"), "kept\n").or_broken("writing into it")?;
    w.snaps.insert("before".into(), snapshot(&d));
    Ok(())
}

/// A project just initialised: this build's "rdm-spec init" in a new room.
pub fn just_initialised(w: &mut World, c: &[String]) -> StepResult {
    let r = room(w)?;
    let ran = w.rdm_spec(&format!("init {}", c[0]), &r)?;
    if ran.exit != Some(0) {
        return broken(format!("init did not set the project up: {}", ran.summary()));
    }
    w.paths.insert("project".into(), r.join(&c[0]));
    Ok(())
}

fn run_in_folder(w: &mut World, c: &[String]) -> StepResult {
    let base = w.workdir()?;
    let cwd = base.join(&c[1]);
    w.rdm_spec(&c[0], &cwd)?;
    Ok(())
}

fn is_repo_with_no_commit(w: &mut World, dir: &Path) -> StepResult {
    let inside = w.exec(&["git".into(), "rev-parse".into(), "--show-toplevel".into()], dir, None)?;
    check(inside.exit == Some(0) && Path::new(inside.stdout.trim()) == dir.canonicalize().unwrap_or_default(), || {
        format!("{} is not the root of a git repository: {}", dir.display(), inside.summary())
    })?;
    let head = w.exec(&["git".into(), "rev-parse".into(), "--verify".into(), "-q".into(), "HEAD".into()], dir, None)?;
    check(head.exit != Some(0), || format!("{} has a commit already", dir.display()))
}

/// The skeleton's paths, as spec-0086's table lists them.
fn skeleton_paths(w: &World) -> Result<Vec<String>, Wrong> {
    for f in w.features.iter() {
        for s in &f.scenarios {
            if s.tags.iter().any(|t| t == "spec-0086") {
                let table = &s.steps.iter().find(|st| !st.table.is_empty()).ok_or(Wrong::Broken("spec-0086 has no table".into()))?.table;
                let mut paths: Vec<String> = table[1..].iter().map(|r| r[0].clone()).collect();
                paths.sort();
                return Ok(paths);
            }
        }
    }
    broken("no spec-0086 in the spec")
}

fn read(dir: &Path, rel: &str) -> Result<String, Wrong> {
    std::fs::read_to_string(dir.join(rel)).map_err(|e| Wrong::Fail(format!("{rel} cannot be read: {e}")))
}

fn read_edn(dir: &Path, rel: &str) -> Result<Edn, Wrong> {
    let text = read(dir, rel)?;
    edn::read(&text).map_err(|e| Wrong::Fail(format!("{rel} is not EDN: {e}")))
}

fn strs(e: Option<&Edn>) -> Vec<String> {
    e.map(|v| v.items().iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default()
}

/// Each row's "holds", checked against what init wrote.
fn row_holds(dir: &Path, path: &str) -> StepResult {
    match path {
        "dhf/spec/model.edn" => {
            let m = read_edn(dir, path)?;
            let Edn::Map(entries) = &m else { return fail("the model is not a map") };
            check(entries.len() == 1 && m.get("system").is_some(), || format!("the model holds more than the system: {}", edn::print(&m)))?;
            let sys = m.get("system").unwrap();
            check(sys.get("id").is_some() && sys.get("name").is_some() && sys.get("description").is_some(), || "the system has no id, name or description".into())
        }
        "dhf/spec/events.edn" => check(read_edn(dir, path)? == Edn::Map(vec![]), || "the event catalogue is not empty".into()),
        "dhf/spec/needs.edn" => check(read_edn(dir, path)? == Edn::Map(vec![]), || "the needs are not empty".into()),
        "dhf/spec/ids.edn" => check(read_edn(dir, path)?.get("issued") == Some(&Edn::Int(0)), || "the ledger is not at 0".into()),
        "dhf/spec/glossary.md" => {
            let t = read(dir, path)?;
            check(t.starts_with("# ") && !t.contains("\n**"), || format!("not an empty glossary: {t}"))
        }
        "dhf/spec/README.md" => {
            let t = read(dir, path)?;
            for word in ["@spec-", "@e2e", "@contract", "Allure", "historyId", "statusDetails", "parameters", "subSuite", "framework", "planned", "issue", ":allure", ":evidence"] {
                check(t.contains(word), || format!("the spec's README does not name {word}"))?;
            }
            check(!t.contains("dhf/spec/kernel/runner.feature"), || "the README cites a path a new project does not hold".into())
        }
        "dhf/harness/config.edn" => {
            let c = read_edn(dir, path)?;
            check(c.get("id-prefix").and_then(|v| v.as_str()).is_some(), || "no :id-prefix".into())?;
            for k in ["spec-dir", "ledger", "model", "events", "needs", "views-dir", "raw-dir", "wiki-dir", "results-dir", "cycles-dir"] {
                check(c.get(k).and_then(|v| v.as_str()).is_some(), || format!("no :{k}"))?;
            }
            let room = c.get("clean-room").ok_or(Wrong::Fail("no :clean-room".into()))?;
            let deny = room.get("deny").ok_or(Wrong::Fail("no :deny".into()))?;
            check(deny.get("implementation") == Some(&Edn::Bool(true)), || "the deny-list does not deny the implementation".into())?;
            check(strs(deny.get("measurements")) == ["dhf/cycles"], || "the deny-list's measurements are not dhf/cycles".into())?;
            let generated = strs(deny.get("generated"));
            check(generated.contains(&"site".to_string()) && generated.contains(&"site-src".to_string()), || format!("the deny-list's generated site is {generated:?}"))?;
            let mut keep = strs(room.get("keep"));
            keep.sort();
            let mut want: Vec<String> = [".gitignore", ".githooks", ".github", "dhf/spec", "dhf/harness", "dhf/raw", "dhf/wiki", "dhf/views", "dhf/decisions"].iter().map(|s| s.to_string()).collect();
            want.sort();
            check(keep == want, || format!("kept paths {keep:?}, not {want:?}"))?;
            let agency = c.get("agency").ok_or(Wrong::Fail("no :agency".into()))?;
            check(strs(agency.get("watch")) == ["/", "~"] && agency.get("tmp").and_then(|v| v.as_str()) == Some("/tmp"), || "the watched directories are not / and ~ with /tmp".into())?;
            let verify = c.get("verify").map(|v| v.items().to_vec()).unwrap_or_default();
            check(verify.len() == 1 && verify[0].get("kind") == Some(&Edn::kw("tests")), || "the :verify is not one :tests check".into())?;
            check(matches!(verify[0].get("command"), None | Some(Edn::Nil)), || "the :tests check has a command".into())?;
            check(c.get("implementation").map(|v| v.items().is_empty()).unwrap_or(true), || "the configuration names implementation paths".into())
        }
        "dhf/harness/stack.md" => {
            let t = read(dir, path)?;
            let rows: Vec<&str> = t.lines().filter(|l| l.starts_with('|')).collect();
            check(rows.len() == 2 && rows[1].contains("---"), || format!("not a stack table with no rows: {t}"))
        }
        "dhf/raw/README.md" => check(read(dir, path)?.to_lowercase().contains("never edited"), || "the raw README holds no rules".into()),
        "dhf/raw/sources.edn" => check(read_edn(dir, path)? == Edn::Map(vec![]), || "the sources are not empty".into()),
        "dhf/wiki/index.md" => {
            let t = read(dir, path)?;
            let after = t.split("## Pages").nth(1).ok_or(Wrong::Fail("the wiki index has no Pages section".into()))?;
            check(!after.lines().any(|l| l.trim_start().starts_with("- ")) && t.contains("## Rules"), || format!("not the wiki's rules and an empty list: {t}"))
        }
        "dhf/decisions/README.md" => check(read(dir, path)?.to_lowercase().contains("decision"), || "the decisions README says nothing of decisions".into()),
        ".githooks/pre-commit" => {
            use std::os::unix::fs::PermissionsExt;
            let t = read(dir, path)?;
            let mode = std::fs::metadata(dir.join(path)).map(|m| m.permissions().mode()).unwrap_or(0);
            check(t.contains("rdm-spec check") && mode & 0o111 != 0, || "the hook does not run rdm-spec check, or is not executable".into())?;
            let hp = std::process::Command::new("git").args(["config", "--get", "core.hooksPath"]).current_dir(dir).output().or_broken("git config")?;
            check(String::from_utf8_lossy(&hp.stdout).trim() == ".githooks", || "core.hooksPath is not .githooks".into())
        }
        ".github/workflows/cut.yml" => {
            let t = read(dir, path)?;
            check(t.contains("rdm-spec cut") && t.contains("push") && t.contains("default_branch"), || format!("not a workflow running rdm-spec cut on every push to the default branch: {t}"))
        }
        ".gitignore" => {
            let t = read(dir, path)?;
            let lines: Vec<&str> = t.lines().filter(|l| !l.trim().is_empty() && !l.starts_with('#')).collect();
            check(lines == ["dhf/allure-results/", "dhf/cycles/*/results/", "dhf/cycles/*/build/", "site-src/", "site/"], || format!("the ignore file holds {lines:?}"))
        }
        other => broken(format!("no check for {other}")),
    }
}

fn holds_exactly(w: &mut World, c: &[String]) -> StepResult {
    w.last()?.expect_exit(0)?;
    let dir = w.workdir()?.join(&c[0]);
    is_repo_with_no_commit(w, &dir)?;
    let want = skeleton_paths(w)?;
    let got = files(&dir);
    check(got == want, || format!("the working tree holds {got:?}, not {want:?}"))?;
    for p in &want {
        row_holds(&dir, p)?;
    }
    Ok(())
}

fn lists_what_it_wrote(w: &mut World, _: &[String]) -> StepResult {
    let ran = w.last()?.clone();
    let v = edn::read(&ran.stdout).map_err(|e| Wrong::Fail(format!("init's output is not EDN ({e}): {}", ran.summary())))?;
    let init = v.get("init").ok_or(Wrong::Fail(format!("no :init in {}", ran.stdout)))?;
    let mut wrote = strs(init.get("wrote"));
    wrote.sort();
    let want = skeleton_paths(w)?;
    check(wrote == want, || format!("init lists {wrote:?}, not every file it wrote, {want:?}"))?;
    check(!strs(init.get("next")).is_empty(), || "init names no next step".into())
}

fn dir_holds_the_skeleton(w: &mut World, c: &[String]) -> StepResult {
    w.last()?.expect_exit(0)?;
    let dir = w.path("DIR")?.join(&c[0]);
    is_repo_with_no_commit(w, &dir)?;
    let want = skeleton_paths(w)?;
    let got = files(&dir);
    check(got == want, || format!("DIR/{} holds {got:?}, not the skeleton {want:?}", c[0]))
}

fn exits_2_naming_the_path(w: &mut World, _: &[String]) -> StepResult {
    let ran = w.last()?;
    ran.expect_exit(2)?;
    check(ran.stderr.contains("sauna") && ran.stdout.is_empty(), || format!("the path is not named: {}", ran.summary()))
}

fn nothing_changes(w: &mut World, c: &[String]) -> StepResult {
    let d = w.workdir()?.join(&c[0]);
    let before = w.snaps.get("before").cloned().unwrap_or_default();
    check(snapshot(&d) == before, || format!("{} changed", c[0]))
}

fn sets_the_prefix(w: &mut World, c: &[String]) -> StepResult {
    w.last()?.expect_exit(0)?;
    let dir = w.workdir()?.join("sauna");
    let config = read_edn(&dir, "dhf/harness/config.edn")?;
    let got = config.get("id-prefix").and_then(|v| v.as_str()).unwrap_or_default().to_string();
    check(got == c[0], || format!("the id prefix is {got:?}, not {:?}", c[0]))
}

fn refused_writing_nothing(w: &mut World, says: &[&str]) -> StepResult {
    let ran = w.last()?.clone();
    ran.expect_exit(2)?;
    for s in says {
        check(ran.stderr.contains(s), || format!("the refusal does not say {s:?}: {}", ran.summary()))?;
    }
    let sauna = ran.cwd.join("sauna");
    check(!sauna.exists(), || "a refused init wrote sauna".into())
}

fn bad_prefix(w: &mut World, _: &[String]) -> StepResult {
    refused_writing_nothing(w, &["9lives", "starts with a letter", "letters, digits and single hyphens"])
}

fn prefix_un(w: &mut World, _: &[String]) -> StepResult {
    refused_writing_nothing(w, &["UN", "need tags"])
}

fn it_passes(w: &mut World, _: &[String]) -> StepResult {
    w.last()?.expect_exit(0)
}
