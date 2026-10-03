# Get started

## Install

RDM is a Python CLI (Python 3.10+). Install it from its repository, pinned to
a release; PyPI's `rdm` is another project.

```bash
uv tool install "rdm @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha"
uv tool install "rdm[graph,report] @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha"   # with the extras
rdm --version
```

| Extra | Adds |
|---|---|
| (none) | the record, the gates (`rdm story …`), `gap`, `render`, `init`, `adopt` |
| `graph` | the record as an RDF graph and the agent server (`rdm graph …`) |
| `report` | the verification report as a PDF (`rdm story evidence-report`) |

The tests need `pytest` and `allure-pytest`. Rendering PDFs needs Pandoc,
Typst and Make, or the RDM Docker image, which has them all. In CI, call
RDM's reusable workflow instead of installing it:
[the gates in your CI](reusable-ci.md). The CLI is cross-platform; the
pre-commit hook and the bootstrap script are bash, so on Windows run them
under Git Bash or WSL.

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
| `dhf/AGENT_WORKFLOW.md` | the change procedure, for people and agents: [Changing the record](agent-workflow.md) |
| `.githooks/` | the design gate as git hooks (pre-commit, pre-merge-commit): implementation commits and merges wait until the design is approved |
| `.gitignore` | what RDM generates (Allure results, verification data, the graph store, bytecode); if you have one, it is kept and the lines to add are printed |
| `.claude/settings.json`, `scripts/agent-bootstrap.sh` | agent sessions turn the hook on themselves |
| `.github/workflows/design-controls.yml` | CI through RDM's [reusable gates](reusable-ci.md), pinned to this release; it runs on pushes to `main`, so change the branch if yours is another |

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

Then [Authoring and rendering](authoring.md), [Design inputs and tests](design-controls.md)
and [gap analysis](gap-analysis.md). Don't use `rdm init` in a repository
that already has code; use `rdm adopt`.
