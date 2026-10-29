//! The harness: one binary, held still (docs/wow/invariants.md, INV-3).
//!
//! Exit codes, as every task will report them: 0 passed, 1 refused (a
//! finding about the project), 2 cannot run (a defect or a missing input of
//! the harness itself).

use std::path::Path;
use std::process::ExitCode;

/// Where a project keeps its storm (INV-1). The first thing `check` asks for.
const STORM: &str = "spec/storm.edn";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("check") => check(Path::new(".")),
        Some(other) => {
            eprintln!("cannot-run: unknown task {other}");
            ExitCode::from(2)
        }
        None => {
            eprintln!("usage: harness check");
            ExitCode::from(2)
        }
    }
}

/// `check`: the gate on a project. For now it holds one opinion, the first
/// one (INV-1): no storm, no cut.
fn check(project: &Path) -> ExitCode {
    match check_storm(project) {
        Ok(()) => {
            println!("{{:check :passed}}");
            ExitCode::SUCCESS
        }
        Err(finding) => {
            println!("{{:check :refused :finding \"{finding}\"}}");
            ExitCode::from(1)
        }
    }
}

fn check_storm(project: &Path) -> Result<(), String> {
    if project.join(STORM).is_file() {
        Ok(())
    } else {
        Err(format!("no storm: {STORM} is missing"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_empty_project_is_refused_for_having_no_storm() {
        let dir = std::env::temp_dir().join(format!("harness-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let result = check_storm(&dir);
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(
            result,
            Err("no storm: spec/storm.edn is missing".to_string())
        );
    }
}
