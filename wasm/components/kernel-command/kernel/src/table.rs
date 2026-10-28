//! The task table (spec-0176; ED-18): every task with its usage and what it
//! does, in its order. The command holds the whole table; each slice's tasks
//! entries are its rows of it.

pub struct Row {
    pub task: &'static str,
    pub usage: &'static str,
    pub does: &'static str,
}

pub const TABLE: &[Row] = &[
    Row { task: "help", usage: "help", does: "list every task with its usage" },
    Row { task: "init", usage: "init PATH [--id-prefix P]", does: "write the skeleton into a path that does not exist" },
    Row { task: "adopt", usage: "adopt", does: "add the skeleton to the current repository, overwriting nothing" },
    Row { task: "stamp", usage: "stamp", does: "give every unstamped scenario its id" },
    Row { task: "check", usage: "check [--sources DIR] [--events]", does: "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths, and the plugins that subscribe to what it reads" },
    Row { task: "views", usage: "views", does: "generate and render every view to dhf/views/" },
    Row { task: "cut", usage: "cut", does: "tag HEAD spec-N when it is origin/HEAD, and push the tag" },
    Row { task: "scopes", usage: "scopes CUT CUT", does: "list the scopes a spec change touched between two cuts" },
    Row { task: "fence", usage: "fence ROOM CUT [--context C,...] [--component C/N,...]", does: "build a clean room from a cut" },
    Row { task: "run", usage: "run [--results DIR]", does: "run the scenarios against a project's step definitions" },
    Row { task: "verify", usage: "verify ROOM CYCLE", does: "run the project's checks on the room's code in a checkout of the cut" },
    Row { task: "checklist", usage: "checklist CYCLE", does: "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json" },
    Row { task: "status", usage: "status [--results DIR]", does: "each scenario's status" },
    Row { task: "trace", usage: "trace ID [--results DIR]", does: "a need or a scenario with everything linked to it" },
    Row { task: "release", usage: "release [--results DIR]", does: "the release check" },
    Row { task: "score", usage: "score CYCLE --verdicts FILE [--verdicts FILE ...]", does: "file the graders' verdicts on a verified cycle into its score.edn" },
    Row { task: "scoreboard", usage: "scoreboard CYCLE CYCLE", does: "compare two cycles" },
    Row { task: "oracle", usage: "oracle [ARGS]", does: "run the oracle the project keeps: its plugin named \"oracle\"" },
    Row { task: "plugin", usage: "plugin add NAME", does: "copy a plugin template rdm-spec carries into dhf/harness/plugins/NAME" },
    Row { task: "publish", usage: "publish [--results DIR]", does: "write the site and build it" },
    Row { task: "audit", usage: "audit OUT [--results DIR]", does: "write the auditor's and the engineer's copy, with their evidence, to OUT" },
    Row { task: "observe", usage: "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE", does: "record a run: its time, its cost and what it touched outside its room" },
];

pub fn row(task: &str) -> Option<&'static Row> {
    TABLE.iter().find(|r| r.task == task)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn holds_every_task_in_its_order() {
        let tasks: Vec<&str> = TABLE.iter().map(|r| r.task).collect();
        assert_eq!(
            tasks,
            [
                "help", "init", "adopt", "stamp", "check", "views", "cut", "scopes", "fence", "run", "verify", "checklist",
                "status", "trace", "release", "score", "scoreboard", "oracle", "plugin", "publish", "audit", "observe"
            ]
        );
    }

    #[test]
    fn gives_each_task_its_usage_and_what_it_does() {
        let help = row("help").unwrap();
        assert_eq!((help.usage, help.does), ("help", "list every task with its usage"));
        assert_eq!(row("init").unwrap().usage, "init PATH [--id-prefix P]");
        assert_eq!(row("status").unwrap().usage, "status [--results DIR]");
        assert!(row("frobnicate").is_none());
    }
}
