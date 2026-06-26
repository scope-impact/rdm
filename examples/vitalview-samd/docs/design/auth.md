---
id: SDS-AUTH-001
kind: design
context: auth
satisfies: [UN-002, UN-003]
design_inputs:
  - id: DI-4
    text: "VitalView shall restrict patient-data access to authorized clinicians and write an audit record for each access."
    traces_to: [UN-003]
realises: [DI-3]
---

# Auth — Software Design

## Design Inputs

This context owns **DI-4** (access control + audit, refining UN-003). It also
**realises** **DI-3** (view vitals, owned by the `dashboard` context) by gating
who may view a patient's vitals.

## Design Outputs

Authenticates clinicians (SSO/OIDC), authorizes access to patient data by
care-team membership, and writes an audit record for every PHI read.

Contributes to **UN-002** (a clinician must securely sign in before viewing
vitals) and **UN-003** (access is restricted to authorized clinicians and
audited). Acceptance criteria are verified by tests tagged
`@allure.story("DI-3")` / `@allure.story("DI-4")` (e.g. unauthorized access is
denied; every read is audited).
