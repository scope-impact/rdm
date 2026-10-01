---
id: SDS-REC-001
kind: design
context: record
satisfies: [UN-001, UN-004, UN-012]
design_inputs:
  - id: DI-1
    text: "RDM shall read the user-need registry + satisfies refs from frontmatter and ingest Allure results, with no project-management dependency."
    traces_to: [UN-001, UN-004]
  - id: DI-29
    text: "RDM shall generate device-master-record index data from controlled documents' frontmatter, writing one entry per document (id, title, path, revision) to a data file the DMR index renders from."
    traces_to: [UN-012]
  - id: DI-31
    text: "RDM shall discover verification tags in non-Python test sources — JavaScript/TypeScript allure calls and Java Story/Feature annotations across conventional test-file names — so tag-linkage warnings and audit coverage work in polyglot repositories."
    traces_to: [UN-004]
  - id: DI-40
    text: "RDM shall read a Python test file's verification tags only from allure story/feature decorators on its test functions and classes and from a module-level pytestmark — never from strings or comments — so a test that writes fixture files is not counted as verifying the ids those files name; a file that does not parse falls back to the decorator pattern."
    traces_to: [UN-004]
---

# Record — Software Design

## Design Inputs

This context owns the design inputs declared in the frontmatter:

- **DI-1 (record ingest)** — read the user-need registry and per-context
  `satisfies` references from frontmatter, and ingest executed Allure results,
  without depending on any project-management tool. Refines UN-001 and UN-004.

- **DI-29 (DMR index data)** — `rdm story dmr` generates device-master-record
  index data from the controlled documents' own frontmatter (one entry per
  document: id, title, path, revision), so the DMR index is derived from the
  record rather than hand-maintained — the same generated-not-transcribed rule
  the traceability matrix follows. Refines UN-012.
- **DI-31 (polyglot tag discovery)** — executed verification (Allure results)
  was always language-agnostic; source-tag scanning was Python-only, so a
  TypeScript or Java product got no authoring-time linkage warnings and no
  audit coverage. Tag discovery now also reads
  JavaScript/TypeScript `allure.story(...)`/`allure.feature(...)` calls and
  Java `@Story(...)`/`@Feature(...)` annotations across conventional test-file
  names (`*.test.*` / `*.spec.*` / `*Test.java` / `*_test.go` …). Refines
  UN-004.
- **DI-40 (Python tags from decorators only)** — Python tags were read with a
  text pattern, so a test that writes a fixture file containing
  `@allure.story("DI-1")` was counted as verifying DI-1 — ten such false links
  in RDM's own suite. Python tags are now read from the syntax tree: allure
  `story`/`feature` decorators on functions and classes, and a module-level
  `pytestmark`. A file that does not parse falls back to the pattern. Refines
  UN-004.

## Design Outputs

Ingests the system of record so the rest of RDM can compile and gate the DHF.

- `rdm/record/sdd.py` — discover per-context design documents (`kind: design`);
  read the user-need registry (`user_needs`), the design inputs (`design_inputs`),
  and `satisfies` references from frontmatter.
- `rdm/record/allure.py` — parse an Allure results directory into per-design-input
  executed status; scan test sources for the tags they claim (Python from the
  syntax tree, other languages by pattern).
- `rdm/record/verify.py` — build the verification data the DHF renders from.

The layer is dependency-light: no pydantic, DuckDB or RDF dependency.
Retired (Design Review 11): DI-6 — RDM ships no planning tooling, so there
are no planning outputs to mark. Acceptance criteria are verified by
`@allure.story("DI-1")` tests.
