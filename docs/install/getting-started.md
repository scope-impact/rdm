# Getting started

Two ways in: bring an existing repository under design controls
(`rdm adopt`), or start a documentation project from nothing (`rdm init`).
Install RDM first ([Installation](installation.md)).

## An existing repository: `rdm adopt`

```bash
cd your-repo
rdm adopt .
```

`rdm adopt` lays down only the control surface and never overwrites a file,
so running it again changes nothing:

| Path | Role |
|---|---|
| `dhf/` | the design history file: the V&V plan, a design document template for your first bounded context, the design review, the traceability-matrix template, the render configuration (`dhf/config.yml`) |
| `dhf/AGENT_WORKFLOW.md` | the change procedure, for people and agents: [Changing the record](../use/changing-the-record.md) |
| `.githooks/` | the design gate as git hooks (pre-commit, pre-merge-commit): implementation commits and merges wait until the design is approved |
| `.gitignore` | what RDM generates (Allure results, verification data, the graph store, bytecode); if you have one, it is kept and the lines to add are printed |
| `.claude/settings.json`, `scripts/agent-bootstrap.sh` | agent sessions turn the hook on themselves |
| `.github/workflows/design-controls.yml` | CI through RDM's [reusable gates](../use/ci.md), pinned to this release; it runs on pushes to `main`, so change the branch if yours is another |

It prints the next steps. The templates carry placeholder markers, so the
design gate stays red until you write and commit your record: that is the
honest starting state, not an error.

Adopting is a ratchet: new work is gated from day one, and the existing code
is brought in context by context, riskiest first. Declare only the design
inputs you can verify in the same change. Where an existing acceptance-level
test (one that checks a requirement end to end) already verifies a design
input, tag it with the input's id rather than writing a new one; unit tests
stay untagged. `rdm graph validate` lists the design inputs that still have
no tagged test.

## A new project: `rdm init`

```bash
rdm init -o regulatory        # default output directory: dhf
cd regulatory
```

`rdm init` scaffolds a whole documentation project: the document templates
(the design-controls set, 510(k) documents and more), data files, render
config, the Pandoc and Typst setup, a `Makefile` and a Docker setup, and
prints the next steps. Start with `data/device.yml`, your device's facts;
documents are Markdown with Jinja2 under `documents/`. Every placeholder is
yours to replace: lines starting `TODO`, and `TODO … ENDTODO` blocks.

```bash
make                          # render documents/ to release/*.md
make pdfs                     # …and to PDF
rdm gap 62304_2015_class_b documents/*.md documents/*/*.md
```

The templates already reference every clause of the standard, so a fresh
project passes that gap analysis while its documents still say nothing: a
pass means *referenced*, not *written*, and the report names each document
that still holds a placeholder.

For the PDFs, Pandoc, Typst and the template's fonts (Nunito Sans and
JetBrains Mono; Noto Sans and DejaVu Sans Mono stand in) must be installed, or
use the Docker setup: `docker compose run rdm make pdfs` builds an image with
all of them and the RDM release that laid the project down.

Then [Authoring and rendering](../use/documents.md), [Design inputs and tests](../use/design-inputs-and-tests.md)
and [gap analysis](../use/gap-analysis.md). Don't use `rdm init` in a repository
that already has code; use `rdm adopt`.
