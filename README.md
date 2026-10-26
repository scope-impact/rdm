# rdm-spec

Requirements kept as executable scenarios: the spec is the product, and the
implementation is regenerated from it in a clean room, ratified by a check the
builder does not control, and measured by a grader who never built it. It
follows the Löyly method (`dhf/wiki/pages/loyly-method.md`). Its regulated
profile (`:regulated`) adds gap analysis against declared clause lists and an
audit copy for an auditor.

This repository was RDM, a Python tool for regulated software's design history
file. At the cutover (2026-10-26, `dhf/decisions/adr-0002-spec-first-regeneration.md`)
the Python implementation was removed: what RDM does is now written as the
spec under `dhf/spec/`, and the code under `src/` is regenerated from it. The
Python record is readable in git history before the cutover commit.

## The repository

| Path | What it is |
|---|---|
| `dhf/spec/` | the spec: feature files by bounded context, the C4 model (`model.edn`), events, needs, glossary, the id ledger, its README of conventions |
| `dhf/views/` | the architecture views, generated from the model |
| `dhf/harness/` | the configuration (`config.edn`) and the stack (`stack.md`) |
| `dhf/raw/`, `dhf/wiki/` | the sources the spec is distilled from, and the domain wiki that cites them |
| `dhf/decisions/`, `dhf/storm/` | the decision to work this way, and the event storm that shaped the spec |
| `dhf/cycles/NN/` | each regeneration: its cut, checklist, verdicts, score and proposed spec changes |
| `src/`, `test/`, `test.clj`, `bb.edn` | the implementation, regenerated from a cut; never edited to change behaviour (change the spec) |

## Use

```bash
rdm-spec help                  # every task with its usage
rdm-spec check                 # the spec check, the model lint, the views, sources, wiki (and clauses under :regulated)
rdm-spec stamp                 # give new scenarios their ids
rdm-spec views                 # regenerate the architecture views
rdm-spec status | trace ID | release
rdm-spec publish               # the site
rdm-spec audit OUT             # under :regulated: the auditor's and engineer's copies, with their evidence
```

Run the implementation's own tests with `RESULTS_DIR="$(mktemp -d)" bb test.clj`.

## A regeneration cycle

`cut` → `fence ROOM CUT` (behind the oracle gate) → one builder rebuilds the
implementation in the room → `ratify ROOM NN` → `checklist NN` → graders file
verdicts → `score NN` → `scoreboard MM NN` → findings folded into the spec.
The oracle (`oracle.edn`, one artifact pinned by digest in `config.edn`)
judges the instrument before fence and ratify.

## License

`LICENSE.txt`.
