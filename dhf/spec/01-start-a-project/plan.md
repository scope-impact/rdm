# Slice 01: Start a project

status: building

## Goal

A team starts a new project, or brings an existing repository under rdm-spec, with one command, and every task it then runs answers the same way: one command, one task table, one way to report, a gate that stops the task.

## References

- UN-009 (`dhf/spec/needs.edn`)
- UN-002 (`dhf/spec/needs.edn`)
- UN-006 (`dhf/spec/needs.edn`)

## Scope

**In:**

- Init: a new project's skeleton, green before anything is written in it
- Adopt: the skeleton added to an existing repository, overwriting nothing, reading none of its code into the spec
- Hooks: every commit checked
- The command: one entry point, the task table, help, how every task reports and exits, a gate stopping a task, a project in one folder of a larger repository (until 11-project-in-a-folder holds it)

**Out:**

- What each task does: its own slice
- Plugins and the Gate request: 08-plugins

## Decisions

`decisions.md`, beside this plan.
