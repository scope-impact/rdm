---
id: RMP-001
title: "Risk acceptability policy — git/GitHub document control"
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

# Risk acceptability policy

**Proposed, not yet approved.** These criteria are a proposal written for this
example; the quality owner of a real deployment approves them, or replaces
them, by reviewing this document and setting `status: approved`. RDM ships no
default matrix: the criteria are the organization's.

A Low risk is acceptable. A Medium or High risk is acceptable only with a
recorded justification: who accepted it, and why further reduction is not
reasonably practicable. Block is never acceptable.
