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
# This is each design input's only statement: the body never restates it.
---

# TODO-context — Software Design

<!-- Keep it agile: write what a reviewer needs to approve the change, no more.
     A section with nothing to say is one line ("None."), never padding. Edit
     this document in the same pull request as the change it describes. -->

## Purpose

TODO one paragraph: what this bounded context owns, and the language it
speaks — the glossary terms (CONTEXT.md) that mean something here. A context
is drawn where the language changes, not where a workflow stage does.

## Design Outputs

The design outputs are this context's architecture, in the C4 model of the
architecture workspace (`rdm c4 draw`): its components, what each is
responsible for, and how they relate. They name components, never source
files or functions — the workspace maps each component to its code.

![Components: TODO-context](../../c4/views/C3_TODO-context.svg)

| Component | Responsibility | Meets |
|-----------|----------------|-------|

TODO one row per component of this context: its responsibility, and the
design inputs it meets, by id. Then the relationships that matter, in words —
what each component uses and why, in the direction of the arrow — and the
parts of other contexts' inputs this context realises (`realises`). Then the
assumptions and open questions, if any.

### Dynamic view (only when needed)

Optional. Add one only when the order of interactions matters and the
component view cannot show it: one important runtime scenario, not one per
feature. Declare it in the workspace as `dynamic <container> "D_<context>_<scenario>"`,
each step along a relationship the model already declares, and draw it with
`rdm c4 draw`. Delete this section when there is none.

![Scenario: TODO-scenario](../../c4/views/D_TODO-context_TODO-scenario.svg)

## Commands and events

What this context does, in the record's domain language (`CONTEXT.md`,
"Domain model"): each row reads *the actor issues the command, resulting in
its success event or a fail event, which affects the entity*. A fail event
names the business rule broken, after a slash. Keep it to the commands a
reviewer needs.

| Actor | Command | Success event | Fail events | Entity |
|-------|---------|---------------|-------------|--------|

TODO one row per command. Then the reactions, if any: "whenever this event,
then that command".

## Dependencies

TODO the contexts this one depends on, and those that depend on it, against
the dependency rule of the system architecture (a context imports only the
contexts below it).
