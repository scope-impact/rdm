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
| `dhf/` | the design history file: the V&V plan, a design document template for your first bounded context, the design review, the traceability-matrix template |
| `dhf/AGENT_WORKFLOW.md` | the change procedure, for people and agents: [Changing the record](agent-workflow.md) |
| `.githooks/` | the pre-commit design gate: implementation commits wait until the design is approved |
| `.claude/settings.json`, `scripts/agent-bootstrap.sh` | agent sessions turn the hook on themselves |
| `.github/workflows/design-controls.yml` | CI through RDM's [reusable gates](reusable-ci.md), pinned to this release |

It prints the next steps. The templates carry placeholder markers, so the
design gate stays red until you write and commit your record: that is the
honest starting state, not an error.

Adopting is a ratchet: new work is gated from day one, and the existing code
is brought in context by context, riskiest first. Declare only the design
inputs you can verify in the same change, and tag the tests that already
exist rather than writing new ones. `rdm graph validate` lists what is still
untagged.

## A new project: `rdm init`

```bash
rdm init -o regulatory        # default output directory: dhf
cd regulatory
```

`rdm init` scaffolds a whole documentation project: the document templates
(the design-controls set, 510(k) documents and more), data files, render
config, the Pandoc and Typst setup, a `Makefile` and a Docker setup. Facts go
in YAML under `data/`, documents are Markdown with Jinja2 under `documents/`,
and anything inside `TODO … ENDTODO` is yours to replace.

```bash
make                          # render documents/ to release/*.md
make pdfs                     # …and to PDF
rdm gap 62304_2015_class_b documents/*.md
```

Then [Authoring and rendering](authoring.md), [Design inputs and tests](design-controls.md)
and [gap analysis](gap-analysis.md). Don't use `rdm init` in a repository
that already has code; use `rdm adopt`.
