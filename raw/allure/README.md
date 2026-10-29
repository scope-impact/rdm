# raw/allure: the result model the gate reads

The Allure result model is the tool-neutral format every judge writes
(INV-11). These are its authoritative statements, fetched 2026-10-29 and
never edited. Where the documentation and a reference implementation differ,
the harness follows the documentation and the decision says so.

| Path | From | Pinned at | What it is |
|---|---|---|---|
| `docs/how-it-works.md` | https://allurereport.org/docs/how-it-works.md | fetched 2026-10-29 | the two phases: results written by the framework, the report built from them |
| `docs/how-it-works-test-result-file.md` | https://allurereport.org/docs/how-it-works-test-result-file.md | fetched 2026-10-29 | the test result file: `{uuid}-result.json`, its identifiers, metadata (labels, links, parameters, attachments), execution (status, statusDetails, stage, start, stop, steps) |
| `docs/how-it-works-container-file.md` | https://allurereport.org/docs/how-it-works-container-file.md | fetched 2026-10-29 | the container file: `{uuid}-container.json`, fixtures around results |
| `docs/how-it-works-test-identifiers.md` | https://allurereport.org/docs/how-it-works-test-identifiers.md | fetched 2026-10-29 | `fullName`, `historyId`, `testCaseId`: how a result is matched across runs |
| `docs/test-statuses.md` | https://allurereport.org/docs/test-statuses.md | fetched 2026-10-29 | passed, failed, skipped, broken, unknown |
| `docs/how-it-works-glossary.md` | https://allurereport.org/docs/how-it-works-glossary.md | fetched 2026-10-29 | Allure's own vocabulary |
| `allure-java/allure-model/*.java` | github.com/allure-framework/allure-java, `allure-model/src/main/java/io/qameta/allure/model/` | tag `3.0.1` (`72fa349`) | the reference model: TestResult, ExecutableItem, StepResult, Status, Stage, StatusDetails, Label, Link, Parameter, Attachment, TestResultContainer, FixtureResult |
| `allure-js/allure-js-commons/model.ts` | github.com/allure-framework/allure-js, `packages/allure-js-commons/src/model.ts` | tag `v3.14.0` (`6b62dd6`) | the model the browser-driver and Gherkin-runner integrations write through |

The documentation pages are not under version control upstream, so they are
pinned by fetch date; the two models are pinned by tag and commit. A later
fetch that differs is a new row, never an edit.
