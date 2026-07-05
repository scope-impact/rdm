# Quickstart — a new documentation project

`rdm init` scaffolds a complete regulatory documentation project: document
templates (including the design-controls set), a build `Makefile`, render
config, and Pandoc setup.

```bash
rdm init -o regulatory        # default output directory: dhf
cd regulatory
ls documents/                 # the template set, incl. software_design_specification.md,
                              # design_review.md, traceability_matrix.md, 510k/…
```

## 1. Fill in your data

Project facts live in YAML under `data/` (device name, version, people…);
documents are Markdown + [Jinja2](authoring.md) under `documents/`. Edit the
templates — anything wrapped in `TODO … ENDTODO` is yours to replace, and the
[design gate](design-controls.md) treats leftover markers as "not complete".

## 2. Render

```bash
rdm render documents/software_description.md config.yml data/*.yml > out.md
make                          # or: the full set → PDFs via Pandoc (see Makefile)
```

## 3. Check against a standard

```bash
rdm gap --list                                        # shipped checklists
rdm gap 62304_2015_class_b documents/*.md             # what's missing?
```

See [Gap analysis](gap-analysis.md).

## 4. Turn on design controls

The scaffolded project inherits the record-first model (`kind: design`
template, design review, traceability matrix). Declare user needs and design
inputs, tag your acceptance tests, and run the gates — the whole loop is in
[Design controls](design-controls.md).

!!! tip "Existing codebase instead?"
    Don't use `rdm init` inside a repository that already has code — use
    [`rdm adopt`](quickstart-existing-repo.md), which lays down only the
    control surface and never overwrites anything.
