---
id: SDS-ARCH-001
kind: design
context: architecture
design_inputs:
  - id: DI-66
    text: "RDM shall read the C4 model from the architecture workspace in the record (one Structurizr workspace, exported as JSON beside it): every person, software system, container and component with its identifier, name, technology, description and whether it is external; the element that contains it; the bounded context each component belongs to (its group); every relationship with its source, destination, description and technology; and each component's code (its code property, a file or a directory)."
    traces_to: [UN-017]
  - id: DI-68
    text: "RDM shall warn, never block, through the graph's gate shapes, when the C4 model and the record disagree: a design output in no component's code; a test run that exercises a component of a context that neither owns nor realises the design input it verifies; a dependency between two components with no relationship declared from the one to the other; a group of components that is not a bounded context of the architecture, or a bounded context with no component; a bounded context whose design document does not show its component view; a component whose code path does not exist; and a relationship with no description."
    traces_to: [UN-017]
  - id: DI-70
    text: "RDM shall draw each view of the architecture workspace to an image in the record, stamped with the workspace it was drawn from, and the design gate shall fail when the workspace's exported model or any view's image was not drawn from the current workspace, or a view has no image."
    traces_to: [UN-017, UN-001]
---

# Architecture — Software Design

## Purpose

This context owns the architecture workspace, its views, and the
conformance of the code and the record to it. It speaks the language of the
C4 model: a **person**, a **software system**, a **container** (something
that runs or stores data), a **component** (a logical building block inside
exactly one container, owned by one bounded context and naming its code), a
**relationship** (directed, labelled with what the source does to the
target), and an **architecture view** (one view of the workspace at one
level, drawn from it and stamped with it, never edited). The **architecture
workspace** (`dhf/c4/workspace.dsl`) is written by people and is the source
of every view; a bounded context is a group of components in it. The system
architecture document describes the system; this context keeps its C4 model.

## Design Inputs

- **DI-66 (the C4 model, read from the architecture workspace)** — the
  architecture is one Structurizr workspace, `dhf/c4/workspace.dsl`: the
  model (people, software systems, containers, components grouped by bounded
  context) and its views (the system context and the containers for the
  system, one component view for each bounded context). Each component names
  its code with a `code` property, a file or a directory, so the code level is
  the code itself. RDM reads the model from the workspace's JSON export,
  `dhf/c4/workspace.json`, which `rdm c4 draw` writes beside it (DI-70), so
  reading needs neither Java nor a parser. One identifier is one element; the
  workspace, not the views, declares it. It is scoped here because the model
  is this context's subject; projecting the model into the knowledge graph is
  the graph's DI-67, and checking the model against the record is DI-68.
  Amended (Design Review 29): the model was read from Mermaid C4 diagrams in
  the design documents. Refines UN-017.
- **DI-68 (conformance, as warnings)** — the graph's gate shapes compare the
  model with the record and the code: code a test exercises that no
  component names; a test that exercises another context's component than
  the one that owns or realises its input; a dependency in the code with no
  relationship declared for it; a group that is not a bounded context, or a
  context with no component; a context whose design document does not show
  its view; a code path that does not exist; a relationship with no
  description. Every one is a warning: an architecture disagreement is a
  question for the reviewer, never a release block. It is scoped here because
  what counts as a disagreement is a statement about the C4 model; the
  mechanism that reports it is the knowledge graph's. Refines UN-017.
- **DI-70 (architecture views as images)** — `rdm c4 draw` exports the
  workspace with Structurizr's CLI: its model as `dhf/c4/workspace.json`,
  and each view as DOT, drawn by Graphviz to `dhf/c4/views/<view>.svg`. Each
  design document shows its view as an ordinary image, so GitHub, the docs
  site and a rendered PDF show the same picture, and no browser draws it.
  The drawn files are committed, each stamped with the SHA-256 of the
  workspace; the design gate fails when a stamp is not the current
  workspace's, when a view has no image, or when an image has no view, so a
  stale view cannot be committed. Checking needs only the stamps: Java and
  Graphviz are needed only to draw. It leaves out how a view is laid out
  (Graphviz's automatic layout, from the workspace's `autolayout`). Amended
  (Design Review 29): DI-70 rendered Mermaid diagrams with Mermaid's
  renderer. Refines UN-017 and UN-001.

## Design Outputs

**Architecture model** (`rdm/architecture/model.py`) meets DI-66 and the checking
half of DI-70. It needs only the Python standard library.

- `read_model(dhf, root)` reads `c4/workspace.json` into a `Model`: every
  person, software system, container and component as an `Element`, keyed by
  its workspace identifier (`structurizr.dsl.identifier`, the `!identifiers
  flat` alias), with its kind, name, technology, description, whether it is
  external (the `External` tag), the element that contains it, a
  component's bounded context (its group) and its code (the `code`
  property). Every declared relationship is read with its source,
  destination, description and technology; the relationships Structurizr
  implies (those with a `linkedRelationshipId`) are left out, so only what
  the workspace declares counts. The model also names the workspace's path
  and the key of every view. A DHF with no exported workspace has an empty
  model.
- `stale(dhf)` says why the drawn files are not the current workspace's: the
  export missing, not valid JSON, or stamped with another workspace's
  SHA-256; a view with no image or with an image stamped for another
  workspace or view; an image under `c4/views/` that is no view's. It reads
  only the stamps, so it needs neither Java nor Graphviz, and it reports
  nothing for a DHF with no workspace.
- `component_of(path, components)` gives the component whose code is the
  longest match for a repository-relative file: the file itself, or a
  directory above it. `component_dependencies(model, root)` gives every
  Python import (absolute imports only) from one component's code into
  another's, with one example file pair for each pair of components; a file
  a more specific component owns counts for that component only. These two
  serve the knowledge graph's projection of the model (DI-67), and are the
  code dependencies DI-68's warnings will be checked against.

**Architecture drawing** (`rdm/architecture/draw.py`, `rdm c4 draw`) meets the drawing
half of DI-70. It runs Structurizr's CLI (`RDM_STRUCTURIZR`, or
`structurizr.sh` / `structurizr` on the PATH) twice side by side, exporting
the workspace as JSON and as DOT, and Graphviz's `dot` (`RDM_DOT`, or on the
PATH) on each view's DOT. It escapes the bare `&` that Structurizr's DOT
export leaves in its labels, which Graphviz would reject. It writes
`c4/workspace.json` stamped with the workspace's SHA-256 (the base64 copy of
the workspace that Structurizr embeds is dropped), and `c4/views/<view>.svg`
for each view with a stamp comment after the XML declaration naming the view
and the workspace's SHA-256. It removes the image of a view the workspace no
longer has. A workspace that cannot be exported or drawn fails with the
tool's reason, and nothing is written. It takes the paths, the stamp's
format, the view keys and the digest from the architecture model, so the
drawing and the check cannot disagree on them. Every kind of view the
workspace declares is drawn, stamped and checked alike: a dynamic view is
drawn, stamped and freshness-checked like every other view, and
Structurizr's DOT export draws it as numbered collaboration steps, not
lifelines.

**DI-68 is not implemented yet.** Its tagged test is a failing stub
(`test_di_68_not_implemented` in `tests/acceptance/test_graph_c4.py`), and
the graph's gate shapes (`rdm/graph/shapes.ttl`) hold no shape for it yet.
The graph's gate shapes will realise it, over the `architecture` and `code`
named graphs that the knowledge graph already projects.

This context realises no other context's input. Parts of its own are
realised elsewhere: the design gate (`specification`,
`rdm/specification/design_gate.py`, `check_architecture_views`) realises DI-70's
failing check, adding the stale reasons from `stale` and failing the
architecture views when the workspace or its drawn files are uncommitted;
the knowledge graph will realise DI-68. The knowledge graph projects the
model (`rdm/graph/c4.py`, DI-67, which graph owns).

DI-66 is verified by `@allure.story("DI-66")` in
`tests/acceptance/test_c4_model.py`, which also reads RDM's own workspace
whole; DI-70 by `@allure.story("DI-70")` in
`tests/acceptance/test_rendering.py`, with stand-ins for Structurizr and
Graphviz, which also checks that RDM's own views are current.

## Components (C3)

![Components: architecture](../../c4/views/C3_architecture.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Architecture model | The C4 model, read from the workspace's export; view freshness | Python | `rdm/architecture/model.py` |
| Architecture drawing | `rdm c4 draw`: Structurizr export, Graphviz views, stamps | Python, Structurizr, Graphviz | `rdm/architecture/draw.py` |

Both components sit in the `rdm` container. The relationships, in the
direction of the arrow:

- Architecture drawing → Architecture model: stamps views with the workspace
  digest of it, and takes from it the paths and the view keys it writes.
- Design and release gates (`specification`) → Architecture model: checks
  the views are fresh with it (`stale`).
- Projection (`graph`) → Architecture model: reads the C4 model and the code
  dependencies with it (`read_model`, `component_of`,
  `component_dependencies`).

Assumptions and open questions:

- Structurizr's CLI, Java and Graphviz are tools on the drawing machine, not
  elements of the model; the view does not show them, nor that the drawing
  writes the export and the images into the product repository.
- Components name their code by path, so the longest-match rule assigns
  every module to one component; a test fails on a module no component
  names (the dependency rule, `tests/dependency_rule_test.py`).
- Code dependencies are found for Python only, and only for absolute
  imports; a relative import is not seen.

## Dependencies

Layer 1 of the dependency rule, a leaf: the Architecture model imports only
the Python standard library, and the Architecture drawing only the
Architecture model. Depended on by `specification` (the design gate checks
the views are fresh), `graph` (the C4 projection: `read_model`,
`component_of`, `component_dependencies`) and the composition root (`rdm c4
draw`). No import breaks the rule.
