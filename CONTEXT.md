# RDM

RDM keeps the design record of regulated health software as Markdown and tests
in git: user needs, the design inputs that meet them, the risks and the controls
that reduce them, and the evidence that each was verified. This glossary is the
language of that record, aligned with the requirements and risk-analysis skills.

## Needs and requirements

**User need**:
What an intended user must be able to do, and the result they need, stated
without reference to a solution. It is validated, not verified.
_Avoid_: user story, feature, epic

**Design input**:
A uniquely identified `shall` requirement on the system or one of its
subsystems, owned by one bounded context and traced to the user need it refines.
It is an acceptance criterion: the requirements skill's "acceptance criterion"
and "subsystem `shall` requirement" are both design inputs.
_Avoid_: spec item, story, ticket, the test

**Baseline acceptance criterion**:
A design input that follows from a user need alone and mentions no risk.
_Avoid_: functional requirement, happy path

**Risk-based acceptance criterion**:
A design input allocated as a risk control. It counts as met only when it is
verified and the residual risk of the risk it controls is acceptable.
_Avoid_: safety requirement, mitigation requirement

**Bounded context**:
The unit of design that owns design inputs; what the requirements skill calls a
subsystem. Exactly one design document describes each.
_Avoid_: module, component, service

**Realising context**:
A bounded context that implements part of a design input another context owns.
It does not own the input and is not where the input is declared.
_Avoid_: co-owner, contributor

**Task acceptance criterion**:
A checklist item on a planning task. Planning, never the record: it is not a
design input and nothing verifies it.
_Avoid_: acceptance criterion (unqualified), requirement

## Verification and validation

**Test**:
An automated check tagged with the design input it verifies. It verifies the
acceptance criterion; it is not the criterion.
_Avoid_: acceptance criterion, scenario, spec

**Verification step**:
One checked clause of a test, with its own result; a test is the ordered set of
its verification steps.
_Avoid_: acceptance criterion, assertion, sub-test

**Test run**:
One execution of a test, at one commit, by one executor.
_Avoid_: build, job, test case

**Verified**:
Said of a design input with a passing run of a test tagged with it and no
failed one. It shows the requirement is met, not that the test proves it.
_Avoid_: validated, tested, done

**Release-grade evidence**:
Verification in which every design input is verified, no run failed, and every
run tested the record's commit with no uncommitted changes.
_Avoid_: green build, passing CI

**Validated**:
Said of a user need whose intended users, in its intended use, were shown to
reach its result.
_Avoid_: verified, accepted

## Risk

**Risk analysis**:
Identifying the hazard, the sequence of events, the hazardous situation, the
harm, and the estimate of its probability and severity.
_Avoid_: risk assessment (for this step alone)

**Risk evaluation**:
Comparing a risk estimate with the risk acceptability criteria, giving
acceptable or unacceptable. Without approved criteria it is blocked, not
guessed.
_Avoid_: risk scoring, risk rating

**Risk acceptability criteria**:
The project's own severities, probabilities, levels and the acceptability of
each level, approved before any risk is evaluated against them. Not a universal
matrix.
_Avoid_: standard risk matrix, ISO matrix

**Hazardous situation**:
A circumstance in which people, property or the environment are exposed to one
or more hazards. Not the harm, and not the cause.
_Avoid_: hazard, threat, scenario

**Harm**:
The injury or damage that a hazardous situation can lead to. Defined once and
referenced, so its severity cannot diverge between the risks that share it.
_Avoid_: impact, consequence

**Harm pathway**:
One pair of a hazardous situation and a harm, carrying its own probability.
One situation can have several, each with its own severity and probability.
_Avoid_: risk line, risk row

**STRIDE threat**:
A security threat classified as spoofing, tampering, repudiation, information
disclosure, denial of service or elevation of privilege. It complements safety
analysis and may link to a safety risk; it does not replace it.
_Avoid_: vulnerability, attack (for the classified threat)

**Risk control**:
Something done to reduce a risk, allocated to a design input that implements it.
Defined once and referenced from every harm pathway it bears on.
_Avoid_: measure, mitigation, safeguard

**Control effect**:
What one risk control does to one harm pathway's probability, whether relative
or absolute, and where it sits in the order controls are applied.
_Avoid_: measure effect, reduction, impact

**Effective**:
Said of a risk control that is verified and whose risk has been evaluated again
with an acceptable residual. A passing test alone makes a control verified, not
effective.
_Avoid_: controlled, mitigated, done

**Initial risk / residual risk**:
The same assessment at two stages: before any risk control, and after the
applied controls fold together. Stages are rows, not a before-and-after pair of
columns.
_Avoid_: gross risk, net risk, pre/post mitigation

**Proposal**:
A rating, a control, an acceptability criterion or a verification plan that no
person has approved yet. It is shown as a proposal until someone approves it.
_Avoid_: draft, assumption, default
