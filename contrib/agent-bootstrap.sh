#!/usr/bin/env bash
#
# Agent (and human) session bootstrap for the RDM repository.
#
# Run this once at the start of any working session — Claude Code runs it
# automatically via the SessionStart hook in `.claude/settings.json`; other
# agents/humans run it by hand:
#
#     bash contrib/agent-bootstrap.sh
#
# It is idempotent. It installs the dependencies, points git at the committed
# design-gate hook (`.githooks/`), and reports the design-gate state on HEAD.
# A red gate at session start is information (e.g. you are resuming a change
# mid-flight), not an error — so this script always exits 0.
#
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"

status_deps="skipped (uv not found)"
if command -v uv >/dev/null 2>&1; then
    if uv sync --all-extras >/dev/null 2>&1; then
        status_deps="installed"
    else
        status_deps="FAILED (run 'uv sync --all-extras' by hand)"
    fi
fi

git config core.hooksPath .githooks
status_hooks="core.hooksPath=$(git config core.hooksPath)"

status_gate="unknown (rdm unavailable)"
if command -v uv >/dev/null 2>&1; then
    if uv run rdm story design-gate --dhf dhf >/dev/null 2>&1; then
        status_gate="GREEN"
    else
        status_gate="RED — commit the design docs before implementation (see .claude/skills/traceable-change/SKILL.md)"
    fi
fi

echo "rdm agent-bootstrap: deps: ${status_deps}; hooks: ${status_hooks}; design gate: ${status_gate}"
echo "rdm agent-bootstrap: before changing ANY code, read .claude/skills/traceable-change/SKILL.md"
exit 0
