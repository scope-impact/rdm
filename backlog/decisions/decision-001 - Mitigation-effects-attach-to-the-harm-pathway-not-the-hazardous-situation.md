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
occur, which is a different pathway. `p2_level` is optional: requiring it would
force 34 re-scorings before anything works.

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
