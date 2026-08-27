# RDM

RDM is a documentation-as-code tool for regulated medical-device software: it
holds a Design History File as markdown plus executed-test evidence, gates the
record, and renders regulatory documents from it. This glossary covers the terms
that are specific to that domain and are easy to confuse.

## Language

**Design input**:
A single verifiable requirement clause owned by one bounded context, refining a
user need. Carries an id of the form `DI-n`.
_Avoid_: requirement, spec item, acceptance criterion

**User need**:
What a user must be able to do, stated without reference to a solution. The
validation anchor a design input traces to.
_Avoid_: user story, feature

**Bounded context**:
The unit of design. Exactly one design document describes each one, discovered
by its `kind: design` frontmatter rather than by filename or folder.
_Avoid_: module, component, subsystem

**Hazardous situation**:
A circumstance in which people, property or the environment are exposed to one
or more hazards. Not the harm, and not the cause.
_Avoid_: hazard, threat, scenario

**Harm**:
The injury or damage that a hazardous situation can lead to. Defined once and
referenced, so its severity cannot diverge between the risks that share it.
_Avoid_: impact, consequence

**Harm pathway**:
One (hazardous situation, harm) pair, carrying its own probability. Modelled as
`PROBABILITY_OF_HARM`. One situation can have several, each with its own
severity and probability.
_Avoid_: risk line, risk row

**Measure**:
A risk control measure — something done to reduce a risk. Defined once and
referenced from every harm pathway it bears on, rather than restated per risk.
_Avoid_: control, mitigation, safeguard

**Measure effect**:
What one measure does to one harm pathway's probability, carrying whether it is
relative or absolute and where it sits in the applied order.
_Avoid_: reduction, impact

**Initial risk / residual risk**:
The same assessment at two stages — before any measure, and after the applied
measures fold together. Stages are rows, not a before/after column pair.
_Avoid_: gross risk, net risk, pre/post mitigation

**Faithfulness verdict**:
An independent reviewer's recorded judgement that a design input's verifying
test actually verifies it, rather than passing hollowly. Hash-pinned, so editing
the test re-opens the review.
_Avoid_: test review, sign-off
