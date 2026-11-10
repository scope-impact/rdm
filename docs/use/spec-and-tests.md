# The spec and its tests

What RDM reads as your requirements, and how a test claims one. RDM does not
author the spec: you, or a factory that writes and regenerates code from it,
keep it as Gherkin. RDM reads it, traces each requirement to the test runs
that verify it, and gates the release on that. The words are the
[glossary](../reference/glossary.md)'s; what the gates then check is
[The gates](gates.md).

## The model in one breath

The spec is Gherkin. A **requirement** is a scenario tagged with an id
(`@REQ-12`, `@alarm-3`, `@item-02-001`). A **user need** is, by default, the
`Feature` the scenario sits under, its description the need's text. A test
**claims** a requirement with an Allure `tag` label of the same id. Every
entity and link is in [the data model](../about/how-rdm-works.md#the-data-model).

## Where the spec lives

Without configuration RDM looks, from the repository root, for
`spec/**/*.feature`, then `features/**/*.feature`. A requirement's context is
the directory its file sits in below that root:

```
spec/
  alarms/
    sound.feature          context "alarms"
    quiet_hours.feature
  logging/
    audit.feature          context "logging"
```

Anywhere else, name it in `dhf/config.yml` (paths relative to the DHF):

```yaml
spec:
  features: ["../acceptance/**/*.feature"]   # one or more globs
  exclude: ["**/drafts.feature"]             # optional
  id: "REQ-\\d+"                              # optional: the id tag's pattern, without "@"
  needs: feature                             # feature (the default) or an adapter's
```

## Requirements: tagged scenarios

```gherkin
Feature: Alarms sound
  A nurse is alerted to a dangerous vital sign.

  Rule: An alarm is raised within 2 s of a reading above the limit

    @REQ-12 @smoke
    Scenario: A reading above the limit raises an alarm within 2 s
      Given ...
```

- The last id tag before a `Scenario` is its id. By default any tag shaped
  `<letters>-…-<number>` is an id; `id` in the configuration narrows it.
  Other tags (`@smoke`) are kept, never read as ids.
- RDM never assigns ids: a scenario with none is reported, and blocks the
  release, until you tag it. An id on two scenarios blocks too.
- The `Rule:` above a scenario, its title and its source line come along into
  the matrix and the graph.

## User needs: features

Each `Feature` is a user need: its title the need's id, the lines under it
its text, and the scenarios under it the requirements that address it. The
release gate blocks a need no requirement addresses. Validation is recorded
against the need's id: [validation evidence](validation-evidence.md).

## Writing the test

A Gherkin runner (allure-pytest-bdd, allure-cucumber, allure-behave) writes
each scenario's tags as Allure `tag` labels, so a run already claims its id.
A plain test claims it with `@allure.tag`:

```python
import allure

@allure.tag("REQ-12")                         # the link the whole chain hangs on
@allure.label("output", "src/alarms.py")     # optional: the code it exercises
def test_alarm_within_two_seconds(device):
    """REQ-12: an alarm within 2 s of a reading above the limit."""
    with allure.step("a reading above the limit raises an alarm"):
        alarm = device.read(150)
        allure.attach(repr(alarm), name="alarm")   # what the assertion looked at
        assert alarm.raised
    with allure.step("within 2 s"):
        assert alarm.latency_s <= 2
```

- **Check only what the scenario states**, one verification step for each
  thing it requires, so a failure says what it was checking.
- **Attach what you checked**, so the evidence shows more than "passed".
- **Only `tag` labels claim a requirement.** A tag that names no requirement
  but looks like one (`req_12`) is warned about; other labels are yours.
- **Acceptance tests only.** Unit tests carry no Allure tags, steps or
  attachments, so nothing but an acceptance test counts as verification.

## The commit under test

`rdm.pytest_plugin` adds, at run time, a `commit` label to each run of a test
that claims a requirement of the spec: the commit under test, with
`worktree=dirty` when the working tree had uncommitted changes. Once per run
it also writes Allure's `executor.json` and `environment.properties` (who or
what ran the tests, and where). `environment.properties` records
`worktree=unknown`, never `clean`, when there is no commit to compare against.

Enable the plugin for the acceptance suite only — import its hook in that
suite's `conftest.py`, or pass `-p rdm.pytest_plugin --rdm-dhf dhf`:

```python
# tests/acceptance/conftest.py
from rdm.pytest_plugin import pytest_runtest_call  # noqa: F401
```

## Running the acceptance tests

Run them into a clean results directory (`--clean-alluredir`, so an earlier
run's results never count as evidence), then the gates: the commands are
[the release gate's](gates.md#release-gate). To see one requirement's need,
rule, runs and the risks it controls, ask the graph (`trace REQ-12` in
[`rdm graph mcp`](agents.md), or [`rdm graph query`](graph.md)). To render
the matrix, grouped by user need:

```bash
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

## Adapters: Löyly's layout

A spec written by a factory can carry more than Gherkin. RDM reads one such
layout through an optional adapter, [Löyly](https://github.com/scope-impact/loyly)'s:
a plan directory `.pi/plan/NN-<context>` (found at the repository root
without configuration, or named with `spec: {layout: loyly, plan: <dir>}`),
each slice's `spec.feature` stamped `@item-NN-SSS`, slices marked `planned`
or `backlog` in their `plan.md` skipped, `drafts.feature` never read.

With `needs: allium` (the default for that layout), a user need is a screen
of the slice's event model (`event-model/*.edn`) with the Allium surfaces it
links (`allium/*.allium`: the actor each faces, the rules it provides, its
guarantees) and the event-storm events its commands produce; a context with
no screens offers each surface. A scenario addresses the needs whose rules or
guarantees its `@allium-<kind>.<Name>` tags cover, else its context's needs.
Nothing else in RDM depends on this adapter.
