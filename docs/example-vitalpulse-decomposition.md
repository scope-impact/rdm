# Worked example — VitalPulse user needs across bounded contexts

Applies ADR 0001 to the VitalPulse Patient Monitoring System (IEC 62304 Class C;
SpO2/ECG/NIBP/Temperature; ICU). It shows **cross-cutting user needs** addressed
by **multiple bounded-context SDDs**, with verification aggregated across them.
There is no "product need" layer — only user needs.

## User needs (validated, cross-cutting)

Defined once in the system architecture document frontmatter
(`architecture.md`):

| ID | User need (the journey) |
|----|-------------------------|
| `UN-001` | Continuously inform the clinician of the patient's vital signs and promptly alert them to dangerous changes. |
| `UN-002` | Make a patient's monitored data securely available to authorized clinicians at the central station. |
| `UN-003` | Operate safely and deterministically as a life-supporting (Class C) device, tolerating single faults. |

These are the **validation** anchors (usability / clinical / simulated-use
evidence attaches here).

## Bounded contexts (one SDD each) and what they satisfy

Each context's SDD declares `satisfies: [UN-…]` — the user needs its design
contributes to. A user need appears in **multiple** SDDs; it is referenced, not
duplicated.

| Context | SDD | `satisfies` |
|---------|-----|-------------|
| `platform` | `sdd/platform.md` | UN-003 |
| `acquisition` | `sdd/acquisition.md` | UN-001 |
| `alarms` | `sdd/alarms.md` | UN-001, UN-003 |
| `display` | `sdd/display.md` | UN-001, UN-002 |
| `connectivity` | `sdd/connectivity.md` | UN-002 |
| `security` | `sdd/security.md` | UN-002 |

Two things this makes visible — the cases that motivate the model:

- **A user need is addressed by multiple contexts.** `UN-001` is satisfied by
  `acquisition`, `alarms`, and `display`. It is defined once; each SDD points at
  it.
- **A context satisfies multiple user needs.** `alarms` satisfies `UN-001` and
  `UN-003`; `display` satisfies `UN-001` and `UN-002`. Many-to-many.

## Acceptance criteria (verified) within a context

A user need dissolves into acceptance criteria inside each contributing context;
each is verified by an `@allure`-tagged test. For example, within `alarms`
(satisfying UN-001 and UN-003):

```python
@allure.story("UN-001")
def test_threshold_breach_detected_within_limit(): ...

@allure.story("UN-003")
def test_alarm_condition_survives_single_fault(): ...
```

## How V&V rolls up per user need

- **Verification** — a user need is verified when all of its acceptance criteria
  pass, **aggregated across every SDD that satisfies it**. `UN-001` is not
  verified until the relevant tests in `acquisition`, `alarms`, *and* `display`
  pass.
- **Validation** — the user need (the journey) is validated by human evidence
  (usability per IEC 62366, clinical, simulated use), recorded as controlled
  documents and approved in git.
- A user need is **met** when it is **validated** *and* fully **verified**. This
  is the release-gate condition.
