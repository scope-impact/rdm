---
id: CSV-001
title: Computer software validation — RDM
status: proposed
references:
- VVP-001
- DC-001
- SDS-SYS-001
kind: risk
risk_policy:
  severities:
  - Critical
  - Serious
  - Minor
  - Negligible
  probabilities:
  - Rare
  - Unlikely
  - Possible
  - Likely
  levels:
    Critical:
    - Medium
    - High
    - High
    - Block
    Serious:
    - Low
    - Medium
    - High
    - High
    Minor:
    - Low
    - Low
    - Medium
    - Medium
    Negligible:
    - Low
    - Low
    - Low
    - Low
  acceptability:
    Low: acceptable
    Medium: justify
    High: justify
    Block: unacceptable
risks:
- id: RISK-TOOL-002
  category: safety
  status: proposed
  hazard: The release gate passes a requirement whose test failed or never ran.
  situation: A test fails, errors or is skipped in CI, and the release gate still reports PASSED.
  harm: A team releases software with a requirement that is not verified.
  severity: Serious
  probability: Possible
  controls:
  - item-04-001
  - item-04-002
  residual:
    probability: Rare
- id: RISK-TOOL-004
  category: security
  stride: Tampering
  status: proposed
  hazard: An agent changes the controlled record through RDM without review.
  situation: An agent connected to rdm graph mcp issues a SPARQL Update or looks for a write tool.
  harm: The record diverges from what reviewers approved, and evidence no one reviewed is presented as controlled.
  severity: Serious
  probability: Possible
  controls:
  - item-06-007
  residual:
    probability: Rare
- id: RISK-TOOL-005
  category: safety
  status: proposed
  hazard: A test that claims a requirement passes without checking everything its scenario states.
  situation: A test checks two of the three things a requirement requires, and the pull-request reviewer does not notice.
  harm: The requirement is reported verified while one thing it requires is unchecked, and the gap ships.
  severity: Serious
  probability: Possible
  controls: []
  acceptance:
    by: RDM maintainers, by approving the pull request that records this register
    rationale: Whether a test proves its requirement is a reviewer's judgement, made in the pull-request review; no gate can make it. Monitored by reviewers reading each changed test against its scenario's text.
- id: RISK-TOOL-006
  category: security
  stride: Tampering
  status: proposed
  hazard: The served graph accepts SPARQL updates from any web origin.
  situation: A person browses the graph in Graph Explorer while a web page they have open posts an update to the local endpoint.
  harm: The graph the person reviews is cleared or shows forged verification, and they act on evidence the record does not hold.
  severity: Serious
  probability: Unlikely
  controls:
  - item-06-002
  residual:
    probability: Rare
- id: RISK-TOOL-007
  category: security
  stride: Information disclosure
  status: proposed
  hazard: A read-only query can make RDM send network requests.
  situation: A prompt-injected agent, or a web page posting to the served endpoint, sends a query with a SERVICE clause naming an internal or external address.
  harm: The server probes or reaches services from the user's machine, outside the review the record is under.
  severity: Serious
  probability: Unlikely
  controls:
  - item-06-007
  - item-06-002
  residual:
    probability: Rare
- id: RISK-TOOL-008
  category: safety
  status: proposed
  hazard: 'The spec is read short: requirements are left out and no one notices.'
  situation: A context's spec is missing from the repository (ignored by git, or a plan link to a folder never committed), and the gate counts only what it found.
  harm: A release passes with requirements that were never checked, reported as complete.
  severity: Serious
  probability: Possible
  controls:
  - item-04-001
  - item-01-001
  residual:
    probability: Rare
- id: RISK-TOOL-009
  category: safety
  status: proposed
  hazard: The evidence that a release was verified cannot be retrieved later.
  situation: The CI artifact holding the evidence bundle expires, or the repository is lost.
  harm: An auditor cannot confirm what was verified for a release, and the team cannot show it.
  severity: Serious
  probability: Unlikely
  controls:
  - item-04-003
  residual:
    probability: Unlikely
  acceptance:
    by: RDM maintainers, by approving the pull request that records this register
    rationale: RDM writes the complete, checksummed bundle; keeping it for the retention period is document control's job (DC-001, 11.10(c)), not the tool's. Monitored through DC-001's retention commitment.
- id: RISK-TOOL-010
  category: safety
  status: proposed
  hazard: The gate decides on results that are not the evidence of the commit being released.
  situation: Results are altered or swapped on their way from the test job to the gate, or an earlier run's results are used.
  harm: A release is approved on evidence of something else.
  severity: Serious
  probability: Unlikely
  controls:
  - item-06-016
  - item-07-009
  - item-04-003
  residual:
    probability: Rare
---

# Computer software validation — RDM

**Proposed, not yet approved.** The validation of RDM as a tool: software
used as part of a regulated team's quality management system, not a medical
device, validated the computer software assurance way, risk based
([[13485:4.1.6-procedure]]). This is RDM's final document: its intended use,
its architecture, its risk analysis (the risk policy and the risks are this
document's frontmatter, which the release gate evaluates), its whole spec
with each requirement's verification, the assurance, the record and change.
It is a template: `rdm render` fills in the architecture and the spec from
the record, with the `include` and `glob` filters, and the verification from
`data/verification.yml`. A team that adopts RDM validates it for its own
intended use; this record is the supplier's assurance it can rely on.

## Intended use

RDM reads a team's spec, checklists, risks, documents and executed test
results; reports the checklist clauses no document references (gap
analysis); decides whether a release may go ahead (the release gate); and
generates the verification report and the evidence bundle, which are quality
records. It is used directly as part of the quality management system: it
automates a quality process (the release decision) and maintains quality
records ([[CSA:intended-use]]). It does not produce the product itself, so
ISO 13485 7.5.6 applies to it only where a team uses its gates inside
production.

{{ "dhf/documents/architecture.md" | include | md_indent(1) }}

## Process risk

A failure of RDM to perform as intended could let a release go ahead with a
requirement not verified, a risk not evaluated, or evidence that does not
describe what was tested: RDM determines acceptability with limited
additional human review where a team relies on its gate alone. That is a
quality problem that could foreseeably compromise safety: **high process
risk** ([[CSA:risk]], [[13485:4.1.6-proportionate]]). The ways it could fail
are the risks below, evaluated against the risk policy below (severity from
the harm, probability from the situation, the residual after the controls),
each control a requirement of RDM's own spec. The analysis covers every
factor the guidance names (its §V.A(2)), for failures that are reasonably
foreseeable, not only likely:

| Factor | Risks |
|---|---|
| Configuration and management | RISK-TOOL-008 (the spec read short) |
| Security | RISK-TOOL-004, RISK-TOOL-006, RISK-TOOL-007 |
| Data integrity | RISK-TOOL-002, RISK-TOOL-005 |
| Data storage | RISK-TOOL-009 (evidence not retrievable) |
| Data transfer, operation error | RISK-TOOL-010 (a decision on other results) |

## Risk policy

## Risks

## Specification

RDM's spec, written the Löyly way in `.pi/spec/`: each accepted scenario, a
concrete Given/When/Then example stamped `@item-NN-SSS`, is a requirement,
verified by the acceptance test that claims it; each event-model screen,
linked to its Allium surface, is a user need. The table is each
requirement's verification from the executed results; the feature files
follow, laid out by the `gherkin` render filter.

{% if verification is defined %}
{{ verification.summary.verified }} of {{ verification.summary.total }} requirements verified ({{ verification.summary.failed }} failed, {{ verification.summary.untested }} untested), from {{ verification.summary.results_found }} executed test results.

| Requirement | Scenario | User need | Status |
|---|---|---|---|
{% for group in verification.groups %}{% for req in group.requirements %}| {{ req.requirement }} | {{ req.text }} | {{ group.user_need }} | {{ req.status }} |
{% endfor %}{% endfor %}
{% else %}
The verification data is not part of this copy: run the acceptance tests and
`rdm story verify` (`data/verification.yml`), then render it again.
{% endif %}

{% for feature in ".pi/spec/*/spec.feature" | glob %}
### {{ feature.split("/")[2] }}

{{ feature | gherkin(verification | default(none)) }}

{% endfor %}

## Assurance activities

Proportionate to that risk ([[CSA:assurance]]):

- **Scripted, automated testing.** Each of RDM's 44 requirements (stamped
  Given/When/Then scenarios in `.pi/spec/`) is verified by an acceptance test
  that claims it; RDM's own release gate blocks while any requirement lacks a
  passing run, any user need is unaddressed, or any risk control is
  unverified.
- **Risk-based focus.** The requirements that control one of the risks (the
  release gate, the read-only agent server, the served graph) count only once
  verified and the risk's residual is evaluated again.
- **Scenario testing on a real product.** The worked example runs RDM's gates
  over Löyly's pi harness (201 requirements, six clojure.test suites, a spec
  in another repository) on every change to RDM, beside RDM's own record.
- **Ongoing monitoring.** Every change runs every gate in CI, on RDM's own
  record (dogfooding) and on the example; a gate that stops passing stops the
  change.

The assurance also relies on ([[CSA:assurance-considerations]]): the
dependencies pinned in `uv.lock`, the RDM container image built from them,
and the executed results RDM keeps as evidence rather than screenshots.

## Record

The record of each validation ([[CSA:record]], [[13485:4.1.6-records]]) is
the evidence bundle the design-controls workflow writes from each run: the
verification report (the commit validated, who and what ran the tests and
where, each requirement with its runs, the issues found, and whether the
evidence is release-grade), the traceability matrix, the verification data
and the results with a manifest and one SHA-256 over them. The conclusion is
the release gate's verdict; the approval, the reviewed pull request.

## The record system and Part 11

The validation record is required as evidence of a validated state and is
kept electronically, so it is a Part 11 electronic record and the system that
keeps it is held to Part 11 ([[CSA:part11]]): git as the document control
system, GitHub as its service provider and RDM's gates, mapped control by
control in DC-001. That system is validated by this same procedure: RDM's
gates are what the assurance above tests.

## Change

RDM is validated before a release is first used and again after every change
([[13485:4.1.6-before-use]], [[CSA:change]]): every pull request runs every
gate before it can merge; a release is a tag, and a team adopting RDM pins
its CI to one release (the reusable workflow and the image, item-04-004), so
a new RDM release reaches a team only when it changes the pin, and the team
assesses that change for its own use ([[13485:7.5.6-software]] where RDM is
used in production).
