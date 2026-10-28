//! The step definitions of project/hooks (dhf/spec/01-start-a-project/
//! hooks.feature). A scenario about the hook is the one place a fixture's
//! hook runs (docs/wow/harness.md): its check is the rdm-spec the scenario
//! puts first on PATH (ED-11), one that records it ran and exits 1.
//! Context keys: paths["project"], paths["fakebin"], paths["ran"].

use crate::world::*;

const SRC: &str = "project-hooks/steps.rs";

pub fn register(s: &mut Steps) {
    s.def(SRC, r"the pre-commit hook is installed", hook_installed);
    s.def(SRC, r"a commit is made", commit_made);
    s.def(SRC, r#"the hook runs "rdm-spec check""#, hook_ran_check);
    s.def(SRC, r"the commit is refused when the check exits other than 0", commit_refused);
}

fn fake_check(w: &mut World, exit: i32) -> StepResult {
    let bin = w.path("fakebin")?;
    let ran = w.path("ran")?;
    let script = format!("#!/bin/sh\necho \"$*\" >> '{}'\nexit {exit}\n", ran.display());
    std::fs::write(bin.join("rdm-spec"), script).or_broken("writing the recording rdm-spec")?;
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(bin.join("rdm-spec"), std::fs::Permissions::from_mode(0o755)).or_broken("chmod")
}

fn hook_installed(w: &mut World, _: &[String]) -> StepResult {
    let room = w.fresh_dir("room")?;
    let ran = w.rdm_spec("init sauna", &room)?;
    if ran.exit != Some(0) {
        return broken(format!("init did not install the hook: {}", ran.summary()));
    }
    let project = room.join("sauna");
    let bin = w.fresh_dir("fakebin")?;
    w.paths.insert("project".into(), project.clone());
    w.paths.insert("fakebin".into(), bin.clone());
    w.paths.insert("ran".into(), w.dir.join("check-ran.txt"));
    fake_check(w, 1)?;
    let path = format!("{}:{}", bin.display(), std::env::var("PATH").unwrap_or_default());
    w.env.insert("PATH".into(), Some(path));
    w.git(&project, &["add", "-A"])?;
    w.cwd = Some(project);
    Ok(())
}

fn commit(w: &mut World) -> Result<Ran, Wrong> {
    let project = w.path("project")?;
    let argv: Vec<String> = ["git", "commit", "-q", "-m", "a commit"].iter().map(|s| s.to_string()).collect();
    let ran = w.exec(&argv, &project, None)?;
    w.attachments.push(("git commit".into(), ran.summary()));
    Ok(ran)
}

fn commit_made(w: &mut World, _: &[String]) -> StepResult {
    let ran = commit(w)?;
    w.last = Some(ran);
    Ok(())
}

fn hook_ran_check(w: &mut World, _: &[String]) -> StepResult {
    let ran = std::fs::read_to_string(w.path("ran")?).unwrap_or_default();
    check(ran.lines().any(|l| l.trim() == "check"), || format!("the hook did not run rdm-spec check (it ran: {ran:?})"))
}

fn commit_refused(w: &mut World, _: &[String]) -> StepResult {
    let first = w.last()?.clone();
    check(first.exit != Some(0), || format!("the commit was made though the check exited 1: {}", first.summary()))?;
    let project = w.path("project")?;
    let head = w.exec(&["git".into(), "rev-parse".into(), "--verify".into(), "-q".into(), "HEAD".into()], &project, None)?;
    check(head.exit != Some(0), || "a commit exists after the refusal".into())?;
    // The same commit with a check that passes is made: the check refused it.
    fake_check(w, 0)?;
    let second = commit(w)?;
    check(second.exit == Some(0), || format!("the commit was refused with a passing check: {}", second.summary()))
}
