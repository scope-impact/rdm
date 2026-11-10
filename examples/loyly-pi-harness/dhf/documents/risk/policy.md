---
id: RMP-HAR-001
title: Risk acceptability policy — pi harness
status: proposed
risk_policy:
  severities: [Critical, Serious, Minor, Negligible]
  probabilities: [Rare, Unlikely, Possible, Likely]
  levels:
    Critical: [Medium, High, High, Block]
    Serious: [Low, Medium, High, High]
    Minor: [Low, Low, Medium, Medium]
    Negligible: [Low, Low, Low, Low]
  acceptability:
    Low: acceptable
    Medium: justify
    High: justify
    Block: unacceptable
---

# Risk acceptability policy — pi harness

**Proposed, not yet approved.** The criteria the harness's risks are
evaluated against, set before any risk decision: a Low risk is acceptable, a
Medium or High one only with a recorded justification, Block never. The
matrix is this example's choice, not a value from ISO 14971.
