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

## Purpose

This context takes test results in the formats tools write them and turns
them into evidence about the record's tests. Its language is the **test
run** (one execution of a test, at one commit, by one executor), the
**executor** (the CI job or the machine that ran it), the **step** (a
verification step, with its own result) and the **attachment** (what a run
kept, such as its copy of the requirement). It is a translation layer:
Allure's words (result, label, epic, feature, story) and xunit's (test
suite, test case) are read and written here and stop at its boundary. It
labels each run of a tagged test from the record at test time, reads the
results back into a status per design input, translates foreign XML
results into result data, and gives the reviewer the mutation probe.

## Design Inputs

- **DI-17 (foreign test-result translation)** — translate gtest, xunit and
  qttest XML into RDM's result data; reject an unknown format. A
  translated result names a test suite and case, not a design input: it is
  data for a document, not a test run that verifies an input. Refines
  UN-001.
- **DI-34 (mutation probe, reviewer tool)** — `rdm story mutation-probe
  --file F --find A --replace B --test T` breaks one line on purpose, runs
  one test, and reports KILLED (the test caught it) or SURVIVED (it did
  not): how a pull-request reviewer turns "this test would catch a broken
  X" from a claim into an executed check. The test runs unmutated first,
  and must pass. Only a genuine test failure is a kill; a run that errors
  or collects nothing is an error, so a mistyped selector cannot
  manufacture evidence. It records nothing and gates nothing — the
  reviewer's judgment, on the pull request, is the record. Restored from
  the retired DI-21 without its verdict coupling. Refines UN-013.
- **DI-47 (the probe always restores)** — split from DI-34 (Design Review
  12) so the restore guarantees have their own test: the original is
  journaled beside the file, an interrupted probe is recovered on the next
  probe of that file, a termination signal restores, and every write
  invalidates the bytecode cache. Refines UN-013.
- **DI-57 (the Allure hierarchy, from the record)** — Allure organises
  results as epic → feature → story; the record is user need → bounded
  context → design input. A test carries one hand-written tag,
  `@allure.story("DI-n")` (the specification's); the pytest plugin adds the
  rest at run time from the record, so the labels cannot drift from it.
  Refines UN-004 and UN-010.
- **DI-59 (the commit under test)** — the plugin labels each run of a
  tagged test with `commit` and, with uncommitted changes,
  `worktree=dirty`, so the evidence says which version it is evidence for.
  The graph side is DI-60, owned by the knowledge graph. Refines UN-004 and
  UN-003.
- **DI-65 (who ran the tests, and where)** — IEC 62304 §9.8 asks a test
  record for the configuration, the tools and the identity of the tester.
  The plugin writes Allure's own `executor.json` and
  `environment.properties` into the results directory once per run, so the
  Allure report shows them and the verification report reads them. Refines
  UN-004 and UN-012.

## Design Outputs

- **pytest plugin** (`rdm/pytest_plugin.py`) — DI-57, DI-59, DI-65.
  Enabled with `-p rdm.pytest_plugin`, `pytest_plugins` in a top-level
  conftest, or by importing its `pytest_runtest_call` hook into one suite's
  conftest (RDM's acceptance suite does); the record is `--rdm-dhf`, else
  `dhf` under the pytest root. In the call phase of a test whose story tag
  is a declared design input, it adds an epic per user need, the context as
  feature, a link per declaring document (design document, V&V plan, risk
  document) pinned to the commit — only with a web remote and a commit —
  critical severity when a risk's `controls:` names the input, the
  attachment `requirement DI-n`, and the `commit` / `worktree` labels. A
  tag that is no declared input gets nothing. On the first tagged test of a
  session, and only with `--alluredir`, it writes `executor.json` (GitHub
  Actions run, number and URL; or `local` with user@host) and
  `environment.properties` (also the CI event, ref and runner, or the local
  user).
- **Allure reader** (`rdm/record/allure.py`) — the labels and file names
  the plugin writes and every reader reads back; `parse_results` (each
  `*-result.json` as a run: name, status, design inputs, `output` labels),
  `run_version`, `read_run_facts`, `full_name` (Allure's name for a Python
  test, to match a run to its source). `reconcile()` gives each design
  input *failed* when any run failed or broke, else *verified* when any
  passed, else *untested*, and returns stories naming no declared input as
  orphans. The file also holds the specification's tag scanning
  (`find_tests_dir`, `scan_source_tags`, `scan_source_tests`: what a test
  *claims* to verify, DI-31, DI-40, DI-61). The planned split moves that
  part, with the `story` label name that defines a tag, to
  `rdm/specification/`; the results, run facts and `reconcile()` stay here
  and move to `rdm/evidence/`.
- **Mutation probe** (`rdm/gates/mutation.py`) — DI-34, DI-47. It refuses
  a `--find` that does not occur exactly once, runs `pytest -q -k <test>`
  unmutated (an error unless it passes), mutates, runs again. Exit 1 is a
  kill, 0 a survival, anything else (5: no test matched) an error; the
  command exits 0, 1, 2 respectively. Restore in depth: the original is
  journaled to `<file>.rdm-probe-orig` and recovered at the start of the
  next probe of the file (survives SIGKILL); SIGTERM unwinds through the
  restore when the probe runs in the main thread; every write stamps a
  whole-second mtime strictly later than the last, so CPython's
  `(mtime, size)` bytecode key cannot serve a same-size mutant stale.
- **Test result translation** (`rdm/translate.py`, `rdm translate`) and
  **Result formatters** (`rdm/test_formatters/xml_util.py`) — DI-17.
  `translate_test_results` dispatches over `XML_TRANSLATORS` (`auto`,
  `gtest`, `qttest`, `xunit`, the gtest flattener reading xunit) and writes
  each test's name, result and failure message as YAML; an unknown format
  raises `ValueError`. The YAML feeds document templates (`rdm init`'s
  test-record data files); nothing reconciles it against design inputs.

Realised here: `reconcile()` is this context's part of **DI-4** (owned by
`release`), which builds the verification data on it. The labels and run
facts written here are read back for DI-60 (graph) and DI-64 (verification
report). No other context realises part of this context's inputs.

## Components (C3)

![Components: test_evidence](../../c4/views/C3_test_evidence.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Allure reader | Allure results, and the test tags in test sources | Python | `rdm/record/allure.py` |
| Mutation probe | Breaks a line, runs one test, restores | Python | `rdm/gates/mutation.py` |
| Test result translation | Translates foreign test results | Python | `rdm/translate.py` |
| Result formatters | JUnit and other result formats | Python | `rdm/test_formatters/` |
| pytest plugin | Labels each run from the record; the run's executor and environment | Python, pytest | `rdm/pytest_plugin.py` |

The pytest plugin runs in the Acceptance test run; the rest in `rdm`. The
pytest plugin *reads the record with* the record kernel, *reads risks with*
the risk register (which inputs each risk controls) and *writes run labels
and facts with* the Allure reader. The Allure reader *finds the repository
and checks ids with* the record kernel. Test result translation *parses
with* the result formatters and *uses* the utilities. Inward, the design and
release gates, new design input, verification data, the verification
report and the projection read results or test tags with the Allure reader.
The mutation probe imports nothing of RDM and runs pytest as a subprocess,
so it has no relationship.

Open question: until `allure.py` is split, the arrows that read test tags
(from the design gate, new design input and the projection) point here
rather than at the specification.

## Dependencies

This context sits above `specification` and the shared kernel, beside
`risk`, `architecture` and `compliance`. It depends on the kernel
(`rdm/record/git.py`, `ids.py`, `reconcile.py`, `rdm/util.py`), on
`specification` (the plugin reads design inputs and declarations with
`rdm/record/sdd.py`) and on `risk` (below). `release`
(`rdm/record/verify.py`), `publishing` (`rdm/record/report.py`) and `graph`
(`rdm/graph/project.py`, `rdm/graph/allure.py`) depend on it, as the rule
allows.

Two imports break the rule:

- `specification` → `test_evidence`: `rdm/gates/design_gate.py` calls
  `reconcile()` for the release gate and the trace, and it and
  `rdm/gates/new_input.py` take the tag scanning from `allure.py`. Moving
  the release gate and trace to `release` and splitting `allure.py` removes
  it, and the cycle with it.
- `test_evidence` → `risk`, between peers: the plugin imports
  `rdm/record/risk.py` for each input's controlling risks. Removing it
  needs a decision — place `risk` below `test_evidence` in the system
  architecture, or have the plugin receive the controls instead of
  importing the risk reader.

## Out of scope

Planning data (issues, pull requests, task boards) is not part of the
controlled record, and RDM no longer pulls it (Design Review 11).
