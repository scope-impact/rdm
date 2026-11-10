---
id: SDS-SPEC-001
kind: design
context: specification
# Implements part of inputs other contexts own.
realises: [DI-61, DI-63]
design_inputs:
  - id: DI-1
    text: "RDM shall read the user-need registry and the design inputs that trace to it from the frontmatter of the record, with no project-management dependency: reading the record imports no planning tool and needs no planning directory."
    traces_to: [UN-001, UN-004]
  - id: DI-31
    text: "RDM shall discover verification tags in non-Python test sources — JavaScript/TypeScript allure.story calls and Java @Story annotations across conventional test-file names — so tag-linkage warnings and verification work in polyglot repositories; only the story names a design input."
    traces_to: [UN-004]
  - id: DI-40
    text: "RDM shall read a Python test file's verification tags only from allure story decorators on its test functions and classes and from a module-level pytestmark — never from strings or comments — so a test that writes fixture files is not counted as verifying the ids those files name; a file that does not parse falls back to the decorator pattern. Only the story names a design input."
    traces_to: [UN-004]
  - id: DI-15
    text: "RDM shall scaffold a new documentation project from one command into a directory that does not yet exist, laying down the document templates (including a V&V plan with an empty user-need registry and the traceability matrix), build Makefile, container build and render config, buildable offline without an RDM checkout, and naming what it laid down and the next steps."
    traces_to: [UN-008]
  - id: DI-24
    text: "RDM shall bring an existing repository under RDM from one command: lay down the DHF skeleton (V&V plan, traceability matrix, render configuration, README), a CI gate workflow and a .gitignore of what RDM generates, skipping (never overwriting) any destination that already exists, a symbolic link included, and naming the .gitignore lines to add when it keeps an existing one."
    traces_to: [UN-011]
  - id: DI-33
    text: "RDM shall ingest per-user-need validation records (user need, disposition, reviewer) from the DHF's validation directory and report, at the release gate, each user need lacking an approved validation record (an approved disposition with a named reviewer) as a warning that does not block release."
    traces_to: [UN-005]
---

# Design specification — Software Design

## Purpose

The core bounded context: the user needs, the design inputs that refine them
(each owned by one context and naming the needs it traces to), and the tagged
tests that claim to verify them. It speaks the glossary's user need, design
input, design document and tagged test, and, for validation, validated
(validation records). Every other context conforms to its ids. Onboarding
(`rdm init`, `rdm adopt`), the commands that lay a project down, is not a
context: it is this context's application layer.

It reads the specification; it does not police how one is written. The
design gate, its git hooks, `rdm story new-input` and persona runs were
removed: authoring the specification belongs to the
factory that owns it (ADR 0002).

## Design Outputs

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| Record reader | Reads the user needs, the design inputs (a user need traced as one value, not a list, is that one need), what each context realises, and every declaration of an id, from the frontmatter of the design documents and the V&V plan. Finds a design document by its `kind: design` marker, never by its name; an id declared more than once keeps its first declaration, and every declaration is listed. Lists every declaration it cannot read (design inputs that are not a list of entries each with one id, a user need with no id, design inputs outside a design document), so none is dropped unseen. | DI-1 |
| Test tags | Finds the test suite (no higher than the DHF's repository root) and the design input each test claims to verify: in Python from the syntax tree, on tests only (never a helper) and under any name the file imports the story decorator as, never from strings or comments, falling back to the decorator pattern when a file does not parse; in JavaScript, TypeScript and Java by pattern, across the conventional test-file names; and in a YAML task's own tags. Only the story names a design input. Lists each tagged test by name for the knowledge graph. | DI-1, DI-31, DI-40 |
| Project scaffold | `rdm init`: copies the project templates into a new directory; refuses a directory that exists (exit 2), and on success names what it laid down and the next steps. | DI-15 |
| Project templates | What `rdm init` lays down: the document templates (the V&V plan with its user-need registry and the traceability matrix among them), the build Makefile (a Word document is rebuilt when its reference document changes), the render configuration, the container build (installing the RDM release from git when no wheel is given), and the Pandoc and Typst configuration. Templates use only local images. | DI-15 |
| Adoption | `rdm adopt`: creates each missing file of the adoption templates and skips, never overwrites, one that exists (a symbolic link exists, dangling or not); when a `.gitignore` exists, names the lines of RDM's generated files to add to it; stamps the installed version as its release tag (a pre-release such as 2.0.0a0 as 2.0.0-alpha, the tag the release and its image carry); reports what it skipped and prints the next steps. | DI-24 |
| Adoption templates | What `rdm adopt` lays down: the DHF skeleton (V&V plan, traceability matrix, render configuration, README), a CI gate workflow and a `.gitignore` of RDM's generated files (Allure results, verification data, the graph store, bytecode). | DI-24 |
| Validation records | Reads the per-user-need validation records from the DHF's validation directory; only an approved disposition with a named reviewer counts. Gives the user needs without one. | DI-33 |

- Project scaffold and adoption copy their templates.
- Validation records read the registry with the record reader.

## Dependencies

Layer 2 of the dependency rule. Depends on the shared kernel only. Depended
on by `test_evidence` (the pytest plugin reads the design inputs), `release`
(the release gate, the verification data), `publishing` (the DMR index, the
verification report, the evidence bundle) and `graph` (the projection). The
composition root wires its commands. It reads no results and no risk
register.

## Out of scope

- Authoring the specification: scaffolding a design input, gating
  implementation on an approved design, and the git hooks that ran that
  gate. The factory that owns the specification does this (ADR 0002).
- Summative and formative usability validation. The human summative study is
  the validation record; RDM ships no persona runs.
- Planning. RDM ships no planning tooling; tasks live outside the record.
