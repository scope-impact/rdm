#!/usr/bin/env bash
#
# Session bootstrap: make RDM's design controls active by default.
#
# Run by the Claude Code SessionStart hook (.claude/settings.json); safe and
# useful to run by hand too. Everything is best-effort and non-fatal — a
# network-less or partially set-up environment must never break session start.
set -u

cd "$(dirname "$0")/.." || exit 0

# 1. The local design gate: point git at the committed hooks.
if [ -d .githooks ]; then
    git config core.hooksPath .githooks 2>/dev/null || true
fi

# 2. Dependencies (idempotent; quick when the environment is already synced).
if command -v uv >/dev/null 2>&1; then
    uv sync --all-extras >/dev/null 2>&1 || true
fi

# 3. Orient the session: the canonical procedure, and the current gate state.
echo "RDM design controls are active (pre-commit design gate via .githooks)."
echo "Canonical change procedure: dhf/AGENT_WORKFLOW.md (design input -> tagged test"
echo "-> independent faithfulness verdict -> gates -> generated traceability matrix)."
if command -v uv >/dev/null 2>&1; then
    uv run rdm story design-gate --dhf dhf 2>/dev/null | tail -1 || true
fi

exit 0
