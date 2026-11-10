---
id: SDS-EVID-001
kind: design
context: test_evidence
# Implements part of inputs other contexts own.
realises: [DI-4, DI-31, DI-40, DI-61]
design_inputs:
  - id: DI-17
    text: "RDM shall translate external test-result formats (gtest/xunit/qttest XML) into RDM's result data, and reject an unknown format or a file that holds no test results."
    traces_to: [UN-001]
  - id: DI-72
    text: "RDM shall read Allure results so that a result it cannot read is never counted as a pass: a file that is not JSON or not an object, not UTF-8, a symbolic link, nested too deep, with a status Allure does not write or labels that are not name and value text blocks release and is named by verify; a byte-order mark is read; a run whose step failed or broke has failed; and a run tagged with an id no design input declares is warned about."
    traces_to: [UN-004, UN-003]
  - id: DI-59
    text: "rdm.pytest_plugin shall label each run of a test tagged with a declared design input with the commit under test, and mark the run when the working tree had uncommitted changes."
    traces_to: [UN-004, UN-003]
  - id: DI-65
    text: "rdm.pytest_plugin shall record, in the Allure results of an acceptance run, who or what ran the tests and where: Allure's executor.json naming the CI system, the run and its URL, or a local run with its user and host; and environment.properties with the operating system, the Python, pytest, allure-pytest and RDM versions, the commit under test, whether the worktree had uncommitted changes (unknown when the commit is), and the CI actor, workflow, event and runner (the user, for a local run)."
    traces_to: [UN-004, UN-012]
---

# Test evidence — Software Design

## Purpose

This context takes test results in the formats tools write them and turns
them into evidence about the record's tests. Its language is the **test
run** (one execution of a test, at one commit, by one executor), the
**executor** (the CI job or the machine that ran it), the **step** (a
verification step, with its own result) and the **attachment** (what a run
kept). It is a translation layer: Allure's words (result, label, story)
and xunit's (test suite, test case) are read and written here and stop at
its boundary. It labels each run of a tagged test with the commit under
test, reads the results back into a status per design input, and
translates foreign XML results into result data.

## Design Outputs

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| pytest plugin | Records each run's commit and worktree state (unknown when there is no commit), executor and environment | DI-59, DI-65 |
| Allure reader | The labels and run facts in Allure's terms, written for the plugin and read back as test runs; a status per design input | DI-72, DI-59, DI-65; realises DI-4 |
| Test result translation | Translates a foreign XML result file into result data; refuses an unknown format | DI-17 |
| Result formatters | Flattens gtest, xunit and qttest XML into one result per test | DI-17 |

**pytest plugin.** It runs inside the acceptance test run, not in `rdm`.
It acts only on a test whose story tag is a design input the record
declares; a tag that names no declared input gets nothing. For such a test
it labels the run with the commit under test and the worktree state; it
adds no requirement labels of its own, since the story tag is the trace
(Design Review 72). It records
the executor and environment once per session, and only when the run
writes Allure results; a CI run is named with its run and URL, a local run
with its user and host.

**Allure reader.** It holds the one vocabulary of labels and result files
that the plugin writes and every reader reads back, so the tools' words
stop here. It reads each result as a test run (its name, result, the
design inputs it names, and the outputs it labels) and matches a run to
the test it came from. A run whose step failed or broke failed, whatever
its own status says. A result it cannot read is named, never skipped: not
JSON, nested too deep, a symbolic link, a status Allure does not write, or
labels that are not a list of name and value text. Any of these could hold
a failed run. Its reconciliation gives each declared design
input *failed* when any of its runs failed or broke, else *verified* when
any passed, else *untested*; a story that names no declared input is an
orphan. What a test *claims* to verify (its tags) is the specification's;
this reader handles only what a run *did*.

**Test result translation** (`rdm translate`) chooses a format, or detects
it, and writes each test's name, result and failure message as result
data; the gtest flattener also reads xunit, keying each test by its class
name, so tests of one name in different modules stay apart. A test that
failed or errored is a fail, with its failure's message or else its text;
one skipped or disabled a skip, and only the rest a pass; a qttest function
fails when any of its incidents does, and is a skip when it was skipped
with a message (Qt5) or an incident (Qt6). A file with no test results, or
whose results flatten to no test, is refused, and every refusal is a
message, exit 2. The data feeds document
templates; nothing reconciles it against design inputs.

The relationships that matter, in the direction of the arrow: the pytest
plugin *reads the declared design inputs with* the record reader and
*writes run labels and facts with* the Allure reader. The Allure reader
*takes the label that names a design input from* the test tags. Test
result translation *parses with* the result formatters. Inward, the release gate, the verification data, the
verification report and the projection read results with the Allure
reader.

Realised here: the Allure reader's reconciliation is this context's part
of **DI-4** (owned by `release`), which builds the verification data on
it. The labels and run facts written here are read back for DI-60 (graph)
and DI-64 (verification report). No other context realises part of this
context's inputs.

Assumption: the acceptance suite enables the plugin, either as a pytest
plugin or through its configuration; a run without it is unlabelled and
counts for no design input.

## Commands and events

Each row: the actor issues the command, resulting in its success event or a
fail event (the rule broken, after the slash), which affects the entity.

| Actor | Command | Success event | Fail events | Entity |
|-------|---------|---------------|-------------|--------|
| Contributor | Translate results | Results Translated | Not Translated / Unknown Format | Test result |

Reactions: whenever a tagged acceptance test runs, its result is labelled
with the commit under test.

## Dependencies

Layer 3 of the dependency rule. Depends on `specification` (the pytest
plugin reads the declared design inputs; the Allure reader takes the story
label from the test tags) and the shared kernel, both below it. Depended on by `release` (the release gate, the verification
data), `publishing` (the verification report) and `graph` (the
projection). No import breaks the rule.

## Out of scope

- Planning data (issues, pull requests, task boards) is not part of the
  controlled record, and RDM no longer pulls it (Design Review 11).
- The mutation probe, and unit tests' code coverage per component: removed
  in Design Review 72.
