---
id: SDS-RISK-001
kind: design
context: risk
design_inputs:
  - id: DI-43
    text: "RDM shall read the risk register from the frontmatter of kind: risk documents — for each risk its id, category (safety or security), STRIDE category for a security risk, linked risks, hazard, situation, harm, severity, probability, controls (design input ids), residual severity and probability (severity defaults to the initial one), acceptance (who and why) and status (proposed or approved) — and evaluate each risk only against a risk policy the project declares (severities, probabilities, a level for each pair, and for each level whether it is acceptable, acceptable only with a recorded justification, or unacceptable), shipping no default policy."
    traces_to: [UN-016]
  - id: DI-44
    text: "The release gate shall block when the register has risks and no risk policy is declared, and on a risk id declared twice or missing, an empty hazard, situation or harm, a missing or unknown category, a security risk without a STRIDE category, a link to an undeclared risk, a severity or probability the policy does not define, a recorded level other than the policy's, a control that is not a declared design input, or controls with no residual score."
    traces_to: [UN-016, UN-003]
  - id: DI-50
    text: "The release gate shall block a risk whose residual is not evaluated because a control has no passing test, whose residual — the initial risk when nothing controls it — the policy calls unacceptable, or that the policy accepts only with justification and no acceptance saying who accepted it and why; a proposed risk or policy shall be a warning."
    traces_to: [UN-016, UN-003]
---

# Risk — Software Design

## Design Inputs

This context owns the risk register as part of the record. The method is the
requirements and risk-analysis skills' (user need → safety and STRIDE
branches; hazard → situation → harm; evaluate, control, verify, re-evaluate).
This context keeps the register and checks what a machine can check.
Whether a control is effective, and whether a residual is as low as
reasonably practicable, stay human judgements.

- **DI-43 (read and evaluate the register)** — risks are frontmatter in
  `kind: risk` documents anywhere under the DHF, like design inputs:

  ```yaml
  risks:
    - id: RISK-TOOL-001
      category: safety             # or security, with stride:
      hazard: "What could go wrong, and the sequence of events"
      situation: "The hazardous situation"
      harm: "Who is hurt, and how"
      severity: Serious            # from the harm
      probability: Possible        # from the situation
      controls: [DI-40]            # each control is a design input
      residual: {probability: Rare}          # severity: optional, defaults to the initial
      acceptance: {by: "...", rationale: "..."}
      status: proposed             # until a person approves the rating
  ```

  Evaluation needs criteria set before the decision, so there is **no
  default**: a project declares one `risk_policy` (severities,
  probabilities, a level for each pair, and per level `acceptable`,
  `justify` or `unacceptable`) in a DHF document's frontmatter. Level names
  are the project's own. A control is a design input, so the chain is risk →
  design input → tagged test → result. A residual may record a severity
  where a control limits the harm itself (ISO 14971 allows it); without one
  the initial severity carries over. Refines UN-016.
- **DI-44 (release gate)** — the mechanical half of a risk review:
  criteria present; ids unique; chain, category and STRIDE complete; scores
  defined by the policy; controls declared and **verified** — a control
  whose design input has no passing test leaves the residual *not
  evaluated*, never assumed; the residual (or the initial risk, when nothing
  controls it) acceptable, or accepted with who and why where the policy
  asks for a justification. `status: proposed` on a risk or on the policy
  is a warning, as a missing validation record is: a person has not yet
  approved those ratings. Refines UN-016 and UN-003.

- **DI-50 (residual rules)** — split from DI-44: a residual is not
  evaluated until every control has a passing test; an unacceptable residual
  blocks; a `justify` residual needs an acceptance naming who and why; a
  proposed risk or policy warns. Refines UN-016 and UN-003.

## Design Outputs

- `rdm/record/risk.py` — `read_policy`, `risks(dhf)` (each risk with its
  evaluated levels), `residual_decision` and `findings(dhf, …)` (DI-44's
  blocking findings and warnings), dependency-light like the rest of
  `rdm/record/`.
- `run_release_gate` (`rdm/gates/design_gate.py`) adds those findings
  to its blocking list and warnings.

Acceptance criteria are verified by `@allure.story("DI-43" / "DI-44")` tests.

## Components (C3)

The components of the `risk` context, each naming the code that
implements it; a component of another context is shown external, where this
one depends on it.

```mermaid
C4Component
  title Components: risk
  Container_Boundary(rdm_cli, "rdm") {
    Component(risk_register, "Risk register", "Python", "Risks scored from the policy; the release rules", $link="rdm/record/risk.py")
  }
  Component_Ext(record_readers, "Record readers", "Python")
  Rel(risk_register, record_readers, "reads frontmatter with")
```
