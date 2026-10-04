# Quick start

Two ways in: add RDM to a repository you already have (`rdm adopt`), or
start a documentation project from nothing (`rdm init`). [Install](installation.md)
RDM first.

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

It prints the next steps. The templates carry `TODO` markers, so the design
gate stays red until you write and commit your record: that is the honest
starting state, not an error.

### Your first passing design gate

1. Turn the hooks on in this clone: `git config core.hooksPath .githooks`.
2. Register your first user need in
   `dhf/documents/verification_and_validation_plan.md` (`user_needs:`, an
   `id` such as `UN-001` and its text), and replace its `TODO` markers.
3. Rename `dhf/documents/design/example_context.md` after your first bounded
   context, set its `context:` to the same name, and replace its `TODO`
   markers.
4. Fill in `dhf/documents/design_review.md`: what you reviewed, and the
   disposition. Replace every `TODO` marker.
5. Declare your first design input:
   `rdm story new-input --dhf dhf --context <context> --text "<the system shall …>" --traces-to UN-001`.
   It also writes a failing test stub under `tests/acceptance/`: that is
   implementation, so leave it out of the next commit.
6. Commit the record alone (`git add dhf && git commit`), then run
   `rdm story design-gate --dhf dhf`. It prints `Design gate PASSED`, and
   the hook now lets the stub test and your code be committed.
7. Open a pull request: its review by someone other than you is the
   approval. Then continue with [Changing the record](../use/changing-the-record.md).

Adopting is a ratchet: new work is gated from day one, and the existing code
is brought in context by context, riskiest first. Declare only the design
inputs you can verify in the same change. Where an existing acceptance-level
test (one that checks a requirement end to end) already verifies a design
input, tag it with the input's id rather than writing a new one; unit tests
stay untagged. The design gate warns on each design input that still has no
tagged test.

## A new project: `rdm init`

```bash
rdm init -o regulatory        # default output directory: dhf
cd regulatory
```

`rdm init` scaffolds a whole documentation project: the document templates
(the design-controls set, 510(k) documents and more), data files, render
config, the Pandoc and Typst setup, a `Makefile` and a Docker setup, the
change procedure (`AGENT_WORKFLOW.md`) and a `.gitignore`, and prints the
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

Then [Authoring and rendering](../use/documents.md), [Design inputs and tests](../use/design-inputs-and-tests.md)
and [gap analysis](../use/gap-analysis.md). Don't use `rdm init` in a repository
that already has code; use `rdm adopt`.
