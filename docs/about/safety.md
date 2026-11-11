# Limits and risks

Read this before you trust a green gate.

## What RDM does not do

--8<-- "README.md:limits"

## Things to get right

!!! warning "A green gate is not proof"
    The release gate passes when every requirement has a passing tagged
    test. It does not read the test. Before approving a pull request, read
    each changed scenario beside its test, and ask whether the test would
    fail if the code did not do what the scenario says.

!!! warning "Old evidence"
    The gates judge the Allure results you give them. Run the acceptance
    suite in the same CI job as the gates, with `--clean-alluredir`, so no
    earlier run can stand in for this one. `rdm graph validate` warns on a
    run that tested another commit than the product's.

!!! warning "Agents read, people approve"
    Give agents the read-only agent server (`rdm graph mcp`), never write
    access to the protected branch. Every change to the record goes through
    a pull request a person other than its author approves.

## Known risks, and what you do about them

RDM keeps its own risk register: the ways RDM itself could misreport your
evidence. Each has a control that RDM's tests check on every release; this
table is what is left over, and what you do about it. The ratings are
**proposed**: an agent drafted them, and they await a maintainer's approval.

| Risk | What could happen | RDM's control | What you must do |
|---|---|---|---|
| RISK-TOOL-002 | The release gate passes a requirement whose test failed, errored or never ran. | Only a passing run verifies; a failed or broken run blocks; a requirement whose only runs were skipped is unverified. | Give the gate the results of this run only (above); read the verification report's evidence status before a release. |
| RISK-TOOL-004 | An agent changes the record through RDM without review. | The agent server has no write tool and refuses SPARQL Update. | Connect agents to the agent server only; protect the default branch. |
| RISK-TOOL-005 | A test passes without checking everything its requirement states. This residual risk stays Medium; its acceptance is proposed, awaiting a maintainer. | None in RDM: whether a test proves its requirement is the pull-request reviewer's judgement. | Review each changed test against its scenario's text. |
| RISK-TOOL-006 | A web page you have open sends an update to the served graph. | `rdm graph serve` is read-only and refuses every update. | Serve on `localhost` (the default), never on a public address; stop the server when you are done. |
| RISK-TOOL-007 | A query makes RDM reach other services on your network. | Queries with `SERVICE` are refused by the endpoint and the agent server. | Keep the endpoint local as above; report any network access RDM makes. |
| RISK-TOOL-008 | Part of your spec is missing from the repository (ignored by git, or a plan link to a folder never committed), and the gate checks only what it found. | A slice of a plan with no `spec.feature`, or a feature file Gherkin cannot read, blocks release, naming it. | Check the release gate's list of verified requirements has the count you expect, and never ignore a spec folder in `.gitignore`. |
| RISK-TOOL-009 | The evidence that a release was verified cannot be found later. | `rdm story evidence-bundle` writes a complete bundle with a manifest and a SHA-256 over the results. | Keep each release's evidence bundle for your retention period in your own document control: a CI artifact expires. This residual risk stays Medium; its acceptance is RDM's maintainers'. |
| RISK-TOOL-010 | The gate decides on results that are not the evidence of the commit you release. | Each run carries the commit it tested; the graph warns on a run of another commit, and the verification report is release-grade only when every run tested the product's commit. | Run the acceptance tests and the gates in one CI job on the commit you release, into a clean results directory. |

## Security and data

- **No telemetry.** RDM sends nothing anywhere. Its only outside calls are to
  your local `git`; the reusable CI workflow fetches RDM itself from GitHub
  at the release you pin.
- **The served graph can be read by any web page.** `rdm graph serve` allows
  requests from any origin so that Graph Explorer can reach it, and refuses
  every update. While it runs, a page you have open in the same browser can
  read the whole record. Serve only a record you would show that page, and
  stop the server when you are done.
- **Personal data.** The record holds the names and e-mail addresses git
  records for each author, and whatever your documents and test results
  contain. Protecting them is part of your quality and privacy system.
- **Backup and retention.** Git and your forge hold the record; the evidence
  bundle (`rdm story evidence-bundle`) holds each release's evidence with its
  checksums. Keep both under your retention rules; RDM keeps no copy.

Report a problem, a misleading result or a suspected new risk as an issue
on [scope-impact/rdm](https://github.com/scope-impact/rdm/issues). Report a
security vulnerability privately, through the repository's security advisory
form, not as a public issue.
