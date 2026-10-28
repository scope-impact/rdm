//! The pre-commit hook and what installing it does (spec-0025, spec-0086):
//! a hook never written over a file, core.hooksPath set only where unset.

/// The hook's path in a repository.
pub const PATH: &str = ".githooks/pre-commit";

/// The hook: runs "rdm-spec check" and refuses the commit when it exits
/// other than 0.
pub const HOOK: &str = "#!/bin/sh
# The pre-commit hook rdm-spec init and adopt install: every commit is
# checked. The commit is refused when the check exits other than 0.
rdm-spec check
status=$?
if [ \"$status\" -ne 0 ]; then
  echo \"pre-commit: rdm-spec check exited $status: the commit is refused\" >&2
  exit 1
fi
";

/// What installing does, given what is there: write the hook, set
/// core.hooksPath.
#[derive(Debug, PartialEq)]
pub struct Plan {
    pub write_hook: bool,
    pub set_hooks_path: bool,
}

pub fn plan(hook_exists: bool, hooks_path: Option<&str>) -> Plan {
    Plan { write_hook: !hook_exists, set_hooks_path: hooks_path.is_none() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_hook_runs_the_check_and_refuses_on_any_other_exit() {
        assert!(HOOK.starts_with("#!/bin/sh\n"));
        assert!(HOOK.contains("rdm-spec check"));
        assert!(HOOK.contains("exit 1"));
    }

    #[test]
    fn a_hook_is_never_written_over_a_file() {
        assert_eq!(plan(true, None), Plan { write_hook: false, set_hooks_path: true });
        assert_eq!(plan(false, None), Plan { write_hook: true, set_hooks_path: true });
    }

    #[test]
    fn core_hooks_path_is_set_only_where_unset() {
        assert_eq!(plan(false, Some(".githooks")), Plan { write_hook: true, set_hooks_path: false });
        assert_eq!(plan(true, Some("other")), Plan { write_hook: false, set_hooks_path: false });
    }
}
