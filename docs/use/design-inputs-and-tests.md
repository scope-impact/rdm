# Design inputs and tests

How to write the record: declare a design input (an acceptance criterion),
write the test that verifies it, and let RDM label the test runs from the
record. The words are the [glossary](../reference/glossary.md)'s. The
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

```bash
rdm story new-input --dhf dhf --list        # contexts, taken DI ids, next free id, user needs
rdm story new-input --dhf dhf --context alarms \
  --text "The system shall …" --traces-to UN-001
```

Scaffolds a traced design input: allocates the next unused `DI-n`, inserts the
entry into the owning context's design document, writes a stub tagged test
that **fails until implemented**, and prints the remaining checklist. An
unknown context or user need is rejected. Write the text as verifiable
clauses; a design input too big for one test to name its failing clause
should be split.

## Writing the test

```python
import allure

@allure.story("DI-1")                         # the link the whole chain hangs on
@allure.label("component", "alarm_logic")    # the C4 component it exercises
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
- **Only the story names a design input.** Feature and epic are not tags you
  write (below).
- **Acceptance tests only.** Unit tests carry no Allure tags, steps or
  attachments, so nothing but an acceptance test can be counted as
  verification.
- **Other languages.** JS/TS `allure.story(...)` calls and Java `@Story(...)`
  annotations are read across conventional test-file names (`*.test.ts`,
  `*.spec.js`, `*Test.java`, `*_test.go`, …). There, the whole file is the
  test.

## Labels from the record

Allure's behaviors hierarchy is epic → feature → story; in RDM that is user
need → bounded context → design input. A test declares only the story.
`rdm.pytest_plugin` adds the rest at run time, from the record, with Allure's
own API (`allure.dynamic`), so the labels can never drift from it:

| Label | Value |
| --- | --- |
| `epic` | each user need the design input traces to |
| `feature` | the bounded context that owns it |
| `link` | each Markdown document that declares it, at the commit under test: its design document, the V&V plan, the risk document of each risk it controls. Links need a web remote (`origin` on GitHub, GitLab or Bitbucket) and a commit; without one, none is written |
| `severity` | `critical` when the design input controls a risk |
| `commit` | the commit under test, with `worktree=dirty` when the working tree had uncommitted changes |
| attachment `requirement DI-n` | the design input's text |

The run's `environment.properties` records `worktree=unknown`, never `clean`,
when there is no commit to compare against.

A test also says what it exercises, by `component`: a C4 component by its key
in the architecture workspace (`@allure.label("component", "release_gate")`;
a key the model does not declare is a warning). The key keeps the record
code-agnostic: a file can move without the test changing. An `output` label,
the file it runs (`@allure.label("output", "rdm/release/gate.py")`), is
optional and names the component holding that file. Either *names* the
component; the graph adds the components a named one *reaches* through the relationships the workspace
declares, so a test need not name every module behind its entry point.
Reaching is derived by a rule, not stored: `rdm graph build` and `query` add
it with `--infer`, and the agent server always does
([derived relations](graph.md#derived-relations-rules-not-facts)).
Neither is evidence of coverage: code coverage is the unit tests' measure
([gates](gates.md#unit-test-code-coverage-optional)), never an acceptance
test's.

Enable it for the acceptance suite only — import its hook in that suite's
`conftest.py`, or pass `-p rdm.pytest_plugin --rdm-dhf dhf`:

```python
# tests/acceptance/conftest.py
from rdm.pytest_plugin import pytest_runtest_call  # noqa: F401
```

## Running the acceptance tests

Run them into a clean results directory (`--clean-alluredir`, so an earlier
run's results never count as evidence), then the gates: the commands are
[the release gate's](gates.md#release-gate). To see one design input's need,
owner, tests and status, and to render the matrix:

```bash
rdm story trace DI-1 --dhf dhf
rdm render dhf/documents/traceability_matrix.md dhf/config.yml dhf/data/verification.yml
```

Validation, by people and by AI personas, is recorded against the user needs:
[validation evidence](validation-evidence.md).
