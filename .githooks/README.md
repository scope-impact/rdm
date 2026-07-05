# Committed git hooks — the local design gate

This directory is RDM's own, version-controlled `core.hooksPath`. It carries the
**design-gate pre-commit hook** (a copy of `rdm/hook_files/pre-commit`): a commit
that stages implementation work is blocked until the design documents and design
review are complete and approved (committed) — see `dhf/AGENT_WORKFLOW.md`.

Activate it (agent sessions do this automatically via `scripts/agent-bootstrap.sh`):

```bash
git config core.hooksPath .githooks
```

Deliberately **not** included: the `commit-msg` / `prepare-commit-msg` hooks that
`rdm hooks` also ships. They enforce a GitHub-issue-reference convention for
downstream projects that RDM's own history does not use. If you re-run
`uv run rdm hooks .githooks` after changing `rdm/hook_files/pre-commit`, remove
those two again before committing.
