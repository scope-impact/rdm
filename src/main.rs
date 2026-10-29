//! The harness: one binary, held still (docs/wow/invariants.md, INV-3).
//!
//! Every task prints one TOML document on stdout and exits 0 (passed), 1
//! (refused: a finding about the project) or 2 (cannot run: the harness's
//! own defect or a missing input). TOML has no null: an absent value is an
//! absent key (harness/spec.feature, its header).

use serde::Serialize;
use std::path::Path;
use std::process::ExitCode;

/// Where a project keeps its storm (INV-1). The first thing `check` asks for.
const STORM: &str = "spec/storm.toml";

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

/// A finding: the rule that refused, and what it names. Keys in the order the
/// spec pins them.
#[derive(Serialize, Debug, PartialEq)]
struct Finding {
    rule: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    path: Option<String>,
}

#[derive(Serialize)]
struct Check {
    check: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    stage: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stages: Option<Vec<&'static str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    finding: Option<Finding>,
}

/// `check`: the gate on a project. For now it holds one opinion, the first
/// one (INV-1): no storm, no cut.
fn check(project: &Path) -> ExitCode {
    let (report, exit) = match check_storm(project) {
        Ok(()) => (
            Check {
                check: "passed",
                stage: None,
                stages: Some(vec!["storm"]),
                finding: None,
            },
            ExitCode::SUCCESS,
        ),
        Err(finding) => (
            Check {
                check: "refused",
                stage: Some("storm"),
                stages: None,
                finding: Some(finding),
            },
            ExitCode::from(1),
        ),
    };
    print!("{}", toml::to_string(&report).expect("a report serialises"));
    exit
}

fn check_storm(project: &Path) -> Result<(), Finding> {
    if project.join(STORM).is_file() {
        Ok(())
    } else {
        Err(Finding {
            rule: "storm-exists",
            path: Some(STORM.to_string()),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fresh_dir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("harness-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn an_empty_project_is_refused_for_having_no_storm() {
        let dir = fresh_dir("empty");
        let result = check_storm(&dir);
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(
            result,
            Err(Finding {
                rule: "storm-exists",
                path: Some("spec/storm.toml".to_string()),
            })
        );
    }

    #[test]
    fn the_refusal_is_the_document_the_spec_pins() {
        // harness/spec.feature, "A project with no storm is refused by check"
        let report = Check {
            check: "refused",
            stage: Some("storm"),
            stages: None,
            finding: Some(Finding {
                rule: "storm-exists",
                path: Some("spec/storm.toml".to_string()),
            }),
        };
        assert_eq!(
            toml::to_string(&report).unwrap(),
            "check = \"refused\"\nstage = \"storm\"\n\n[finding]\nrule = \"storm-exists\"\npath = \"spec/storm.toml\"\n"
        );
    }
}
