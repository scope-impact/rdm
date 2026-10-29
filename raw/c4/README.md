# raw/c4: the architecture's vocabulary

The spec owner (decision 0003): the model invents no schema; it reuses the
C4 model's abstractions and the Structurizr DSL's names for them, including
where the stack lives (a container's, component's, deployment node's and
infrastructure node's `technology`). These are the sources, fetched
2026-10-29 as text from their pages and never edited.

| Path | From | Pinned at | What it is |
|---|---|---|---|
| `c4model.com/abstractions.md` | https://c4model.com/abstractions | fetched 2026-10-29 | software system, container, component, code, person: the abstractions and how they nest |
| `c4model.com/abstractions-software-system.md` | https://c4model.com/abstractions/software-system | fetched 2026-10-29 | the software system |
| `c4model.com/abstractions-container.md` | https://c4model.com/abstractions/container | fetched 2026-10-29 | the container: an application or a data store, a runtime boundary; not Docker; deployment a separate concern |
| `c4model.com/abstractions-component.md` | https://c4model.com/abstractions/component | fetched 2026-10-29 | the component: related functionality behind an interface, inside one container's process space, not separately deployable |
| `c4model.com/diagrams-deployment.md` | https://c4model.com/diagrams/deployment | fetched 2026-10-29 | the deployment diagram: deployment environments, deployment nodes (nested), infrastructure nodes, software system and container instances |
| `structurizr/dsl-language.md` | https://docs.structurizr.com/dsl/language | fetched 2026-10-29 | the DSL's grammar for each element: `softwareSystem`, `container`, `component`, `deploymentEnvironment`, `deploymentNode`, `infrastructureNode`, `containerInstance`, `relationship`, `technology` |

The pages are not under version control upstream, so they are pinned by
fetch date; a later fetch that differs is a new row, never an edit.
