# Committed git hooks — the local design gate

`pre-commit` here (laid down by `rdm adopt`, from RDM's `hook_files/`) blocks a
commit that stages implementation work until the design documents and design
review are complete and approved (committed) — see `dhf/AGENT_WORKFLOW.md`.

Activate it (agent sessions do this automatically via
`scripts/agent-bootstrap.sh`):

```bash
git config core.hooksPath .githooks
```

Bypass for emergencies only: `RDM_SKIP_DESIGN_GATE=1 git commit …` (loud, local;
CI still runs the full pipeline).
