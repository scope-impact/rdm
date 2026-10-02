---
# One `kind: design` document per bounded context. Discovery keys on the
# frontmatter marker, never the filename — rename this file for your context.
id: SDS-CTX-001
kind: design
context: TODO-your-context-name
# Design inputs other contexts own that this one implements part of:
# realises: [DI-n]
design_inputs: []
# Scaffold inputs with `rdm story new-input --context <ctx> --text "..." --traces-to UN-…`
# Each entry: {id: DI-n, text: "the verifiable requirement", traces_to: [UN-…]}
---

# TODO-context — Software Design

## Purpose

TODO one paragraph: what this bounded context owns, and the language it
speaks — the glossary terms (CONTEXT.md) that mean something here. A context
is drawn where the language changes, not where a workflow stage does.

## Design Inputs

TODO one bullet per design input this context owns, in id order:

- **DI-n (a short name)** — what the requirement means, why it is scoped
  here, and what it deliberately leaves out. Refines UN-….

## Design Outputs

TODO the implementation that meets the inputs, by component (named as in the
architecture workspace): what each does and which inputs it meets. Name the
parts of other contexts' inputs this context realises (`realises`).

## Components (C3)

TODO the component view of this context, drawn from the architecture
workspace (`rdm c4 draw`):

![Components: TODO-context](../../c4/views/C3_TODO-context.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|

TODO the relationships that matter, in words: what each component uses and
why, in the direction of the arrow. Then the assumptions and open questions,
if any.

## Dependencies

TODO the contexts this one depends on, and those that depend on it, against
the dependency rule of the system architecture (a context imports only the
contexts below it). Name any import that breaks the rule and the change that
removes it.
