---
# One `kind: design` document per bounded context. Discovery keys on the
# frontmatter marker, never the filename — rename this file for your context.
id: SDS-CTX-001
kind: design
context: TODO-your-context-name
satisfies: []        # user needs this context contributes to, e.g. [UN-001]
design_inputs: []
# Scaffold inputs with `rdm story new-input --context <ctx> --text "..." --traces-to UN-…`
# Each entry: {id: DI-n, text: "the verifiable requirement", traces_to: [UN-…]}
---

# TODO-context — Software Design

## Design Inputs

TODO describe each design input this context owns: what the requirement means,
which user need it refines, and why it is scoped here.

## Design Outputs

TODO name the implementation (modules, components) that realises the inputs —
the "how" the tagged acceptance tests exercise.
