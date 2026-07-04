# Quickstart — an existing repository (brownfield)

`rdm adopt` brings a repository that already has code under record-first
design controls. It lays down only the **control surface** and **never
overwrites an existing file** (re-running is a no-op):

```bash
cd your-repo
rdm adopt .
```

What lands:

| Path | Role |
|---|---|
| `dhf/` | DHF skeleton: V&V plan (empty user-need registry), a `kind: design` context template, design review, traceability-matrix template |
| `dhf/AGENT_WORKFLOW.md` | the end-to-end change procedure (humans and AI agents) |
| `.githooks/pre-commit` | the local design gate: implementation commits are blocked until the design record is approved |
| `.claude/settings.json` + `scripts/agent-bootstrap.sh` | agent sessions activate the gate automatically |
| `.github/workflows/design-controls.yml` | CI: design gate active immediately; the remaining gates ship commented until your first design input lands |

The templates deliberately contain placeholder markers, so the design gate
stays **red until you write and commit your actual record** — that is the
honest starting state, not an error.

## First hour

```bash
git config core.hooksPath .githooks          # humans; agent sessions do it automatically
git add dhf/ .githooks/ .claude/ scripts/ .github/ && git commit -m "Adopt design controls"
```

1. Register your first **user need** in
   `dhf/documents/verification_and_validation_plan.md` — a validated journey,
   not a feature.
2. Rename `dhf/documents/design/example_context.md` for your first bounded
   context and fill it in.
3. Declare your first **design input** and let the scaffolder wire it:

   ```bash
   rdm story new-input --dhf dhf --list
   rdm story new-input --dhf dhf --context <ctx> \
     --text "The system shall …" --traces-to UN-001
   ```

4. Follow the printed checklist (commit the design docs first — that commit
   *is* the approval — then implement, replace the failing stub test, get an
   independent [faithfulness verdict](design-controls.md#faithfulness-review), run
   the gates).

## Backfilling an undocumented codebase

Adopt is a ratchet: all **new** work is gated from day one; the old code is
backfilled context-by-context, by risk — declare only the design inputs you
can verify in the same change, and tag *existing* tests rather than writing
new ones. `rdm story audit` is the progress meter. The full strategy is in
the [agent workflow](agent-workflow.md).
