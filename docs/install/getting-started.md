# Quick start

Two ways in: add RDM to a repository you already have (`rdm adopt`), or
start a documentation project from nothing (`rdm init`). [Install](installation.md)
RDM first.

## An existing repository: `rdm adopt`

```bash
cd your-repo
rdm adopt .
```

`rdm adopt` lays down only what RDM reads and runs, and never overwrites a
file, so running it again changes nothing:

| Path | Role |
|---|---|
| `dhf/` | the design history file: the V&V plan, the traceability-matrix template, the render configuration (`dhf/config.yml`) and a README |
| `.gitignore` | what RDM generates (Allure results, verification data, the graph store, bytecode); if you have one, it is kept and the lines to add are printed |
| `.github/workflows/design-controls.yml` | CI through RDM's [reusable gates](../use/ci.md), pinned to this release; it runs on pushes to `main`, so change the branch if yours is another |

It prints the next steps.

### Your first passing release gate

1. Write the spec in the skeleton `rdm adopt` laid down in `.pi/` (the
   Löyly way; a repository that already had plain Gherkin under `spec/` or
   `features/` keeps it). Each accepted scenario in a context's
   `spec.feature`, stamped `@item-01-001`, is a requirement; each screen of
   its event model, linked to an Allium surface, is a user need. See
   [The spec and its tests](../use/spec-and-tests.md).
2. Tag the acceptance test that verifies each requirement with
   `@allure.tag("item-01-001")` (a Gherkin runner writes the scenario's tags
   for you), and run the suite with Allure.
3. Run `rdm story release-gate --dhf dhf --allure-results dhf/allure-results`.
   It passes once every requirement has a passing tagged run and every user
   need is addressed.
4. Open a pull request: its review by someone other than you is the
   approval. Then set `acceptance-tests` and `release-gate` to `true` in the
   CI workflow.

Where an existing acceptance-level test (one that checks a requirement end
to end) already verifies a scenario, tag it with the scenario's item id
rather than writing a new one; unit tests stay untagged.

## A new project: `rdm init`

```bash
rdm init -o regulatory        # default output directory: dhf
cd regulatory
```

`rdm init` scaffolds a whole documentation project: the document templates
(the IEC 62304 set, 510(k) documents and more), data files, render
config, the Pandoc and Typst setup, a `Makefile`, a Docker setup and a
`.gitignore`, and prints the
next steps. Start with `data/device.yml`, your device's facts;
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

Then [Authoring and rendering](../use/documents.md), [The spec and its tests](../use/spec-and-tests.md)
and [gap analysis](../use/gap-analysis.md). Don't use `rdm init` in a repository
that already has code; use `rdm adopt`.
