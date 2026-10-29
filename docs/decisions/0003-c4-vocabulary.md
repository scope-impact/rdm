# 0003: The model reuses the C4 vocabulary, and the stack lives where C4 puts it

> Status: Accepted, 2026-10-29, by the spec owner (Sathish Narayanan): "I
> don't want to invent schema. C4 has proper vocabulary for stack
> (deployment, container). I want to reuse."

## Context

`spec/model.toml` had its own keys (`container`, `context`, `runs`), and the
firewall (INV-2) needed a hand-written list of stack words that no builder
could guess (spec gap 13 of the audit). Both were invented where a
vocabulary already exists: the C4 model's abstractions and the Structurizr
DSL's names for them (`raw/c4/`).

## Decision

- **The model's vocabulary is C4's, by the DSL's names.** `softwareSystem`;
  `containers.<name>` with `description`, `technology`, `tags`;
  `containers.<name>.components.<name>` with the same plus `group` (the
  bounded context, C4's grouping); `[[relationships]]` with `from`, `to`,
  `description`, `technology`; `deploymentEnvironment.<name>` holding nested
  `deploymentNode.<name>` (with `technology`, `instances`),
  `infrastructureNode.<name>`, and `containerInstance = [...]` naming
  containers (`raw/c4/structurizr/dsl-language.md`, the sections named
  after each keyword; `raw/c4/c4model.com/abstractions.md`).
- **The harness adds three keys and no more:** `owns` (the storm elements a
  component owns), `spec` (its feature file), `event` (the storm event a
  relationship carries). Any other key is refused (`model-vocabulary`). A
  component is named `<container>/<component>`.
- **The stack lives in `technology`.** On a container, a component, a
  deployment node, an infrastructure node or a relationship, as the DSL
  puts it (`raw/c4/structurizr/dsl-language.md`, "technology"). That is
  the model's business and the firewall's exemption.
- **The firewall reads the model's technologies.** A feature file or a
  storm that names a declared technology is refused (`no-stack-words`,
  naming the technology and where it is declared). The match is by each
  word of the technology that is not a common word, whole and regardless
  of case; the common-word list (`harness/common-words.toml`, declared
  shared) is the one list that is not derived, because "a web application"
  must not forbid "application". There is no other stack-word list.
- **A container is C4's container**: an application or a data store, a
  runtime boundary, not Docker, with deployment a separate concern
  (`raw/c4/c4model.com/abstractions-container.md`). A component is C4's:
  related functionality behind an interface, in its container's process
  space, not separately deployable
  (`raw/c4/c4model.com/abstractions-component.md`). The glossary says so.

## Consequences

- Spec gaps 12 (`runs`) and 13 (the word list) are closed by reuse rather
  than by invention; gap 10 (an unknown component in a relationship) and
  the model-coverage gaps (7, 8, 9) are stated in the same rewrite of the
  model's rule.
- A product's model can be exported to a Structurizr workspace without
  translation of names; that export is a plan of its own, not the core.
- A deployment environment is where a monitor's results will one day be
  read against (the way of working, "Where a monitor's results go"): the
  model already names the environments, so nothing is invented for that
  either.
