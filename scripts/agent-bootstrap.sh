#!/usr/bin/env bash
#
# Session bootstrap: sync RDM's dependencies and point the session at the
# change procedure.
#
# Run by the Claude Code SessionStart hook (.claude/settings.json); safe and
# useful to run by hand too. Everything is best-effort and non-fatal — a
# network-less or partially set-up environment must never break session start.
set -u

cd "$(dirname "$0")/.." || exit 0

# 1. Dependencies (idempotent; quick when the environment is already synced).
if command -v uv >/dev/null 2>&1; then
    uv sync --all-extras >/dev/null 2>&1 || true
fi

# 2. Orient the session: the canonical procedure.
echo "Canonical change procedure: dhf/AGENT_WORKFLOW.md (design input -> tagged test"
echo "-> gates -> generated traceability matrix -> reviewed pull request)."

exit 0
