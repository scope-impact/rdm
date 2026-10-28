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
| `docs/wow/` | the way we work, as Löyly keeps it: a cycle, the agent harness, the invariants and what enforces each |
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

## Install

rdm-spec needs [Babashka](https://babashka.org) (tested with 1.13.227) and git.

From a release, take `rdm-spec.jar` and put a wrapper on your PATH:

```bash
mkdir -p ~/.local/share/rdm-spec ~/.local/bin
cp rdm-spec.jar ~/.local/share/rdm-spec/
printf '#!/bin/sh\nexec bb ~/.local/share/rdm-spec/rdm-spec.jar "$@"\n' > ~/.local/bin/rdm-spec
chmod +x ~/.local/bin/rdm-spec
rdm-spec help
```

From a checkout, without a jar:
`exec bb -cp /path/to/rdm/src:/path/to/rdm/res -m rdm-spec.kernel.command "$@"`,
with the plugin templates (`plugins/`) copied to `res/rdm-spec/plugins/`.
To build the jar the same way:
`bb --classpath src:res uberjar rdm-spec.jar -m rdm-spec.kernel.command`.

Then in your project: `rdm-spec init PATH` for a new one, or `rdm-spec adopt`
in an existing repository. A project may be one folder of a larger
repository (run rdm-spec in that folder), and may keep several specs, one
folder each, under the configuration's `:specs` (`dhf/spec/02-write-the-spec/parser.feature`).
A project's own judges, its oracle and its grading among them, are plugins
(`dhf/decisions/adr-0003-core-and-plugins.md`; `rdm-spec plugin add NAME`).

## Slice development

Work is delivered slice by slice (`dhf/decisions/adr-0005-slice-development.md`,
after Löyly): a slice is a folder of the spec with its plan (status, goal,
scope), one feature file per component it reaches, and its decisions. A
behaviour change starts in a slice; a cycle builds one slice, whole:
`cut` → `scopes` names the slices touched → `fence ROOM CUT --slice S` (behind
the project's Gate plugins) → one builder rebuilds the slice's components →
`verify ROOM NN` → `checklist NN` (the slice's items and its regressions) →
graders file verdicts → `score NN --verdicts FILE ...` → `scoreboard MM NN` →
findings folded into the slice. The way of working is `docs/wow/`.

## License

`LICENSE.txt`.
