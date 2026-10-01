---
id: SDS-RISK-001
kind: design
context: risk
satisfies: [UN-016, UN-003]
design_inputs:
  - id: DI-43
    text: "RDM shall read the risk register from the frontmatter of kind: risk documents — for each risk its id, hazard, situation, harm, severity, probability, controls (design input ids), residual probability (severity is the harm's and carries over), and acceptance (who and why) — and compute each risk's initial and residual level from the project's risk matrix: the default four-by-four, or a risk_matrix declared in a document's frontmatter."
    traces_to: [UN-016]
  - id: DI-44
    text: "The release gate shall block on a risk id declared twice, and on any risk with an empty hazard, situation or harm; a severity or probability the matrix does not define; a recorded level other than the matrix's; an initial level above Low and no control; a control that is not a declared design input; controls but no residual score; a residual level of Block; or a residual level of Medium or High with no acceptance saying who accepted it and why."
    traces_to: [UN-016, UN-003]
---

# Risk — Software Design

## Design Inputs

This context owns the risk register as part of the record. The method is the
risk-analysis skill's (hazard → situation → harm, severity × probability,
controls, residual risk); this context keeps the register and checks what a
machine can check. Whether a control is real in the code, and whether a
residual is as low as reasonably practicable, stay human judgements.

- **DI-43 (read and score the register)** — risks are frontmatter in
  `kind: risk` documents anywhere under the DHF, like design inputs:

  ```yaml
  risks:
    - id: RISK-TOOL-001
      hazard: "What could go wrong"
      situation: "How it comes about"
      harm: "Who is hurt, and how"
      severity: Serious          # from the harm
      probability: Possible      # from the situation
      level: High                # optional; must be what the matrix says
      controls: [DI-40]          # each control is a design input
      residual: {probability: Rare}
      acceptance: {by: "...", rationale: "..."}   # residual Medium or High
  ```

  A control is a design input, so the chain is risk → design input → tagged
  test → result with no new kind of thing. The residual records probability
  only: severity belongs to the harm, so a control that changed it would be
  controlling a different harm (settled in RDM-004, decision-001). Levels are
  looked up, never typed: the default matrix is the risk-analysis skill's
  four-by-four, and a project declares its own as `risk_matrix`
  (`severities`, `probabilities`, and a row of levels per severity) in any
  DHF document's frontmatter. A matrix uses the four level names Low,
  Medium, High and Block, because the gate rules are stated in them.
  Refines UN-016.
- **DI-44 (release gate)** — the mechanical half of the risk-analysis
  review: a duplicate id, a broken chain, an unscored or mis-scored risk, a
  risk above Low with nothing controlling it, a control that is not a
  declared design input, a controlled risk with no residual, a residual of
  Block, and an accepted Medium or High residual with no one named as
  accepting it. Each control being a design input, the existing rule that
  every design input has a passing test covers "every control is verified".
  Refines UN-016 and UN-003.

## Design Outputs

- `rdm/record/risk.py` — `read_matrix`, `risks(dhf)` (each risk with its
  computed initial and residual level) and `blocking(dhf)` (DI-44's
  findings), dependency-light like the rest of `rdm/record/`.
- `run_release_gate` (`rdm/story_audit/design_gate.py`) adds those findings
  to its blocking list.

Acceptance criteria are verified by `@allure.story("DI-43" / "DI-44")` tests.
