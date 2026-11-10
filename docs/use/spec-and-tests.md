# The spec and its tests

What RDM reads as your requirements, and how a test claims one. RDM does not
author the spec: you, or a factory that writes and regenerates code from it
(such as [Löyly](https://github.com/scope-impact/loyly)), keep it, written the
Löyly way. RDM reads it, traces each requirement to the test runs
that verify it, and gates the release on that. The words are the
[glossary](../reference/glossary.md)'s; what the gates then check is
[The gates](gates.md).

## The model in one breath

The spec is written the Löyly way. A **requirement** is an accepted Gherkin
scenario, a concrete Given/When/Then example stamped with an id
(`@item-01-001`). A **user need** is a screen of the event model, linked to
the Allium surface that models it; a requirement addresses the needs whose
Allium rules or guarantees its tags name. A test **claims** a requirement
with an Allure `tag` label of the same id. Every entity and link is in
[the data model](../about/how-rdm-works.md#the-data-model).

## Where the spec lives

`rdm init` and `rdm adopt` lay down a skeleton in `.pi/` (adopt only when the
repository has no spec yet); RDM finds it there without configuration:

```
.pi/
  plan/
    01-alarms -> ../spec/alarms        one slice per bounded context, in order
  spec/
    event-storm.edn                    the board: the domain's events and their causes
    alarms/
      spec.feature                     the accepted scenarios: the requirements
      drafts.feature                   scenarios not yet accepted (not read)
      allium/alarms.allium             rules, actors, surfaces and their guarantees
      event-model/area.edn             screens (the user needs), commands, events
```

The slice number `NN` is the first part of every id in that context
(`@item-01-SSS`). A slice whose `plan.md` says `status: planned` or
`status: backlog` is skipped and listed. Elsewhere, name the plan in
`dhf/config.yml` (relative to the DHF): `spec: ../.pi/plan`.

## Requirements: stamped scenarios

```gherkin
Feature: Alarms
  The accepted scenarios of this context. Each is stamped with an item id
  that is never renumbered or reused.

  Rule: An alarm is raised within 2 s of a reading above the limit

    # alarms.allium, rule RaiseAlarm
    @allium-rule-success.RaiseAlarm
    @item-01-001
    Scenario: A reading above the limit raises an alarm within 2 s
      Given the limit for heart rate is 140
      When the monitor reads a heart rate of 150
      Then an alarm is raised within 2 s
```

- The last id tag before a `Scenario` is its id, `@item-NN-SSS` by default.
  Other tags are kept, never read as ids.
- RDM never assigns ids: a scenario with none is reported, and blocks the
  release, until whoever owns the spec stamps it. An id on two scenarios
  blocks too.
- `@allium-<kind>.<Name>` names what the scenario covers: a rule's success or
  failure (`rule-success.RaiseAlarm`, `rule-failure.RaiseAlarm.2`) or a
  guarantee (`guarantee.NeverSilenced`). That is how it addresses a need.
- The `Rule:` above a scenario, its title and its source line come along into
  the matrix and the graph.

## User needs: screens and surfaces

Each screen of a context's event model is a user need: its key the need's id
(`alarms/monitor`), its description the need's text.

```clojure
;; .pi/spec/alarms/event-model/area.edn
{:alarms
 {:screens
  {:alarms/monitor
   {:description "A nurse is alerted to a dangerous vital sign."
    :grain/allium [{:spec ".pi/spec/alarms/allium/alarms.allium" :kind :surface :name "Monitor"}]
    :commands #{:alarms/raise-alarm}}}}}
```

The surface it links says whom it faces, the rules it provides (by their
triggers) and what it guarantees:

```
-- A nurse is alerted to a dangerous vital sign.
surface Monitor {
    facing nurse: Nurse
    provides:
        RaiseAlarm(monitor, reading)
    @guarantee NeverSilenced
        -- An alarm is never silenced without a nurse acknowledging it.
}
```

A requirement tagged with one of those rules or guarantees addresses the
need; one tagged with none addresses its context's needs. The event-storm
events the screen's commands produce, caused by an actor, show with the need.
In a context with no screens, each surface is a need. The release gate
blocks a need no requirement addresses. Validation is recorded against the
need's id: [validation evidence](validation-evidence.md).

## Plain Gherkin

RDM's contract with a spec is only Gherkin and Allure (ADR 0002), so a spec
kept as plain feature files works too: without `.pi/plan`, RDM looks for
`spec/**/*.feature`, then `features/**/*.feature`; a requirement is any
scenario with an id-shaped tag (`@REQ-12`), a user need each `Feature` (its
description the need's text), and a context the directory a file sits in.
Anywhere else, name it in `dhf/config.yml`:

```yaml
spec:
  features: ["../acceptance/**/*.feature"]   # one or more globs
  exclude: ["**/drafts.feature"]             # optional
  id: "REQ-\\d+"                              # optional: the id tag's pattern, without "@"
  needs: feature                             # feature (the default), or allium
```

## Writing the test

A Gherkin runner (allure-pytest-bdd, allure-cucumber, allure-behave) writes
each scenario's tags as Allure `tag` labels, so a run already claims its id.
A plain test claims it with `@allure.tag`:

```python
import allure

@allure.tag("item-01-001")                    # the link the whole chain hangs on
@allure.label("output", "src/alarms.py")     # optional: the code it exercises
def test_alarm_within_two_seconds(device):
    """item-01-001: an alarm within 2 s of a reading above the limit."""
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
  but looks like one (`item_01_001`) is warned about; other labels are yours.
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
rule, runs and the risks it controls, ask the graph (`trace item-01-001` in
[`rdm graph mcp`](agents.md), or [`rdm graph query`](graph.md)). To render
the matrix, grouped by user need:

```bash
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```
