# Plugin templates

What rdm-spec carries for projects to start their own judges from
(dhf/decisions/adr-0003-core-and-plugins.md). A template is never a running
dependency: `rdm-spec plugin add NAME` copies it into a project's
`dhf/harness/plugins/NAME/`, writes `origin.edn` with the template and its
version, and prints the `:plugins` entry to add. From then on the copy is the
project's own, evolving with the project; `diff` against the template shows how.

A template joins here when a second project wants a starting point like it (the
rule of two). An idea a project's copy grew comes back by a person's proposal,
as a new version.

| Template | Version | What it starts | First used by |
|---|---|---|---|
| [ddd-domain](ddd-domain/) | 1 | a domain model held to the rules of domain-driven design | rdm-spec's core, until spec-25 |
| [affordances](affordances/) | 1 | an affordance contract, and a conformance check that holds a served design to it | Löyly, cycle 04 |
