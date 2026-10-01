# Risk register

The risk register is part of the record: risks are frontmatter in
`kind: risk` documents under the DHF, in the same way design inputs are
frontmatter in `kind: design` ones. The release gate holds the register to
the rules a machine can check, and the graph carries it, so a query or an
agent can walk **risk → control → test → result**.

The method — building the chain, scoring it, choosing controls, judging
residual risk — is the
[risk-analysis skill](https://github.com/scope-impact/agent-skills/tree/main/skills/engineering/risk-analysis).
RDM keeps the register and checks it; it does not do the analysis.

## A risk

```yaml
---
id: RMF-001
title: Risk register
kind: risk
risks:
  - id: RISK-DATA-001
    hazard: "Lab report sync fails silently when the network drops."
    situation: "A user adds a report offline and assumes it synced."
    harm: "A health record is missing at a doctor's visit."
    severity: Serious          # from the harm
    probability: Possible      # from the situation, never the hazard
    controls: [DI-12, DI-14]   # each control is a design input
    residual: {probability: Rare}
---
```

- **A control is a design input.** That gives the control a stated
  requirement, a tagged test and a result, so "every control is verified"
  comes from the release gate's existing rule that every design input has a
  passing test.
- **Levels are looked up, not typed.** RDM computes the initial and residual
  level from the risk matrix. You may also write `level:`; the gate then
  checks that it matches the matrix.
- **The residual records probability only.** Severity belongs to the harm:
  a control lowers how likely the harm is, not how bad it is.
- **Accepting a residual of Medium or High** takes an `acceptance:` with
  `by` (who accepted it) and `rationale` (why further reduction is not
  reasonably practicable, and what monitors it).

## The matrix

The default is the risk-analysis skill's four-by-four:

| | **Rare** | **Unlikely** | **Possible** | **Likely** |
|---|---|---|---|---|
| **Critical** | Medium | High | High | Block |
| **Serious** | Low | Medium | High | High |
| **Minor** | Low | Low | Medium | Medium |
| **Negligible** | Low | Low | Low | Low |

To use your own, declare it once in any DHF document's frontmatter — the
risk management plan is the natural place. Use the level names Low, Medium,
High and Block, because the gate rules are stated in them:

```yaml
risk_matrix:
  severities: [Catastrophic, Serious, Minor]
  probabilities: [Improbable, Remote, Occasional, Frequent]
  levels:
    Catastrophic: [Medium, High, Block, Block]
    Serious: [Low, Medium, High, Block]
    Minor: [Low, Low, Medium, High]
```

## What blocks a release

`rdm story release-gate` blocks on any of these:

- a risk id declared twice
- a risk with no id
- a missing hazard, situation or harm
- a severity or probability the matrix does not define
- a recorded level that differs from the matrix
- a risk above Low that nothing controls
- a control that is not a declared design input
- a risk that has controls but no residual score
- a residual of Block
- a residual of Medium or High with no acceptance

`rdm graph validate`, and the agent server's `validate`, report the same
risks through the shipped SHACL shapes.

## What the gate does not check

These are the reviewer's job, using the risk-analysis skill:

- **Whether a control is real in the code.** A linked design input proves
  the control was written down and tested, not that it holds. Read the code
  against the control's own words.
- **Whether a residual is as low as reasonably practicable (ALARP).**
- **Whether the chain is honest:** probability scored from the situation,
  severity from the harm, the harm naming who is hurt.

## Tracing

```bash
rdm graph query 'SELECT ?risk ?level ?residual ?input WHERE {
  ?r a rdm:Risk ; rdfs:label ?risk ; rdm:level ?level ; rdm:residualLevel ?residual .
  OPTIONAL { ?r rdm:controlledBy/rdfs:label ?input } } ORDER BY ?risk'
```

An agent connected to `rdm graph mcp` calls `trace` with a risk id, and gets
the chain, the scores and each controlling design input with its tests and
runs. A design input's trace lists the risks it controls.

RDM's own register is
[`dhf/documents/risk/tool_risks.md`](https://github.com/scope-impact/rdm/blob/main/dhf/documents/risk/tool_risks.md):
the ways RDM itself could misreport evidence.
