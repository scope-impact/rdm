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

## Purpose

This context owns the risk register as part of the design record, and the
risk acceptability criteria the register is evaluated against. Its language
is risk analysis and risk evaluation: a risk is a hazard, the hazardous
situation it leads to and the harm that situation can cause — one harm
pathway per risk — with a severity from the harm and a probability from the
situation; a safety risk or a STRIDE threat; the risk controls that reduce
it, each a design input; the initial risk and the residual risk, the same
estimate before and after the controls; and a proposal, a rating or a policy
no person has approved yet. The method is the requirements and risk-analysis
skills' (user need → safety and STRIDE branches; hazard → situation → harm;
evaluate, control, verify, evaluate again). This context keeps the register
and checks what a machine can check.

## Design Inputs

- **DI-43 (read and evaluate the register)** — risks are frontmatter in
  `kind: risk` documents anywhere under the DHF, as design inputs are in
  `kind: design` ones:

  ```yaml
  risks:
    - id: RISK-TOOL-001
      category: safety             # or security, with stride:
      linked: [RISK-TOOL-002]      # optional: risks it bears on
      hazard: "What could go wrong, and the sequence of events"
      situation: "The hazardous situation"
      harm: "Who is hurt, and how"
      severity: Serious            # from the harm
      probability: Possible        # from the situation
      level: Medium                # optional; must be the policy's level
      controls: [DI-40]            # each control is a design input
      residual: {probability: Rare}  # severity optional: the initial's
      acceptance: {by: "...", rationale: "..."}
      status: proposed             # until a person approves the rating
  ```

  Risk evaluation needs risk acceptability criteria set before the
  decision, so there is **no default**: a project declares one
  `risk_policy` (severities, probabilities, a level for each pair, and per
  level `acceptable`, `justify` or `unacceptable`) in a DHF document's
  frontmatter. Level names are the project's own. A risk control is a
  design input, so the chain is risk → design input → tagged test → result.
  A residual may record a severity where a control limits the harm itself
  (ISO 14971 allows it); without one the initial severity carries over.
  Scoped here because the register and its criteria speak this context's
  language; the gate that applies the rules is DI-44's. It leaves out how
  the register is rendered or projected (publishing, graph). Amended
  (Design Review 10): no default policy, acceptability as policy data,
  safety and security branches, residual severity allowed. Refines UN-016.
- **DI-44 (release gate)** — the mechanical half of a risk review:
  criteria present; ids unique and present; chain, category and STRIDE
  complete; links to declared risks; scores defined by the policy and a
  recorded level equal to the policy's; controls declared, with a residual
  score. A register with no risks needs no policy. Scoped here because the
  rules are rules about the register; the release gate only reports them.
  It leaves out the residual rules (DI-50) and every judgement a machine
  cannot make (see Out of scope). Amended (Design Review 10); narrowed
  (Design Review 12), with the residual rules split into DI-50. Refines
  UN-016 and UN-003.
- **DI-50 (residual rules)** — split from DI-44 (Design Review 12): a
  residual is *not evaluated*, never assumed, until every risk control's
  design input has a passing test; the residual (the initial risk when
  nothing controls it) blocks when the policy calls it unacceptable; a
  `justify` residual needs an acceptance naming who accepted it and why. A
  proposed risk or policy is a warning, as a missing validation record is:
  a person has not yet approved those ratings. A risk with no `status` takes
  its document's. Refines UN-016 and UN-003.

## Design Outputs

- **Risk register** (`rdm/record/risk.py`) meets DI-43, DI-44 and DI-50,
  and is the one place the risk rules are written:
  - `read_policy` reads the first `risk_policy` by path, or none; a
    malformed one raises an error naming its document. The policy's status
    is its document's `status`.
  - `risks(dhf, policy)` reads every `kind: risk` entry and evaluates its
    initial and residual levels against the policy (none without one). A
    risk with no controls and no residual score keeps the initial level as
    its residual.
  - `residual_decision` gives a risk's residual decision: *not evaluated*
    (no policy, no residual level, or a control without a passing test),
    *acceptable*, *accepted* (a `justify` level with who and why), *needs
    acceptance*, or *unacceptable*. Only the first two let a release
    through.
  - `assess` returns the register and every finding of DI-44 and DI-50,
    each blocking or a warning and tied to the risk it is about. A
    malformed policy is one blocking finding and the register is not
    checked further until it is fixed. A risk with a `status` other than
    `proposed` or `approved` also blocks.
  - `findings` splits those into the release gate's blocking messages and
    warnings.
- Realised elsewhere: the release gate (`run_release_gate`, today in the
  **Design and release gates** component of `specification`, which declares
  `realises: [DI-44, DI-50]`) adds the findings to its blocking list and its
  warnings. The **Projection** (graph) carries the same findings into the
  `risks` named graph for its shapes (DI-45, owned by `graph`), so the gate
  and the shapes cannot disagree.
- This context realises no input another context owns.

The design inputs are verified by the tests tagged `@allure.story("DI-43")`,
`"DI-44"` and `"DI-50"` in `tests/acceptance/test_risk.py`.

## Components (C3)

![Components: risk](../../c4/views/C3_risk.svg)

| Component | Responsibility | Technology | Code |
|-----------|----------------|------------|------|
| Risk register | Reads the risk policy and the register, evaluates each risk against the policy, and writes the release rules as findings | Python | `rdm/record/risk.py` |

The view also shows the components of other contexts that use the register,
and the one it uses:

- Risk register **reads frontmatter with** the Record kernel
  (`specification`): it parses each document's frontmatter with the
  specification reader's `_frontmatter_of`. It takes the declared design
  input ids and the verified ones as arguments, so it reads no other part
  of the specification.
- Design and release gates (`specification`) **applies the risk rules of**
  the Risk register: the release gate reports its findings.
- Projection (`graph`) **reads risks and findings with** the Risk register:
  the policy, the register, each residual decision and the findings.
- Verification report (`publishing`) **reads risks with** the Risk
  register: each risk's status and residual decision, beside the design
  inputs that control it.
- pytest plugin (`test_evidence`) **reads risks with** the Risk register:
  the risks each design input controls, to label its test runs.

Assumption: one harm pathway per risk, with one probability; no control
effect is recorded apart — the residual is all the controls folded
together. Design Review 9 kept the register this small; each can grow later
without changing a register written to this format.

## Dependencies

Under the dependency rule `risk` sits beside `test_evidence`,
`architecture` and `compliance`, above `specification` and the shared
kernel, and below `release`.

- **Depends on:** `specification`, for the frontmatter parser
  (`rdm.record.sdd._frontmatter_of`). The direction is allowed, but the
  parser is the shared kernel's in all but location and is private to the
  specification reader; moving frontmatter parsing to the kernel
  (`rdm/kernel/`) makes `risk` depend on the kernel only.
- **Depended on by:** `release` (the release gate, whose code is still
  in `specification`'s component — see below), `graph` (the
  projection) and `publishing` (the verification report) — all above it,
  as the rule allows — and by `test_evidence` (the pytest plugin), a
  context at the same level.

Imports that break the rule:

- `specification` → `risk`: the release gate, in
  `rdm/gates/design_gate.py`, imports `findings`, and `risk` imports the
  specification's frontmatter parser — one of the two cycles
  `architecture.md` names. Moving the release gate to `release` removes the
  upward import, and moving the parser to the kernel removes the other
  half.
- `test_evidence` → `risk`: the pytest plugin imports `risks` to label each
  run with the risks its design input controls. The rule allows imports
  only from below, so a peer import breaks it. Removing it means the
  plugin labels runs with the record's ids only and the read models
  (graph, publishing), which already read the register, join each run to
  its risks.

## Out of scope

Whether a risk control is effective is shown in the record only as far as a
machine can see it: the control is verified and the risk, evaluated again,
has an acceptable or accepted residual. Whether the control does what it
claims in the code, whether the rating is right, and whether a residual is
as low as reasonably practicable stay human judgements, made in review and
recorded by approving the rating (`status: approved`).
