---
id: SDS-EVID-001
kind: design
context: test_evidence
---

# Test evidence — Software Design

## Purpose

This context takes test results in the formats tools write them and turns
them into evidence about the record's tests. Its language is the **test
run** (one execution of a test, at one commit, by one executor), the
**executor** (the CI job or the machine that ran it), the **step** (a
verification step, with its own result) and the **attachment** (what a run
kept). It is a translation layer: Allure's words (result, label, tag)
are read and written here and stop at its boundary. It labels each run of
a tagged test with the commit under test, and reads the results back into
a status per requirement.

## Design Outputs

| Component | Responsibility | Meets |
|-----------|----------------|-------|
| pytest plugin | Records each run's commit and worktree state (unknown when there is no commit), executor and environment | item-03-002, item-03-003 |
| Allure reader | The labels and run facts in Allure's terms, written for the plugin and read back as test runs; a status per requirement | item-03-001, item-03-002, item-03-003; realises item-04-002 |

**pytest plugin.** It runs inside the acceptance test run, not in `rdm`.
It acts only on a test whose item tag (`@allure.tag("item-NN-SSS")`) is a requirement of the spec
declares; a tag that names no declared input gets nothing. For such a test
it labels the run with the commit under test and the worktree state; it
adds no requirement labels of its own, since the item tag is the trace. It records
the executor and environment once per session, and only when the run
writes Allure results; a CI run is named with its run and URL, a local run
with its user and host.

**Allure reader.** It holds the one vocabulary of labels and result files
that the plugin writes and every reader reads back, so the tools' words
stop here. It reads each result as a test run (its name, result, the
requirements it names, and the outputs it labels) and matches a run to
the test it came from. A run whose step failed or broke failed, whatever
its own status says. A result it cannot read is named, never skipped: not
JSON, nested too deep, a symbolic link, a status Allure does not write, or
labels that are not a list of name and value text. Any of these could hold
a failed run. Its reconciliation gives each declared design
input *failed* when any of its runs failed or broke, else *verified* when
any passed, else *untested*; an item tag that names no requirement is an
orphan. What a test *claims* to verify (its tags) is the specification's;
this reader handles only what a run *did*.

The relationships that matter, in the direction of the arrow: the pytest
plugin *reads the requirements with* the spec reader and
*writes run labels and facts with* the Allure reader. The Allure reader
*reads the item tags of each run*. Inward, the release gate, the verification data, the
verification report and the projection read results with the Allure
reader.

Assumption: the acceptance suite enables the plugin, either as a pytest
plugin or through its configuration; a run without it is unlabelled and
counts for no requirement.

## Dependencies

Layer 3 of the dependency rule. Depends on `specification` (the pytest
plugin reads the requirements) and the shared kernel, both below it. Depended on by `release` (the release gate, the verification
data), `publishing` (the verification report) and `graph` (the
projection). No import breaks the rule.

## Out of scope

- Planning data (issues, pull requests, task boards) is not part of the
  controlled record, and RDM no longer pulls it.
- The mutation probe, and unit tests' code coverage per component.
