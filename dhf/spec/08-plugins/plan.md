# Slice 08: Plugins

status: done

## Goal

A team adds the judges its own project needs to the harness without changing the harness, through the core's published language.

## References

- UN-015 (`dhf/spec/needs.edn`)
- UN-005 (`dhf/spec/needs.edn`)
- UN-008 (`dhf/spec/needs.edn`)
- `dhf/decisions/adr-0003-core-and-plugins.md`

## Scope

**In:**

- Plugins: declared in the configuration, subscribers and answerers (Gate, Check), one interface, templates added and then owned, :env, :CycleVerified
- The bus: rdm-spec's own events, shown and never varying
- The oracle task: the plugin named "oracle", and nothing fenced or verified while it is red

**Out:**

- What any one plugin judges: the project's own

## Decisions

`decisions.md`, beside this plan.
