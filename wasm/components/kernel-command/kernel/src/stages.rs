//! A task's stages, as the task table runs them (the glossary's "Gate";
//! ticket 04): in order; a gate with findings that are not warnings stops the
//! task, and the stages after it never run; every other stage reports, so one
//! run names every finding; a stage whose code is not built refuses the task
//! (exit 2), since a stage skipped is a check that reads as a pass.

use alloc::format;
use alloc::string::String;
use alloc::vec::Vec;

/// One error-stream line a stage reported.
#[derive(Clone, Debug, PartialEq)]
pub struct Line {
    pub text: String,
    pub warning: bool,
}

pub struct Stage<'a> {
    pub name: &'a str,
    pub gate: bool,
    /// None when this build does not hold the stage's code.
    pub run: Option<&'a dyn Fn() -> Vec<Line>>,
}

/// What the stages came to: exit 0, 1 or 2, and the lines, findings first,
/// then warnings, then a refusal.
#[derive(Debug, PartialEq)]
pub struct Ran {
    pub exit: u8,
    pub errors: Vec<String>,
}

pub fn run(stages: &[Stage]) -> Ran {
    let mut findings: Vec<String> = Vec::new();
    let mut warnings: Vec<String> = Vec::new();
    let done = |findings: Vec<String>, warnings: Vec<String>, refusal: Option<String>| {
        let exit = if refusal.is_some() { 2 } else if findings.is_empty() { 0 } else { 1 };
        let mut errors = findings;
        errors.extend(warnings);
        errors.extend(refusal);
        Ran { exit, errors }
    };
    for stage in stages {
        let Some(run) = stage.run else {
            return done(findings, warnings, Some(format!("cannot-run: the stage {} is not built", stage.name)));
        };
        let lines = run();
        let found = lines.iter().any(|l| !l.warning);
        for l in lines {
            if l.warning { warnings.push(l.text) } else { findings.push(l.text) }
        }
        if stage.gate && found {
            break;
        }
    }
    done(findings, warnings, None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::string::ToString;
    use alloc::vec;
    use core::cell::Cell;

    fn finding(t: &str) -> Line {
        Line { text: t.to_string(), warning: false }
    }
    fn warning(t: &str) -> Line {
        Line { text: t.to_string(), warning: true }
    }

    #[test]
    fn stages_that_find_nothing_pass() {
        let ok = || vec![];
        let r = run(&[Stage { name: "a", gate: false, run: Some(&ok) }, Stage { name: "b", gate: true, run: Some(&ok) }]);
        assert_eq!(r, Ran { exit: 0, errors: vec![] });
    }

    #[test]
    fn a_stage_that_is_no_gate_reports_and_the_rest_still_run() {
        let a = || vec![finding("f.feature:3: missing-level: no level")];
        let b = || vec![finding("dhf/views/c.mmd: stale-view: older than the model"), warning("warning: w: x")];
        let r = run(&[Stage { name: "spec", gate: false, run: Some(&a) }, Stage { name: "views", gate: false, run: Some(&b) }]);
        assert_eq!(r.exit, 1);
        assert_eq!(
            r.errors,
            vec!["f.feature:3: missing-level: no level", "dhf/views/c.mmd: stale-view: older than the model", "warning: w: x"]
        );
    }

    #[test]
    fn a_gate_with_findings_stops_the_task() {
        let ran_after = Cell::new(false);
        let gate = || vec![finding("x: r: wrong")];
        let after = || {
            ran_after.set(true);
            vec![]
        };
        let r = run(&[Stage { name: "gate", gate: true, run: Some(&gate) }, Stage { name: "after", gate: false, run: Some(&after) }]);
        assert_eq!(r.exit, 1);
        assert!(!ran_after.get());
    }

    #[test]
    fn a_gate_with_only_warnings_does_not_stop_it() {
        let ran_after = Cell::new(false);
        let gate = || vec![warning("warning: r: note")];
        let after = || {
            ran_after.set(true);
            vec![]
        };
        let r = run(&[Stage { name: "gate", gate: true, run: Some(&gate) }, Stage { name: "after", gate: false, run: Some(&after) }]);
        assert_eq!(r.exit, 0);
        assert!(ran_after.get());
        assert_eq!(r.errors, vec!["warning: r: note"]);
    }

    #[test]
    fn a_stage_not_built_refuses_the_task() {
        let ok = || vec![finding("a: r: x")];
        let r = run(&[Stage { name: "spec", gate: false, run: Some(&ok) }, Stage { name: "paths", gate: false, run: None }]);
        assert_eq!(r.exit, 2);
        assert_eq!(r.errors.last().unwrap(), "cannot-run: the stage paths is not built");
    }
}
