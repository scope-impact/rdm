# Example: validating Löyly's pi harness (computer software validation)

[Löyly](https://github.com/scope-impact/loyly) is an agentic software factory:
its spec is the source of truth and the code is regenerated from it. Its pi
harness (spec v2) is a real product with a real spec: 201 stamped Gherkin
requirements in six contexts, user needs in an event model, Allium rules and
an event storm, and six clojure.test acceptance suites. This example keeps
the harness's design history file with RDM **without changing Löyly**:
Löyly knows nothing of RDM or Allure.

| Path | Role |
|---|---|
| `../../loyly/` | RDM's `loyly` submodule (`spec-v2`): the spec and the code under test |
| `dhf/config.yml` | `spec: ../../../loyly/.pi/plan`: RDM reads Löyly's plan and its needs |
| `dhf/documents/tool_validation.md` | **the validation, in one document** (CSV-HAR-001, proposed): intended use, process risk with the ten risks (four of them AI-specific) as its frontmatter, assurance activities, record, Part 11 for the record system, change |
| `dhf/documents/traceability_matrix.md` | the matrix template, rendered from generated data |
| `rdm/compliance/checklists/csv_tool_validation.txt` (built-in `csv_tool_validation`) | computer software validation of a production tool and of its record system, each item citing its source PDF, section and page (FDA's CSA guidance of 3 Feb 2026, ISO 13485 4.1.6 and 7.5.6, 21 CFR 11.10(a)); for `rdm gap` and graph validation |
| `bridge/user.clj` | clojure.test to Allure, loaded into each test JVM from outside |
| `run-acceptance.sh` | runs the six suites unchanged, in a copy of the checkout, and writes `dhf/allure-results/` |

## Run it

Needs a JDK and the Clojure CLI on `PATH` (the suites fetch their own
dependencies, Grain and ORC among them, on the first run), and RDM.

```bash
git submodule update --init loyly        # from RDM's root
cd examples/loyly-pi-harness
./run-acceptance.sh
rdm story release-gate --dhf dhf --allure-results dhf/allure-results
rdm gap csv_tool_validation dhf/documents/tool_validation.md
rdm gap part11_document_control dhf/documents/tool_validation.md
rdm graph validate --dhf dhf --allure-results dhf/allure-results \
  --checklist csv_tool_validation --checklist part11_document_control
rdm story evidence-bundle --dhf dhf --allure-results dhf/allure-results -o release-evidence
```

The bundle holds the verification report (`verification_report.pdf`: about 210
pages, one per requirement with its scenario, need and runs, release-grade when
every run tested Löyly's commit), the traceability matrix, the verification
data and the results with their manifest. CI uploads it as the
`loyly-pi-harness-evidence` artifact.

## How the evidence gets out without touching Löyly

Each suite's `test.sh` runs as Löyly wrote it. `run-acceptance.sh` puts
`bridge/` on the JVM's boot class path (`JAVA_TOOL_OPTIONS`), and Clojure
loads `user.clj` from the class path when it starts. The bridge wraps
`clojure.test/report`, and each test var it sees end becomes one Allure
result: *failed* on a failed assertion, *broken* on an error, else
*passed*, with a `tag` label naming the requirement the test claims (its
`:item` metadata, else an `item-NN-SSS` at the start of its name). A test
that claims no requirement is still written, without a tag. The bridge does
nothing unless `ALLURE_RESULTS_DIR` is set.

If Löyly renames its tests or moves an id, the bridge cannot see the claim
and the requirement shows as untested: the release gate fails; it never
passes quietly.
