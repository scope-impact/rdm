# Committed git hooks — the local design gate

This directory is RDM's own, version-controlled `core.hooksPath`. It carries the
**design-gate hooks** (copies of `rdm/specification/hook_files/pre-commit` and
`pre-merge-commit`): a commit, or a merge, that stages implementation work is
blocked until the design documents and design review are complete and approved (committed) — see `dhf/AGENT_WORKFLOW.md`.

Activate it (agent sessions do this automatically via `scripts/agent-bootstrap.sh`):

```bash
git config core.hooksPath .githooks
```

Deliberately **not** included: the `commit-msg` / `prepare-commit-msg` hooks that
`rdm hooks` also ships. They enforce a GitHub-issue-reference convention for
downstream projects that RDM's own history does not use. If you re-run
`uv run rdm hooks .githooks` after changing `rdm/specification/hook_files/`, remove
those two again before committing.
