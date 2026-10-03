---
id: RMP-001
title: Risk acceptability policy — RDM
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

# Risk acceptability policy — RDM

**Proposed, not yet approved.** An agent wrote this policy; a maintainer
approves it by reviewing it and setting `status: approved`.

The criteria RDM's own risks are evaluated against, set before any
individual risk decision. The matrix is the risk-analysis skill's
four-by-four. A Low risk is acceptable; a Medium or High risk is acceptable
only with a recorded justification (who accepted it, and why further
reduction is not reasonably practicable); Block is never acceptable.

These cells are RDM's choice for RDM, not values from ISO 14971.
