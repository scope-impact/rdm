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
    acceptance:                      # only where the policy says justify for the residual level
      by: "Jane Doe (QA lead)"       # who accepted it
      rationale: "Further reduction is not practicable; sync failures are monitored weekly."
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
  document-level `status` applies to every risk in it that sets none; with
  neither, the rating is proposed. The same holds for the policy: only
  `status: approved` is approved.

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

## What the gates check, and what they do not

The release gate blocks on a register it cannot evaluate or whose residuals
the policy does not accept, and warns on proposed ratings; the rules are
DI-44 and DI-50 in the [risk design](dhf/documents/design/risk.md), and
`rdm graph validate` reports the same through its shapes. Whether a control
is *effective*, whether a residual is as low as reasonably practicable, and
whether the analysis is honest are the reviewer's, using the skills.

## Tracing

```bash
rdm graph query --allure-results dhf/allure-results 'SELECT ?risk ?decision ?input WHERE {
  ?r a rdm:Risk ; rdfs:label ?risk ; rdm:residualDecision ?decision .
  OPTIONAL { ?r rdm:controlledBy/rdfs:label ?input } } ORDER BY ?risk'
```

An agent connected to `rdm graph mcp` calls `trace` with a risk id, and gets
the chain, the scores, the residual decision and each controlling design
input with its tests and runs; the agent's trace of a design input lists the
risks it controls. That is the graph's trace: `rdm story trace` takes a user
need or a design input and does not list risks, so from the command line use
the query above.

RDM's own [policy](dhf/documents/risk/policy.md) and
[register](dhf/documents/risk/tool_risks.md) list the ways RDM itself could
misreport evidence; an agent wrote them, so they are proposed until a
maintainer approves them.
