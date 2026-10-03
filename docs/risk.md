# Risk register

The risk register is part of the record: risks are frontmatter in
`kind: risk` documents under the DHF, in the same way design inputs are
frontmatter in `kind: design` ones. The release gate holds the register to
the rules a machine can check, and the graph carries it, so a query or an
agent can walk **risk → control → test → result**.

The method — two branches from each user need (baseline acceptance criteria;
safety and STRIDE risks), evaluating, controlling, verifying, re-evaluating —
is the
[requirements](https://github.com/scope-impact/agent-skills/tree/main/skills/engineering/requirements)
and
[risk-analysis](https://github.com/scope-impact/agent-skills/tree/main/skills/engineering/risk-analysis)
skills'. RDM keeps the register and checks it; it does not do the analysis.

## A risk

```yaml
---
id: RMF-001
title: Risk register
kind: risk
risks:
  - id: RISK-DATA-001
    category: safety                 # safety or security
    hazard: "Lab report sync fails silently when the network drops."
    situation: "A user adds a report offline and assumes it synced."
    harm: "A health record is missing at a doctor's visit."
    severity: Serious                # from the harm
    probability: Possible            # from the situation, never the hazard
    controls: [DI-12, DI-14]         # each control is a design input
    residual: {probability: Rare}    # add severity: where a control limits the harm itself
    status: approved                 # proposed until a person approves the rating
  - id: RISK-DATA-002
    category: security
    stride: Information disclosure   # Spoofing, Tampering, Repudiation, Information disclosure,
                                     # Denial of service, Elevation of privilege
    linked: [RISK-DATA-001]          # the safety risk this threat bears on, if any
    ...
---
```

- **A control is a design input** — your subsystem `shall` requirement. That
  gives the control a stated requirement, a tagged test and a result.
- **Levels are evaluated, not typed.** RDM looks them up in your risk
  policy. You may also write `level:`; the gate checks it matches.
- **A residual is not evaluated until its controls are verified.** Until
  every controlling design input has a passing test, the residual decision
  is *not evaluated* and the release is blocked. A passing test shows the
  control was built as specified; whether it is *effective* is the
  reviewer's judgement.
- **`status: proposed`** marks ratings no person has approved — an agent's
  suggestion, say. It is a warning at the release gate, never silent. A
  document-level `status` applies to every risk in it that sets none.

## The risk policy

There is no default. Acceptability criteria are yours, set before any
individual risk decision — a matrix's cells are not ISO 14971 values. Declare
one `risk_policy` in any DHF document's frontmatter (the risk management
plan is the natural place); without it, a register with risks blocks the
release as *acceptability criteria missing*.

```yaml
---
id: RMP-001
title: Risk acceptability policy
status: approved
risk_policy:
  severities: [Critical, Serious, Minor, Negligible]
  probabilities: [Rare, Unlikely, Possible, Likely]
  levels:                       # one level per probability, per severity
    Critical: [Medium, High, High, Block]
    Serious: [Low, Medium, High, High]
    Minor: [Low, Low, Medium, Medium]
    Negligible: [Low, Low, Low, Low]
  acceptability:                # per level: acceptable | justify | unacceptable
    Low: acceptable
    Medium: justify
    High: justify
    Block: unacceptable
---
```

The cells above are the risk-analysis skill's four-by-four, shown as a
starting point; level names and cells are your choice. `justify` means
acceptable only with an `acceptance:` naming `by` (who accepted it) and
`rationale` (why further reduction is not reasonably practicable, and what
monitors it).

## What blocks a release

`rdm story release-gate` blocks on any of these:

- risks but no declared risk policy
- a risk id declared twice, or a risk with no id
- a missing hazard, situation or harm
- a missing or unknown category; a security risk with no STRIDE category; a
  link to an undeclared risk
- a severity or probability the policy does not define; a recorded level
  that differs from the policy's
- a control that is not a declared design input; controls but no residual
- a control with no passing test (residual not evaluated)
- a residual — the initial risk, when nothing controls it — the policy
  calls unacceptable, or one it accepts only with justification and no
  acceptance
- an unknown status

Proposed risks, and a proposed policy, are warnings. `rdm graph validate`,
and the agent server's `validate`, report the same risks through the
shipped SHACL shapes.

## What the gate does not check

These are the reviewer's job, using the skills:

- **Whether a control is effective, and real in the code.** A verified
  design input proves the control was specified, built and tested — not
  that it reduces the risk as claimed.
- **Whether a residual is as low as reasonably practicable (ALARP).**
- **Whether the analysis is honest:** probability from the situation,
  severity from the harm, the harm naming who is hurt, STRIDE threats found
  rather than assumed absent.

## Tracing

```bash
rdm graph query --allure-results dhf/allure-results 'SELECT ?risk ?decision ?input WHERE {
  ?r a rdm:Risk ; rdfs:label ?risk ; rdm:residualDecision ?decision .
  OPTIONAL { ?r rdm:controlledBy/rdfs:label ?input } } ORDER BY ?risk'
```

An agent connected to `rdm graph mcp` calls `trace` with a risk id, and gets
the chain, the scores, the residual decision and each controlling design
input with its tests and runs. A design input's trace lists the risks it
controls.

RDM's own policy and register are
[`dhf/documents/risk/`](https://github.com/scope-impact/rdm/tree/main/dhf/documents/risk):
the ways RDM itself could misreport evidence — written by an agent, and
marked proposed until a maintainer approves them.
