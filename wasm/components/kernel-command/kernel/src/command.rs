//! kernel/command's own rules, whatever task it runs (dhf/spec/README.md,
//! "How every task reports"; the glossary's "Project"; tickets 01, 04, 05):
//! the task table read, help, an unknown task, the project's root, the
//! results directory, and how a task's outcome is reported.

use crate::edn::{self, Edn};
use crate::table::{self, TABLE};
use alloc::format;
use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;

/// What the command does with its arguments before any task runs.
#[derive(Debug, PartialEq)]
pub enum Plan {
    /// help, answered from the table.
    Help,
    /// A refusal before anything is read: exit 2 with this why.
    Refuse(String),
    /// A task of the table, run in the project's root when `root` holds.
    Task { task: String, args: Vec<String>, root: bool, results: bool, cut: bool },
}

/// Every task's name, as an unknown task's refusal lists them.
fn task_list() -> String {
    let names: Vec<&str> = TABLE.iter().map(|r| r.task).collect();
    names.join(", ")
}

/// Tasks that read a cut, to which the command hands the project's folder
/// within its repository (spec-0368).
const READS_A_CUT: &[&str] = &["fence", "verify", "checklist", "scopes"];

/// Tasks that run anywhere: init makes a project, adopt one where there is
/// none yet; help is answered first (ED-06).
const ANYWHERE: &[&str] = &["init", "adopt"];

pub fn plan(args: &[String]) -> Plan {
    let Some(task) = args.first() else {
        return Plan::Refuse(format!("no task given; the tasks are: {}", task_list()));
    };
    let Some(row) = table::row(task) else {
        return Plan::Refuse(format!("unknown task {task}; the tasks are: {}", task_list()));
    };
    if row.task == "help" {
        return match args.get(1) {
            Some(extra) => Plan::Refuse(format!("help takes no argument: {extra}")),
            None => Plan::Help,
        };
    }
    Plan::Task {
        task: task.clone(),
        args: args[1..].to_vec(),
        root: !ANYWHERE.contains(&row.task),
        results: row.usage.contains("[--results DIR]"),
        cut: READS_A_CUT.contains(&row.task),
    }
}

/// help's result: {:help [{:task T :usage U :does D} ...]}, every row.
pub fn help() -> String {
    let rows = TABLE
        .iter()
        .map(|r| Edn::map(vec![("task", Edn::str(r.task)), ("usage", Edn::str(r.usage)), ("does", Edn::str(r.does))]))
        .collect();
    edn::print(&Edn::map(vec![("help", Edn::Vector(rows))]))
}

/// The results directory of a task that takes [--results DIR]: --results,
/// else RESULTS_DIR, else the configured one, else dhf/allure-results
/// (spec-0219, spec-0193). Err when --results has no value.
pub fn results_dir(args: &[String], env: Option<&str>, configured: Option<&str>) -> Result<String, String> {
    if let Some(i) = args.iter().position(|a| a == "--results") {
        return match args.get(i + 1) {
            Some(dir) if !dir.is_empty() && !dir.starts_with("--") => Ok(dir.clone()),
            _ => Err("--results needs a directory".into()),
        };
    }
    if let Some(dir) = env.filter(|d| !d.is_empty()) {
        return Ok(dir.into());
    }
    Ok(configured.unwrap_or(DEFAULT_RESULTS).into())
}

/// The results directory the command hands a task that takes
/// [--results DIR] (ED-16, as the spec owner narrowed it): status, release,
/// publish and audit read results, so a directory that does not exist is
/// refused (exit 2, naming it); trace is refused so only when --results or
/// RESULTS_DIR names it (spec-0107), and is handed none when the configured
/// one does not exist (spec-0108, spec-0047); run writes results, so it is
/// handed its directory, which it creates (spec-0324).
pub fn results_for(
    task: &str,
    args: &[String],
    env: Option<&str>,
    configured: Option<&str>,
    is_dir: &dyn Fn(&str) -> bool,
) -> Result<Option<String>, String> {
    let dir = results_dir(args, env, configured)?;
    if task == "run" || is_dir(&dir) {
        return Ok(Some(dir));
    }
    let named = args.iter().any(|a| a == "--results") || env.is_some_and(|e| !e.is_empty());
    if task == "trace" && !named {
        return Ok(None);
    }
    Err(format!("the results directory {dir} does not exist"))
}

/// The results directory when the configuration names none.
pub const DEFAULT_RESULTS: &str = "dhf/allure-results";

/// init's arguments: PATH [--id-prefix P] (ticket 02).
pub fn init_args(args: &[String]) -> Result<(String, Option<String>), String> {
    let usage = "init takes PATH [--id-prefix P]";
    let mut path: Option<String> = None;
    let mut prefix: Option<String> = None;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        if a == "--id-prefix" {
            match it.next() {
                Some(p) if !p.starts_with("--") => prefix = Some(p.clone()),
                _ => return Err(format!("--id-prefix needs a prefix; {usage}")),
            }
        } else if a.starts_with("--") {
            return Err(format!("unknown argument {a}; {usage}"));
        } else if path.is_some() {
            return Err(format!("one path only, and {a} is a second; {usage}"));
        } else {
            path = Some(a.clone());
        }
    }
    match path {
        Some(p) => Ok((p, prefix)),
        None => Err(format!("no path given; {usage}")),
    }
}

/// The next steps adopt names (ED-07; ticket 03).
pub const ADOPT_NEXT: &[&str] = &[
    "add the lines adopt names to .gitignore, if it kept yours",
    "write the first feature file under dhf/spec/, and the needs its scenarios trace to in dhf/spec/needs.edn",
    "rdm-spec stamp",
    "rdm-spec check",
    "set the :tests check's :command in dhf/harness/config.edn",
];

/// The project's folder within its repository, from what
/// "git rev-parse --show-prefix" printed in the project's root: "." at the
/// repository's root (spec-0368).
pub fn folder(show_prefix: &str) -> String {
    let p = show_prefix.trim().trim_end_matches('/');
    if p.is_empty() { ".".into() } else { p.into() }
}

/// The why of a task no built slice names (ED-02).
pub fn not_built(task: &str) -> String {
    alloc::format!("{task} is not built")
}

/// The error stream of an outcome: each line, then a newline.
pub fn error_stream(errors: &[String]) -> String {
    let mut out = String::new();
    for e in errors {
        // One line each: a newline inside a message would split it.
        out.push_str(&e.replace('\n', " "));
        out.push('\n');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::string::ToString;

    fn args(s: &str) -> Vec<String> {
        s.split_whitespace().map(|x| x.to_string()).collect()
    }

    #[test]
    fn help_is_answered_anywhere_and_lists_every_row() {
        assert_eq!(plan(&args("help")), Plan::Help);
        let v = edn::read(&help()).unwrap();
        let rows = v.get("help").unwrap().items();
        assert_eq!(rows.len(), crate::table::TABLE.len());
        assert_eq!(rows[0].get("task"), Some(&Edn::str("help")));
        assert_eq!(rows[1].get("usage"), Some(&Edn::str("init PATH [--id-prefix P]")));
        assert!(help().starts_with("{:help [{:task \"help\""));
    }

    #[test]
    fn an_unknown_task_is_refused_naming_it_and_listing_every_task() {
        let Plan::Refuse(why) = plan(&args("frobnicate x")) else { panic!() };
        assert!(why.contains("frobnicate"));
        for r in crate::table::TABLE {
            assert!(why.contains(r.task), "{} missing", r.task);
        }
        assert!(matches!(plan(&[]), Plan::Refuse(_)));
    }

    #[test]
    fn init_and_adopt_run_anywhere_every_other_task_in_a_root() {
        assert!(matches!(plan(&args("init sauna")), Plan::Task { root: false, .. }));
        assert!(matches!(plan(&args("adopt")), Plan::Task { root: false, .. }));
        assert!(matches!(plan(&args("stamp")), Plan::Task { root: true, results: false, .. }));
        assert!(matches!(plan(&args("status")), Plan::Task { root: true, results: true, .. }));
        assert!(matches!(plan(&args("fence ROOM spec-3")), Plan::Task { cut: true, .. }));
        assert!(matches!(plan(&args("checklist 03")), Plan::Task { cut: true, .. }));
        assert!(matches!(plan(&args("check")), Plan::Task { cut: false, results: false, .. }));
    }

    #[test]
    fn help_takes_no_argument() {
        assert!(matches!(plan(&args("help me")), Plan::Refuse(_)));
    }

    #[test]
    fn the_results_directory_is_flag_then_environment_then_configured() {
        assert_eq!(results_dir(&args("status"), None, Some("dhf/allure-results")).unwrap(), "dhf/allure-results");
        assert_eq!(results_dir(&args("status"), Some("R1"), Some("dhf/allure-results")).unwrap(), "R1");
        assert_eq!(results_dir(&args("status --results other"), Some("R1"), Some("x")).unwrap(), "other");
        assert_eq!(results_dir(&args("status"), Some(""), None).unwrap(), "dhf/allure-results");
        assert!(results_dir(&args("status --results"), None, None).is_err());
    }

    #[test]
    fn init_takes_one_path_and_an_optional_prefix() {
        assert_eq!(init_args(&args("sauna")).unwrap(), ("sauna".to_string(), None));
        assert_eq!(init_args(&args("sauna --id-prefix p")).unwrap(), ("sauna".to_string(), Some("p".to_string())));
        assert_eq!(init_args(&args("--id-prefix p /a/sauna")).unwrap(), ("/a/sauna".to_string(), Some("p".to_string())));
        assert!(init_args(&args("")).is_err());
        assert!(init_args(&args("a b")).is_err());
        assert!(init_args(&args("sauna --id-prefix")).is_err());
        assert!(init_args(&args("sauna --frob")).is_err());
    }

    fn none_exist(_: &str) -> bool {
        false
    }
    fn all_exist(_: &str) -> bool {
        true
    }

    #[test]
    fn tasks_that_read_results_refuse_a_directory_that_does_not_exist() {
        for task in ["status", "release", "publish", "audit"] {
            let why = results_for(task, &args(task), None, Some("dhf/allure-results"), &none_exist).unwrap_err();
            assert!(why.contains("dhf/allure-results"), "{task}: {why}");
            let flag = alloc::format!("{task} --results other");
            assert!(results_for(task, &args(&flag), None, None, &none_exist).unwrap_err().contains("other"));
            assert_eq!(results_for(task, &args(task), Some("R1"), None, &all_exist).unwrap(), Some("R1".to_string()));
        }
    }

    #[test]
    fn trace_refuses_a_missing_directory_only_when_flag_or_environment_names_it() {
        assert!(results_for("trace", &args("trace UN-001 --results other"), None, None, &none_exist).unwrap_err().contains("other"));
        assert!(results_for("trace", &args("trace UN-001"), Some("R1"), None, &none_exist).unwrap_err().contains("R1"));
        assert_eq!(results_for("trace", &args("trace UN-001"), None, Some("dhf/allure-results"), &none_exist).unwrap(), None);
        assert_eq!(results_for("trace", &args("trace UN-001"), Some(""), None, &none_exist).unwrap(), None);
        assert_eq!(
            results_for("trace", &args("trace UN-001"), None, Some("dhf/allure-results"), &all_exist).unwrap(),
            Some("dhf/allure-results".to_string())
        );
    }

    #[test]
    fn run_is_handed_its_directory_whether_or_not_it_exists() {
        assert_eq!(results_for("run", &args("run"), None, Some("dhf/allure-results"), &none_exist).unwrap(), Some("dhf/allure-results".to_string()));
        assert_eq!(results_for("run", &args("run --results out"), Some("R1"), None, &none_exist).unwrap(), Some("out".to_string()));
    }

    #[test]
    fn a_results_flag_with_no_directory_is_refused_for_every_task() {
        for task in ["status", "trace", "run"] {
            let line = alloc::format!("{task} --results");
            assert!(results_for(task, &args(&line), None, None, &all_exist).is_err());
        }
    }

    #[test]
    fn the_folder_is_the_projects_path_within_its_repository() {
        assert_eq!(folder("wallet/\n"), "wallet");
        assert_eq!(folder("apps/wallet/\n"), "apps/wallet");
        assert_eq!(folder("\n"), ".");
        assert_eq!(folder(""), ".");
    }

    #[test]
    fn the_error_stream_is_one_line_each() {
        assert_eq!(error_stream(&vec!["a: b".to_string(), "warning: c".to_string()]), "a: b\nwarning: c\n");
        assert_eq!(error_stream(&[]), "");
    }
}
