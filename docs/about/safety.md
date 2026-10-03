# Safety and limitations

Read this chapter before relying on a gate's verdict.

## Limitations

--8<-- "README.md:limits"

## Warnings

!!! warning "A green gate is not proof"
    The release gate passes when every design input has a passing tagged
    test. It does not read the test. Before approving a pull request, read
    each changed design input beside its test; use `rdm story
    mutation-probe` where you doubt that the test would fail.

!!! warning "Old evidence"
    The gates judge the Allure results you give them. Run the acceptance
    suite in the same CI job as the gates, with `--clean-alluredir`, so no
    earlier run can stand in for this one. `rdm graph validate` warns on a
    run that tested another commit than the record's.

!!! warning "Keep the hooks on"
    The design gate stops an implementation commit only where the hooks are
    active. Run `git config core.hooksPath .githooks` in every clone (the
    agent bootstrap does it for agent sessions), and make the CI design gate
    a required check.

!!! warning "Agents read, people approve"
    Give agents the read-only agent server (`rdm graph mcp`), never write
    access to the protected branch. Every change to the record goes through
    a pull request a person other than its author approves.

## Residual risks

RDM's own risk register (ISO 14971) names the ways RDM could misreport a
team's evidence. Each is reduced by a control RDM verifies on every release;
what is left is listed here with what you must do. The ratings are
**proposed**: an agent drafted them, and they await a maintainer's approval.

| Risk | What could happen | RDM's control | What you must do |
|---|---|---|---|
| RISK-TOOL-001 | A design-input id in a fixture or comment is read as a tag, and an untested input shows verified. | Tags are read from decorators only. | Tag a test only with `@allure.story("DI-n")` on the test itself; act on every "test tag names no declared design input" warning. |
| RISK-TOOL-002 | The release gate passes an input whose test failed, errored or never ran. | Only a passing run verifies; a failed, broken or skipped run blocks. | Give the gate the results of this run only (above); read the verification report's evidence status before a release. |
| RISK-TOOL-003 | Code lands before the design input that governs it is approved. | The pre-commit and pre-merge hooks run the design gate. | Keep the hooks active in every clone and the CI design gate required (above). |
| RISK-TOOL-004 | An agent changes the record through RDM without review. | The agent server has no write tool and refuses SPARQL Update. | Connect agents to the agent server only; protect the default branch. |
| RISK-TOOL-005 | A test passes without checking everything its input requires. This residual risk stays Medium and is accepted. | The mutation probe shows, on demand, whether a test catches a defect. | Review each input beside its test; probe the doubtful ones; hold a chain review of every input before each release. |
| RISK-TOOL-006 | A web page you have open sends an update to the served graph. | `rdm graph serve` is read-only and refuses every update. | Serve on `localhost` (the default), never on a public address; stop the server when you are done. |
| RISK-TOOL-007 | A query makes RDM reach other services on your network. | Queries with `SERVICE` are refused by the endpoint and the agent server. | Keep the endpoint local as above; report any network access RDM makes. |

Report a problem, a misleading result or a suspected new risk as an issue
on [scope-impact/rdm](https://github.com/scope-impact/rdm/issues).
