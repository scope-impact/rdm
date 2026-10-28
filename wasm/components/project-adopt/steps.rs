//! The step definitions of project/adopt (dhf/spec/01-start-a-project/
//! adopt.feature). Context keys: paths["repo"] (the repository adopted, where
//! "it" runs), snaps["before"] (the repository before the When).

use crate::world::*;
use rdm_kernel::edn::{self, Edn};
use std::collections::BTreeMap;
use std::path::Path;

const SRC: &str = "project-adopt/steps.rs";

pub fn register(s: &mut Steps) {
    s.def(SRC, r"a git repository holding source code and a README", repo_with_code_and_readme);
    s.def(SRC, r"a git repository holding source code and tests", repo_with_code_and_tests);
    s.def(SRC, r"a git repository that already holds (\S+)", repo_holding);
    s.def(SRC, r"a git repository where (\S+) is a symbolic link", repo_with_link);
    s.def(SRC, r"a git repository with its own \.gitignore", repo_with_ignore);
    s.def(SRC, r"a repository that has been adopted", repo_adopted);
    s.def(SRC, r"a directory that is not a git repository", not_a_repo);
    s.def(SRC, r#""rdm-spec ([^"]*)" is run in it(?: again)?"#, run_in_it);
    s.def(SRC, r#"it holds the skeleton, as "rdm-spec init" writes it"#, holds_the_skeleton);
    s.def(SRC, r"its source code and README are unchanged", unchanged);
    s.def(SRC, r"(?:the \.gitignore|\S+) is unchanged", unchanged);
    s.def(SRC, r"the link and its target are unchanged", unchanged);
    s.def(SRC, r"adopt lists every file it wrote", lists_what_it_wrote);
    s.def(SRC, r"adopt names (?:it|the link) as kept", names_kept);
    s.def(SRC, r"adopt names the lines it would have added, those init writes to \.gitignore", names_ignore_lines);
    s.def(SRC, r"no file changes", no_file_changes);
    s.def(SRC, r"adopt names every destination as kept", every_destination_kept);
    s.def(SRC, r"it exits 2, says the hook needs a git repository, and writes nothing", refuses_outside_git);
    s.def(SRC, r"the model, the needs and the ledger are empty", spec_is_empty);
    s.def(SRC, r"no feature file is written", no_feature_file);
}

fn write(dir: &Path, rel: &str, text: &str) -> StepResult {
    let p = dir.join(rel);
    std::fs::create_dir_all(p.parent().unwrap()).or_broken("making a folder")?;
    std::fs::write(p, text).or_broken("writing a fixture file")
}

/// A git repository with the files given, fenced from any around it.
fn repo(w: &mut World, files: &[(&str, &str)]) -> Result<std::path::PathBuf, Wrong> {
    let dir = w.fresh_dir("repo")?;
    w.git(&dir, &["init", "-q"])?;
    for (rel, text) in files {
        write(&dir, rel, text)?;
    }
    w.paths.insert("repo".into(), dir.clone());
    w.cwd = Some(dir.clone());
    Ok(dir)
}

fn remember(w: &mut World) -> StepResult {
    let dir = w.path("repo")?;
    w.snaps.insert("before".into(), snapshot(&dir));
    Ok(())
}

fn before(w: &World) -> BTreeMap<String, Vec<u8>> {
    w.snaps.get("before").cloned().unwrap_or_default()
}

fn repo_with_code_and_readme(w: &mut World, _: &[String]) -> StepResult {
    repo(w, &[("src/main.c", "int main(void) { return 0; }\n"), ("README.md", "# The app\n\nIt runs.\n")])?;
    remember(w)
}

fn repo_with_code_and_tests(w: &mut World, _: &[String]) -> StepResult {
    repo(
        w,
        &[
            ("src/lib.rs", "pub fn add(a: i32, b: i32) -> i32 { a + b }\n"),
            ("tests/add_test.rs", "#[test]\nfn adds() { assert_eq!(app::add(1, 2), 3); }\n"),
            ("features/login.feature", "Feature: Login\n  Scenario: A user logs in\n    Given a user\n    Then they are in\n"),
        ],
    )?;
    remember(w)
}

fn repo_holding(w: &mut World, c: &[String]) -> StepResult {
    repo(w, &[(c[0].as_str(), "# Our own glossary\n\n**Term**: ours.\n")])?;
    remember(w)
}

fn repo_with_link(w: &mut World, c: &[String]) -> StepResult {
    let dir = repo(w, &[("shared/config.edn", "{:ours true}\n")])?;
    let link = dir.join(&c[0]);
    std::fs::create_dir_all(link.parent().unwrap()).or_broken("making a folder")?;
    let depth = c[0].matches('/').count();
    let target = format!("{}shared/config.edn", "../".repeat(depth));
    std::os::unix::fs::symlink(&target, &link).or_broken("making the link")?;
    remember(w)
}

fn repo_with_ignore(w: &mut World, _: &[String]) -> StepResult {
    repo(w, &[(".gitignore", "node_modules/\n*.log\n")])?;
    remember(w)
}

fn repo_adopted(w: &mut World, _: &[String]) -> StepResult {
    let dir = repo(w, &[("README.md", "# The app\n")])?;
    let ran = w.rdm_spec("adopt", &dir)?;
    if ran.exit != Some(0) {
        return broken(format!("adopt did not adopt the repository: {}", ran.summary()));
    }
    remember(w)
}

fn not_a_repo(w: &mut World, _: &[String]) -> StepResult {
    let dir = w.fresh_dir("plain")?;
    write(&dir, "notes.txt", "not a repository\n")?;
    w.paths.insert("repo".into(), dir.clone());
    w.cwd = Some(dir);
    remember(w)
}

fn run_in_it(w: &mut World, c: &[String]) -> StepResult {
    let dir = w.path("repo")?;
    w.rdm_spec(&c[0], &dir)?;
    Ok(())
}

/// What "rdm-spec init" writes, made in a folder of its own: path to bytes,
/// .git left out.
fn init_reference(w: &mut World) -> Result<BTreeMap<String, Vec<u8>>, Wrong> {
    let room = w.fresh_dir("reference")?;
    let ran = w.exec(&["rdm-spec".into(), "init".into(), "ref".into()], &room, None)?;
    if ran.exit != Some(0) {
        return broken(format!("init did not write a reference skeleton: {}", ran.summary()));
    }
    w.paths.insert("reference".into(), room.join("ref"));
    let snap = snapshot(&room.join("ref"));
    Ok(snap.into_iter().filter(|(k, _)| !k.ends_with('/') && !k.starts_with(".git/")).collect())
}

fn adopted(w: &World) -> Result<Edn, Wrong> {
    let ran = w.last()?;
    ran.expect_exit(0)?;
    let v = edn::read(&ran.stdout).map_err(|e| Wrong::Fail(format!("adopt's output is not EDN ({e}): {}", ran.summary())))?;
    v.get("adopt").cloned().ok_or_else(|| Wrong::Fail(format!("no :adopt in {}", ran.stdout)))
}

fn strs(e: Option<&Edn>) -> Vec<String> {
    let mut v: Vec<String> = e.map(|v| v.items().iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
    v.sort();
    v
}

fn holds_the_skeleton(w: &mut World, _: &[String]) -> StepResult {
    w.last()?.expect_exit(0)?;
    let dir = w.path("repo")?;
    let reference = init_reference(w)?;
    let now = snapshot(&dir);
    for (rel, bytes) in &reference {
        match now.get(rel) {
            None => return fail(format!("{rel}, which init writes, is not there")),
            Some(b) if b != bytes => return fail(format!("{rel} differs from what init writes")),
            _ => {}
        }
    }
    let hp = std::process::Command::new("git").args(["config", "--get", "core.hooksPath"]).current_dir(&dir).output().or_broken("git config")?;
    check(String::from_utf8_lossy(&hp.stdout).trim() == ".githooks", || "core.hooksPath is not .githooks".into())
}

/// Every file the Given put there is as it was, a link as the same link.
fn unchanged(w: &mut World, _: &[String]) -> StepResult {
    w.last()?.expect_exit(0)?;
    let dir = w.path("repo")?;
    let now = snapshot(&dir);
    for (k, v) in before(w).iter().filter(|(k, _)| !k.starts_with(".git/")) {
        check(now.get(k) == Some(v), || format!("{k} changed"))?;
    }
    Ok(())
}

fn lists_what_it_wrote(w: &mut World, _: &[String]) -> StepResult {
    let a = adopted(w)?;
    let dir = w.path("repo")?;
    let old = before(w);
    let mut new_files: Vec<String> = files(&dir).into_iter().filter(|f| !old.contains_key(f)).collect();
    new_files.sort();
    let wrote = strs(a.get("wrote"));
    check(wrote == new_files, || format!("adopt lists {wrote:?}; it wrote {new_files:?}"))?;
    check(!wrote.is_empty(), || "adopt lists nothing".into())
}

fn names_kept(w: &mut World, _: &[String]) -> StepResult {
    let a = adopted(w)?;
    let ran = w.last()?.clone();
    let given = w.scenario.steps[0].text.clone();
    let path = given.split_whitespace().find(|t| t.contains('/')).unwrap_or_default().to_string();
    check(strs(a.get("kept")).contains(&path), || format!("adopt does not name {path} as kept: {}", ran.stdout))?;
    check(!strs(a.get("wrote")).contains(&path), || format!("adopt says it wrote {path}"))?;
    check(ran.stderr.lines().any(|l| l.starts_with(&format!("{path}: warning:"))), || format!("no warning names {path} as kept: {}", ran.stderr))
}

fn names_ignore_lines(w: &mut World, _: &[String]) -> StepResult {
    let a = adopted(w)?;
    init_reference(w)?;
    let ignore = std::fs::read_to_string(w.path("reference")?.join(".gitignore")).or_broken("reading init's .gitignore")?;
    let mut want: Vec<String> = ignore.lines().filter(|l| !l.trim().is_empty() && !l.starts_with('#')).map(String::from).collect();
    want.sort();
    let got = strs(a.get("gitignore-lines"));
    check(got == want, || format!("adopt names {got:?}, init writes {want:?}"))?;
    check(strs(a.get("kept")).contains(&".gitignore".to_string()), || "adopt does not name .gitignore as kept".into())
}

fn no_file_changes(w: &mut World, _: &[String]) -> StepResult {
    w.last()?.expect_exit(0)?;
    let dir = w.path("repo")?;
    check(snapshot(&dir) == before(w), || "a file changed".into())
}

fn every_destination_kept(w: &mut World, _: &[String]) -> StepResult {
    let a = adopted(w)?;
    let reference: Vec<String> = init_reference(w)?.into_keys().collect();
    let kept = strs(a.get("kept"));
    check(kept == reference, || format!("adopt names {kept:?} as kept, not every destination {reference:?}"))?;
    check(strs(a.get("wrote")).is_empty(), || "adopt wrote something the second time".into())
}

fn refuses_outside_git(w: &mut World, _: &[String]) -> StepResult {
    let ran = w.last()?.clone();
    ran.expect_exit(2)?;
    check(ran.stderr.contains("git repository") && ran.stderr.contains("hook"), || format!("the refusal does not say the hook needs a git repository: {}", ran.summary()))?;
    check(ran.stdout.is_empty(), || "a refusal printed a result".into())?;
    let dir = w.path("repo")?;
    check(snapshot(&dir) == before(w), || "adopt wrote into a directory it refused".into())
}

fn read_edn(dir: &Path, rel: &str) -> Result<Edn, Wrong> {
    let t = std::fs::read_to_string(dir.join(rel)).map_err(|e| Wrong::Fail(format!("{rel}: {e}")))?;
    edn::read(&t).map_err(|e| Wrong::Fail(format!("{rel} is not EDN: {e}")))
}

fn spec_is_empty(w: &mut World, _: &[String]) -> StepResult {
    w.last()?.expect_exit(0)?;
    let dir = w.path("repo")?;
    let model = read_edn(&dir, "dhf/spec/model.edn")?;
    check(matches!(&model, Edn::Map(m) if m.len() == 1) && model.get("system").is_some(), || format!("the model is not empty: {}", edn::print(&model)))?;
    check(read_edn(&dir, "dhf/spec/needs.edn")? == Edn::Map(vec![]), || "the needs are not empty".into())?;
    check(read_edn(&dir, "dhf/spec/ids.edn")?.get("issued") == Some(&Edn::Int(0)), || "the ledger is not at 0".into())
}

fn no_feature_file(w: &mut World, _: &[String]) -> StepResult {
    let dir = w.path("repo")?;
    let written: Vec<String> = files(&dir).into_iter().filter(|f| f.ends_with(".feature") && !f.starts_with("features/")).collect();
    check(written.is_empty(), || format!("adopt wrote feature files: {written:?}"))
}
