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

This context realises design inputs **DI-3** (view vitals, refining UN-002) and
**DI-4** (access control + audit, refining UN-003); their acceptance criteria are
verified by tests tagged `@allure.story("DI-3")` / `@allure.story("DI-4")` (e.g.
unauthorized access is denied; every read is audited).
