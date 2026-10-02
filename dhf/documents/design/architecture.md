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

> **Interim (Design Review 30).** This context was formed from `record`, `graph`, `rendering`. Its
> design inputs moved here unchanged; the prose below is carried over verbatim
> from those documents, by section, until it is rewritten for this context.

## Design Inputs

Owns the architecture workspace, its drawn views, and the conformance of the code and the record to it.

- **DI-66 (the C4 model, read from the architecture workspace)** — the
  architecture is one Structurizr workspace, `dhf/c4/workspace.dsl`: the
  model (people, software systems, containers, components grouped by bounded
  context) and its views (the system context and the containers for the
  system, one component view for each bounded context). Each component names
  its code with a `code` property, a file or a directory, so the code level is
  the code itself. RDM reads the model from the workspace's JSON export,
  `dhf/c4/workspace.json`, which `rdm c4 draw` writes beside it (DI-70), so
  reading needs neither Java nor a parser. One identifier is one element; the
  workspace, not the views, declares it. Amended (Design Review 29): the model
  was read from Mermaid C4 diagrams in the design documents. Refines UN-017.
- **DI-68 (conformance, as warnings)** — the gate shapes compare the model
  with the record and the code: code a test exercises that no component
  names; a test that exercises another context's component than the one that
  owns or realises its input; a dependency in the code with no relationship
  declared for it; a group that is not a bounded context, or a context with
  no component; a context whose design document does not show its view; a
  code path that does not exist; a relationship with no description. Every
  one is a warning: an architecture disagreement is a
  question for the reviewer, never a release block. Refines UN-017.
- **DI-70 (architecture views as images)** — `rdm c4 draw` exports the
  workspace (`dhf/c4/workspace.dsl`) with Structurizr's CLI: its model as
  `dhf/c4/workspace.json`, and each view as DOT, drawn by Graphviz to
  `dhf/c4/views/<view>.svg`. Each design document shows its view as an
  ordinary image, so GitHub, the docs site and a rendered PDF show the same
  picture, and no browser draws it. The drawn files are committed, each
  stamped with the SHA-256 of the workspace; the design gate fails when a
  stamp is not the current workspace's, when a view has no image, or when an
  image has no view, so a stale diagram cannot be committed. Checking needs
  only the stamps: Java and Graphviz are needed only to draw. Amended (Design
  Review 29): DI-70 rendered Mermaid diagrams with Mermaid's renderer.

The rest of `record`'s design is carried over in [specification](specification.md).

The rest of `graph`'s design is carried over in [graph](graph.md).

The rest of `rendering`'s design is carried over in [publishing](publishing.md).

## Components (C3)

The components of the `architecture` context, drawn from the architecture
workspace (`rdm c4 draw`).

![Components: architecture](../../c4/views/C3_architecture.svg)
