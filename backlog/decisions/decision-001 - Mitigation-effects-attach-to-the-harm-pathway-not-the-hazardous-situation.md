---
id: decision-001
title: 'Mitigation effects attach to the harm pathway, not the hazardous situation'
date: '2026-08-27 06:44'
status: accepted
---

## Context

The target ALM/risk data model left open whether `MEASURE_EFFECT` references
`PROBABILITY_OF_HARM` — one specific harm pathway — or `HAZARDOUS_SITUATION` as
a whole, noting that retrofitting either direction is painful.

The estate cannot answer it. Measured across all 34 scored risks (20
halla-health-infra, 14 halla-health wallet): 34 distinct hazardous situations
and **zero pairs sharing one**, no pair reaching even 0.40 token overlap on the
situation text. Every situation in current practice carries exactly one harm,
which makes the two options **isomorphic on every row that exists today**.

## Decision

`MEASURE_EFFECT` references `PROBABILITY_OF_HARM`.

Decided on intent rather than evidence: one-situation-many-harms is a modelling
goal, so the 1:1 shape of the current registers is under-modelling rather than a
true description of the domain. Given that, harm-pathway granularity is the only
non-lossy choice, and the extra rows are accepted up front.

An effect may target `p1_level` or `p2_level`, never severity — severity is a
property of the harm, so a measure that changes it is changing which harm can
occur, which is a different pathway. `p2_level` is optional — and see the process
constraint below, which is stronger than "optional".

## Consequences

- Where a situation has one harm the two designs are indistinguishable, so this
  decision buys nothing measurable today. It is a bet that registers will be
  authored with several harms per situation. If that never happens,
  `PROBABILITY_OF_HARM` remains an unexercised table.
- The 34 existing risks are under-modelled by this decision's own reasoning.
  Re-modelling them is deliberately **out of scope** for the map that produced
  this record.
- Effects are reducing-or-neutral, never signed. An aggravating consequence is a
  different hazard and is recorded as its own risk, so the register cannot
  silently net two harms against each other.
- Recorded residual scores are provisional. Effects are not backfilled to
  reproduce them; residual is recomputed once effects exist. The 14 initial
  scores already recompute exactly from the RMP-001 matrix, while the residuals
  have never been checked by anything.

## Process constraints found after acceptance

Checked against `RMP-001` (Risk Management Process, revision 2, Scope Impact) in
the `documentation` repository, which governs risk management for this estate.
Two constraints qualify this decision; neither reverses it.

**`p2` has no basis in the governing process.** `RMP-001` defines a *single*
probability with four levels (Rare / Unlikely / Possible / Likely) and instructs
scoring "the probability of the specific situation, not the abstract hazard".
That single probability **is** `p1`. There is no `p2` concept anywhere in the
document. So this decision does not merely make `p2` optional — it adopts a
probability model the process does not define, and anyone authoring a `p2` today
would have no level definitions to score it against. Making the decomposition
usable requires **revising `RMP-001`**, a governed document change in another
repository. Recorded as an out-of-scope dependency on the map, not as schema
work.

**A computed residual is a proposal, not the residual.** `RMP-001` "Evaluate
Residual Risk" applies **ALARP** and requires recording why further reduction is
not reasonably practicable, what monitoring will detect issues, and **who
approved the acceptance**. The process's residual therefore contains an
acceptance decision. A folded `MEASURE_EFFECT` chain can propose a value; it
cannot be that residual. So "residual is recomputed" above should be read as
*recomputed as a proposal, with the accepted value and its approver recorded
separately*. This constrains the fold algorithm rather than this decision.

**One point the process strengthens.** "Verify Controls" already requires that
controls "do not introduce new risks". The reducing-or-neutral rule above was
adopted weakly held; the process already forbids an aggravating control outright,
so an aggravating consequence is a verification failure rather than a negative
effect row. Firmer ground than originally recorded.
