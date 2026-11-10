# The spec and its tests

What RDM reads as your requirements, and how a test claims one. RDM does not
author the spec: a factory such as [Löyly](https://github.com/scope-impact/loyly)
writes it, stamps it and regenerates code from it. RDM reads it, traces each
requirement to the test runs that verify it, and gates the release on that.
The words are the [glossary](../reference/glossary.md)'s; what the gates then
check is [The gates](gates.md).

## The model in one breath

The spec is one bounded context per slice of a plan. A **requirement** is a
stamped Gherkin scenario, `item-NN-SSS` (the slice, then the scenario). A
**user need** is a screen of the event model with the Allium surfaces it
links: who it faces, the rules it provides, what it guarantees. A requirement
**addresses** the needs whose rules or guarantees it covers. A test **claims**
a requirement with an Allure tag of the same id. Every entity and link is in
[the data model](../about/how-rdm-works.md#the-data-model).

## Where the spec lives

Löyly's layout, read as Löyly's own checklist reads it:

```
.pi/
  plan/
    01-alarms -> ../spec/alarms          one slice per context, in number order
  spec/
    event-storm.edn                      the Big Picture board (optional)
    alarms/
      spec.feature                       the accepted, stamped scenarios
      drafts.feature                     never read
      allium/*.allium                    rules, actors, surfaces
      event-model/*.edn                  commands, events, screens (optional)
      plan.md                            "status: planned" or "backlog" skips the slice
```

RDM finds `.pi/plan` beside your `dhf/` or in a parent directory, up to the
repository root. To read a spec somewhere else (a submodule, say), name its
plan directory in `dhf/config.yml`, relative to the DHF:

```yaml
spec: ../loyly/.pi/plan
```

## Requirements: stamped scenarios

```gherkin
Feature: Alarms

  Rule: An alarm is raised within 2 s of a reading above the limit

    @allium-rule-success.RaiseAlarm @allium-guarantee.PromptAlarm
    @item-01-001
    Scenario: A reading above the limit raises an alarm within 2 s
      Given ...
```

- The last `@item-NN-SSS` tag before a `Scenario` is its id. RDM never stamps:
  a scenario with no id is reported, and blocks the release, until the
  factory's stamper stamps it. An id on two scenarios blocks too.
- The `Rule:` above a scenario, its title and its source line come along into
  the matrix and the graph.
- Its `@allium-<kind>.<Name>` tags name the Allium obligations it covers; they
  decide which user needs it addresses.

## User needs: screens and surfaces

```clojure
;; alarms/event-model/area.edn
{:alarms {:screens {:alarms/ward-board
                    {:description "A nurse sees every alarm on the ward, newest first."
                     :grain/allium [{:spec "..." :kind :surface :name "WardBoard"}]
                     :commands #{:alarms/acknowledge-alarm}}}}}
```

```
-- alarms/allium/alarms.allium
surface WardBoard {
    facing nurse: Nurse
    provides:
        AcknowledgeAlarm(nurse, alarm_id)
    @guarantee PromptAlarm
}
```

Each screen is a user need, with the surfaces it links, the actors they face,
the rules whose triggers they provide and their guarantees, and the
event-storm events its commands produce. A context with no event model offers
each surface as a need, its `--` comment above it as the need's text. A
requirement addresses a need whose rules or guarantees it covers; one that
covers none (a system-facing seam, an invariant) is listed under its own
context's needs. The release gate blocks a need no requirement addresses
directly.

## Writing the test

A Gherkin runner (allure-pytest-bdd, allure-cucumber, allure-behave) writes
each scenario's tags as Allure `tag` labels, so a run already claims its
`item-NN-SSS`. A plain test claims it with `@allure.tag`:

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
- **Only `tag` labels of the form `item-NN-SSS` claim a requirement.** Other
  labels are yours; RDM does not read them.
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
[the release gate's](gates.md#release-gate). To see one requirement's needs,
rule, runs and the risks it controls, ask the graph (`trace item-01-001` in
[`rdm graph mcp`](agents.md), or [`rdm graph query`](graph.md)). To render
the matrix, grouped by user need:

```bash
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

Validation is recorded against the user needs:
[validation evidence](validation-evidence.md).
