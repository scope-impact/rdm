---
id: SDS-EVID-001
kind: design
context: test_evidence
# Implements part of inputs other contexts own.
realises: [DI-4]
design_inputs:
  - id: DI-17
    text: "RDM shall translate external test-result formats (gtest/xunit/qttest XML) into RDM's result data, and reject an unknown format."
    traces_to: [UN-001]
  - id: DI-34
    text: "RDM shall provide a mutation probe for reviewers that runs a test once unmutated — reporting an error, never a result, when it does not pass — then applies a one-line source mutation, runs the test again, and reports killed or survived, counting only a genuine test failure as a kill; the probe never gates a release."
    traces_to: [UN-013]
  - id: DI-47
    text: "The mutation probe shall always restore the file it mutated: it journals the original beside the file so an interrupted probe is recovered on the next probe of that file, restores on a termination signal, and invalidates the bytecode cache on every write."
    traces_to: [UN-013]
  - id: DI-57
    text: "RDM shall provide a pytest plugin that, for each test tagged with a design input's story, labels the run from the record at test time — the input's user needs as Allure epics, its bounded context as the feature, links to the Markdown documents that declare it at the tested commit (its design document, the V&V plan for its user needs, and the risk document of each risk it controls), critical severity when it controls a risk — and attaches the input's text."
    traces_to: [UN-004, UN-010]
  - id: DI-59
    text: "rdm.pytest_plugin shall label each run of a test tagged with a declared design input with the commit under test, and mark the run when the working tree had uncommitted changes."
    traces_to: [UN-004, UN-003]
  - id: DI-65
    text: "rdm.pytest_plugin shall record, in the Allure results of an acceptance run, who or what ran the tests and where: Allure's executor.json naming the CI system, the run and its URL, or a local run with its user and host; and environment.properties with the operating system, the Python, pytest, allure-pytest and RDM versions, the commit under test, whether the worktree had uncommitted changes, and the CI actor and workflow."
    traces_to: [UN-004, UN-012]
---

# Test evidence — Software Design

> **Interim (Design Review 30).** This context was formed from `ingestion`, `verification`. Its
> design inputs moved here unchanged; the prose below is carried over verbatim
> from those documents, by section, until it is rewritten for this context.

## Design Inputs

Takes test results in the formats tools write them (Allure, xunit) and translates them into test runs of the record's tests, labelled from the record at test time; and the reviewer's mutation probe.

- **DI-17 (foreign test-result translation)** — translate gtest/xunit/qttest XML
  into RDM's result data; reject an unknown format.
- **DI-34 (mutation probe, reviewer tool)** — `rdm story mutation-probe
  --file F --find A --replace B --test T` breaks one line on purpose, runs one
  test, and reports KILLED (the test caught it) or SURVIVED (it did not). It is
  how a pull-request reviewer turns "this test would catch a broken X" from a
  claim into an executed check. Only a genuine test failure is a kill; a run
  that errors or collects nothing is an error, so a typo'd selector cannot
  manufacture evidence. The file is always restored, defended in depth: the
  original is journaled to a sidecar first (recovered on the next probe of the
  file, even after SIGKILL), SIGTERM restores in-process, and every write
  advances the mtime to a fresh whole second so CPython never runs stale
  bytecode for a same-size mutant. It records nothing and gates nothing — the
  reviewer's judgment, on the pull request, is the record. Restored from the
  retired DI-21 without its verdict coupling. Refines UN-013.
- **DI-47 (the probe always restores)** — split from DI-34 (Design Review
  12) so the restore guarantees have their own test: the original is
  journaled beside the file, an interrupted probe is recovered on the next
  probe of that file, a termination signal restores, and every write
  invalidates the bytecode cache. Refines UN-013.
- **DI-57 (the Allure hierarchy, from the record)** — Allure organises
  results as epic → feature → story; RDM's record is user need → bounded
  context → design input. A test carries one hand-written tag,
  `@allure.story("DI-n")`; `rdm.pytest_plugin` (enabled with
  `-p rdm.pytest_plugin`, or its hook imported in the acceptance conftest;
  record at `--rdm-dhf`, default `dhf`) adds the rest at run time with Allure's dynamic API — epics,
  feature, links to the Markdown that declares it at the tested commit (the
  design document, the V&V plan for its user needs, the risk document of each
  risk it controls), critical severity for an input that controls a risk, and the requirement text as an
  attachment — so the labels cannot drift from the record. Refines UN-004
  and UN-010.
- **DI-59 (the commit under test)** — the plugin labels each run of a tagged
  test with the commit under test (`commit`) and, when the working tree had
  uncommitted changes, `worktree=dirty`, so the evidence says which version
  it is evidence for (the graph side is DI-60). Refines UN-004 and UN-003.
- **DI-65 (who ran the tests, and where)** — IEC 62304 §9.8 asks a test
  record for the configuration, the tools and the identity of the tester.
  `rdm.pytest_plugin` writes Allure's own `executor.json` (on GitHub Actions:
  the workflow run, its number and URL; locally: the user and host) and
  `environment.properties` (operating system; Python, pytest, allure-pytest
  and RDM versions; the commit under test and the worktree state; the CI actor
  and workflow) into the results directory once per run, so the Allure report
  shows them and the verification report reads them. Refines UN-004 and
  UN-012.

The rest of `verification`'s design is carried over in [release](release.md).

## Design Inputs (context notes) — from `ingestion`

This context owns the requirements for pulling *external source artifacts* into
the documentation pipeline (distinct from the `record` context, which ingests the
DHF's own frontmatter/Allure/git). Both refine UN-001 (compile the DHF from the
system of record — code and executed tests are part of that record):

## Design Outputs — from `ingestion`

`rdm collect` (`rdm/collect.py`) and `rdm translate` (`rdm/translate.py`):

- `collect_from_lines` / `collect_from_files` — snippet extraction keyed by the
  `RDOC` marker.
- `translate_test_results(format, input, output)` — dispatch over
  `XML_TRANSLATORS` (gtest, xunit, qttest, auto) → flattened results → YAML;
  unknown format raises `ValueError`.

Acceptance criteria are verified by `@allure.story("DI-16" / "DI-17")` tests;
`collect_test.py` and `test_xml_util.py` remain as lower-level coverage.

## Out of scope — from `ingestion`

Planning data (issues, pull requests, task boards) is not part of the
controlled record, and RDM no longer pulls it (Design Review 11).

## Components (C3)

The components of the `test_evidence` context, drawn from the architecture
workspace (`rdm c4 draw`).

![Components: test_evidence](../../c4/views/C3_test_evidence.svg)
