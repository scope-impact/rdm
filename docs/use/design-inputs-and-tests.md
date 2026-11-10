# Design inputs and tests

How to write the record: declare a design input (an acceptance criterion),
write the test that verifies it, and let RDM tie each run to the commit it
tested. The words are the [glossary](../reference/glossary.md)'s. The
step-by-step change procedure is [Changing the record](changing-the-record.md);
what the gates then check is [The gates](gates.md).

## The model in one breath

A user need (`UN-nnn`, in the V&V plan) is refined by design inputs (`DI-n`,
in the design document of the bounded context that owns them); each design
input is verified by an acceptance test tagged `@allure.story("DI-n")`. Every
entity and link is in [the data model](../about/how-rdm-works.md#the-data-model).

```yaml
# dhf/documents/verification_and_validation_plan.md
user_needs:
  - {id: UN-001, text: "A clinician is alerted to a deteriorating patient."}
```

```yaml
# dhf/documents/design/alarms.md
kind: design
context: alarms
design_inputs:
  - id: DI-1
    text: "The system shall raise an alarm within 2 s of a reading above the limit."
    traces_to: [UN-001]
```

## Declaring a design input

Add an entry to the `design_inputs:` list of the design document of the
context that owns it, by hand, in the same pull request as its tagged test.
Give it the next unused `DI-n`, state it as one verifiable `shall`, and list
the user needs it `traces_to`. A design input too big for one test to name
its failing clause should be split.

## Writing the test

```python
import allure

@allure.story("DI-1")                         # the link the whole chain hangs on
@allure.label("output", "src/alarms.py")     # optional: the code it exercises
def test_alarm_within_two_seconds(device):
    """DI-1: an alarm within 2 s of a reading above the limit."""
    with allure.step("a reading above the limit raises an alarm"):
        alarm = device.read(150)
        allure.attach(repr(alarm), name="alarm")   # what the assertion looked at
        assert alarm.raised
    with allure.step("within 2 s"):
        assert alarm.latency_s <= 2
```

- **One verification step for each thing the design input requires**
  (`with allure.step("…")`), so a failure says what it was checking. The
  steps belong to the test, not to the acceptance criterion; if a part must be
  accepted on its own, make it a separate design input.
- **Attach what you checked**, so the evidence shows more than "passed".
- **Only the story names a design input.** Epic, feature and other labels
  are yours to use as you like; RDM does not read them.
- **Acceptance tests only.** Unit tests carry no Allure tags, steps or
  attachments, so nothing but an acceptance test can be counted as
  verification.
- **Other languages.** JS/TS `allure.story(...)` calls and Java `@Story(...)`
  annotations are read across conventional test-file names (`*.test.ts`,
  `*.spec.js`, `*Test.java`, `*_test.go`, …). There, the whole file is the
  test.

## The commit under test

`rdm.pytest_plugin` adds, at run time, a `commit` label to each run of a test
tagged with a declared design input: the commit under test, with
`worktree=dirty` when the working tree had uncommitted changes. Once per run
it also writes Allure's `executor.json` and `environment.properties` (who or
what ran the tests, and where). The run's `environment.properties` records
`worktree=unknown`, never `clean`, when there is no commit to compare against.

An `output` label, the file a test runs
(`@allure.label("output", "rdm/release/gate.py")`), is optional: the graph
links the run to that source file. It is not evidence of coverage.

Enable the plugin for the acceptance suite only — import its hook in that
suite's `conftest.py`, or pass `-p rdm.pytest_plugin --rdm-dhf dhf`:

```python
# tests/acceptance/conftest.py
from rdm.pytest_plugin import pytest_runtest_call  # noqa: F401
```

## Running the acceptance tests

Run them into a clean results directory (`--clean-alluredir`, so an earlier
run's results never count as evidence), then the gates: the commands are
[the release gate's](gates.md#release-gate). To see one design input's need,
owner, tests and status, ask the graph (`trace DI-1` in
[`rdm graph mcp`](agents.md), or [`rdm graph query`](graph.md)). To render
the matrix:

```bash
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

Validation is recorded against the user needs:
[validation evidence](validation-evidence.md).
