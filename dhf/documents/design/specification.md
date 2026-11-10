---
id: SDS-SPEC-001
kind: design
context: specification
---

# Design specification — Software Design

## Purpose

The core bounded context: the spec the record is held to, read, never
written. Its requirements are the spec's stamped scenarios (`item-NN-SSS`)
and its user needs, by default, its Features; adapters read richer layouts
(Löyly's: event-model screens with their Allium surfaces).
Every other context conforms to its ids. It also reads the validation
records of the user needs. Onboarding
(`rdm init`, `rdm adopt`), the commands that lay a project down, is not a
context: it is this context's application layer.

It reads the specification; it does not police how one is written. The
design gate, its git hooks, `rdm story new-input` and persona runs were
removed: authoring the specification belongs to the
factory that owns it (ADR 0002).

## Design Outputs

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Gherkin reader | Reads feature files: each scenario tagged with an id (any id-shaped tag, or the configured pattern) a requirement with its Feature, Rule, title, source line and tags; the last id tag before a scenario its id; each untagged scenario listed. | item-01-001 |
| Spec configuration | Finds the spec the record is held to (`spec` in the DHF's `config.yml`: feature globs, exclusions, id pattern, needs source, layout; else `.pi/plan`, `spec/` or `features/` from the repository root) and its user needs: by default each Feature, addressed by the scenarios under it. | item-01-001, item-01-005 |
| Löyly adapter | An optional layout and needs source: Löyly's plan of slices (planned and backlog skipped, `drafts.feature` never read) and its user needs, the event-model screens with their Allium surfaces, actors, rules and guarantees and the event-storm events behind them. | item-01-001, item-01-005 |
| Project scaffold | `rdm init`: copies the project templates into a new directory; refuses a directory that exists (exit 2), and on success names what it laid down and the next steps. | item-01-002 |
| Project templates | What `rdm init` lays down: the document templates (the traceability matrix among them), the build Makefile (a Word document is rebuilt when its reference document changes), the render configuration, the container build (installing the RDM release from git when no wheel is given), and the Pandoc and Typst configuration. Templates use only local images. | item-01-002 |
| Adoption | `rdm adopt`: creates each missing file of the adoption templates and skips, never overwrites, one that exists (a symbolic link exists, dangling or not); when a `.gitignore` exists, names the lines of RDM's generated files to add to it; stamps the installed version as its release tag (a pre-release such as 2.0.0a0 as 2.0.0-alpha, the tag the release and its image carry); reports what it skipped and prints the next steps. | item-01-003 |
| Adoption templates | What `rdm adopt` lays down: the DHF skeleton (V&V plan, traceability matrix, render configuration, README), a CI gate workflow and a `.gitignore` of RDM's generated files (Allure results, verification data, the graph store, bytecode). | item-01-003 |
| Validation records | Reads the per-user-need validation records from the DHF's validation directory; only an approved disposition with a named reviewer counts. Gives the user needs without one. | item-01-004 |

- Project scaffold and adoption copy their templates.
- Validation records read the user needs with the need reader.

## Dependencies

Layer 2 of the dependency rule. Depends on the shared kernel only. Depended
on by `test_evidence` (the pytest plugin reads the requirements), `release`
(the release gate, the verification data), `publishing` (the DMR index, the
verification report, the evidence bundle) and `graph` (the projection). The
composition root wires its commands. It reads no results and no risk
register.

## Out of scope

- Authoring the specification: scaffolding a requirement, gating
  implementation on an approved design, and the git hooks that ran that
  gate. The factory that owns the specification does this (ADR 0002).
- Summative and formative usability validation. The human summative study is
  the validation record; RDM ships no persona runs.
- Planning. RDM ships no planning tooling; tasks live outside the record.
