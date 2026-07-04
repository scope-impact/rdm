#!/usr/bin/env bash
#
# Session bootstrap: make this repo's design controls active by default.
# Run by the Claude Code SessionStart hook (.claude/settings.json); safe to run
# by hand. Best-effort and non-fatal — a partially set-up environment must
# never break session start.
set -u

cd "$(dirname "$0")/.." || exit 0

# 1. The local design gate: point git at the committed hooks.
if [ -d .githooks ]; then
    git config core.hooksPath .githooks 2>/dev/null || true
fi

# 2. Orient the session: the canonical procedure, and the current gate state.
echo "Design controls are active (pre-commit design gate via .githooks)."
echo "Canonical change procedure: dhf/AGENT_WORKFLOW.md (design input -> tagged test"
echo "-> independent faithfulness verdict -> gates -> generated traceability matrix)."
if command -v rdm >/dev/null 2>&1; then
    rdm story design-gate --dhf dhf 2>/dev/null | tail -1 || true
else
    echo "note: 'rdm' not on PATH -- install with: pip install 'rdm[story-audit]'"
fi

exit 0
