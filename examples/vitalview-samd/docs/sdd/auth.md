---
id: SDS-AUTH-001
context: auth
satisfies: [UN-002, UN-003]
---

# Auth — Software Design

Authenticates clinicians (SSO/OIDC), authorizes access to patient data by
care-team membership, and writes an audit record for every PHI read.

Contributes to:

- **UN-002** — a clinician must securely sign in before viewing vitals.
- **UN-003** — access is restricted to authorized clinicians and audited.

Acceptance criteria are verified by tests tagged `@allure.story("UN-002")` /
`@allure.story("UN-003")` (e.g. unauthorized access is denied; every read is
audited).
