#!/usr/bin/env python3
"""Claude Code PreToolUse guard for RDM's design controls.

Wired up in `.claude/settings.json`. Reads the hook payload (JSON on stdin) and
exits 2 — which blocks the tool call and feeds stderr back to the agent — when
the call would bypass or falsify the design-control record:

- a `git commit` that skips the design gate (`RDM_SKIP_DESIGN_GATE`,
  `--no-verify`/`-n`) or a `git config` that unsets/re-points `core.hooksPath`;
- a hand edit of `dhf/faithfulness/*` (verdicts are recorded only via
  `rdm story verdict`, by a reviewer independent of the test author);
- a hand edit of `backlog/tasks/*` or `backlog/drafts/*` (CLI-only).

Deliberately NOT blocked: editing the traceability-matrix *template* (it is a
legitimate Jinja source) and editing tagged tests (the faithfulness hash-pin
already turns that into a STALE verdict CI catches).

Anything unexpected (no stdin, bad JSON) exits 0 — the guard must never break
an unrelated tool call.
"""

import json
import re
import shlex
import sys

SOP = ".claude/skills/traceable-change/SKILL.md"


def block(message: str) -> None:
    print(message, file=sys.stderr)
    sys.exit(2)


def sanitized(command: str) -> str:
    """Strip heredoc bodies and quoted strings so prose (e.g. a commit message
    that *mentions* --no-verify) cannot false-trigger the flag patterns."""
    s = re.sub(r"<<-?\s*['\"]?(\w+)['\"]?.*?^\1\s*$", " ", command, flags=re.S | re.M)
    s = re.sub(r"'[^']*'", "''", s)
    s = re.sub(r'"[^"]*"', '""', s)
    return s


def check_bash(raw_command: str) -> None:
    command = sanitized(raw_command)
    has_commit = re.search(r"\bgit\b[^|;&]*\bcommit\b", command)

    if has_commit:
        if re.search(r"\bRDM_SKIP_DESIGN_GATE\s*=", command) or re.search(
            r"\bexport\s+RDM_SKIP_DESIGN_GATE\b", command
        ):
            block(
                "Blocked: RDM_SKIP_DESIGN_GATE bypasses the design gate. The gate is not "
                f"optional in this repo — commit the design docs first (see {SOP}). "
                "If you believe a bypass is genuinely needed, ask the human."
            )
        if re.search(r"(^|\s)--no-verify\b", command) or re.search(
            r"\bcommit\b[^|;&]*(^|\s)-n\b", command
        ):
            block(
                "Blocked: `git commit --no-verify` skips the design-gate pre-commit hook. "
                f"Commit the design docs first (see {SOP}), or ask the human."
            )

    if "core.hooksPath" in command:
        try:
            tokens = shlex.split(raw_command)
        except ValueError:
            tokens = raw_command.split()
        if any(t == "--unset" or t.startswith("--unset-") for t in tokens):
            block(
                "Blocked: unsetting core.hooksPath disables the design-gate hook. "
                f"See {SOP}, or ask the human."
            )
        shell_operators = {"&&", "||", ";", "|", "&"}
        for i, tok in enumerate(tokens):
            if tok == "core.hooksPath" and i + 1 < len(tokens):
                value = tokens[i + 1]
                if value in shell_operators or value.startswith("-"):
                    continue  # a bare read followed by another command, not a set
                if value != ".githooks":
                    block(
                        "Blocked: re-pointing core.hooksPath away from .githooks disables "
                        f"the design-gate hook. See {SOP}, or ask the human."
                    )


def check_file_edit(file_path: str) -> None:
    path = file_path.replace("\\", "/")
    if "dhf/faithfulness/" in path:
        block(
            "Blocked: faithfulness verdicts are never hand-edited. They are recorded via "
            "`rdm story verdict <DI-…> --reviewer …` by a reviewer INDEPENDENT of the "
            f"test author (see .claude/skills/test-faithfulness/SKILL.md and {SOP})."
        )
    if "backlog/tasks/" in path or "backlog/drafts/" in path:
        block(
            "Blocked: backlog task files are managed exclusively through the `backlog` "
            "CLI (`backlog task edit …`). Direct edits break its metadata sync."
        )


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        sys.exit(0)

    tool_name = payload.get("tool_name", "")
    tool_input = payload.get("tool_input") or {}

    if tool_name == "Bash":
        check_bash(tool_input.get("command") or "")
    elif tool_name in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        check_file_edit(tool_input.get("file_path") or tool_input.get("notebook_path") or "")

    sys.exit(0)


if __name__ == "__main__":
    main()
