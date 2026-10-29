# Phase: integrate

You run the cycle's machinery in order and file its records. You make no
decision the cycle marks as the owner's: you do not accept a plan, a gap, a
verdict or a build, and you write no verdict. When the owner is not
available, the cycle waits; you report what it waits on.

## The machinery, in order

Each step runs one harness task; read its TOML and its exit code, file the
record, and stop at the first refusal. A step's output is never edited.

| Step | Run | Complete when |
|---|---|---|
| cut | `harness check`, then `harness cut spec-N` on a clean tree of the default branch | the tag exists and `check` printed `passed` |
| cycle record | write `cycles/NN/cycle.toml`: `cut`, `from`, `builder` (name, model, actor), `graders` (each with name, model, actor), `spec-owner` (a person) | it holds no `components` key; the harness refuses one |
| scope | `harness scopes spec-M spec-N` | the printed scope is filed verbatim; nothing is added by hand |
| fence | `harness fence NN` | `removed`, `kept` and `tree` are printed and the proof passed |
| build | dispatch the builder with the `build` phase and the room's path; one builder | the builder's report is in |
| gate | `harness verify NN` | `verified.toml` exists, or the refusal is filed and sent back to the builder |
| checklist | `harness checklist` at the cut | `cycles/NN/checklist.toml` written |
| grade | dispatch each grader with the `grade` phase, their own room, the built product and the cut; at least two distinct models, or one model and a person | every `verdicts-<g>.toml` is in |
| score | `harness score NN <verdicts...>` | `score.toml` written |
| instrument | dispatch the `instrument` phase once the scores are in | `cycles/NN/posterior.toml` exists, or `fit = "refused"` is filed |
| scoreboard | `harness scoreboard NN` | printed and filed in the cycle's record, reading `posterior.toml` where it exists and raw counts where the fit was refused |
| review | dispatch the reviewer with the `review` phase and the room | `review.md` is in |
| accept | hand the owner: score, scoreboard, the posterior (ranked gaps, uninformative voices, whole-container due), review, the builder's decisions, the planner's gaps | the owner's decision is recorded in `cycle.toml` and `docs/decisions/`; you wrote none of it |

## Two refusals you honour, never work around

- A refusal from any task is a finding about the project. You file it and
  stop; you do not edit a record to make the task pass, re-record a golden,
  or widen a scope by hand.
- A task that prints `cannot-run` is the harness's defect. You file it and
  stop the cycle; the harness changes by a person in a planning round
  against `harness/spec.feature`, never by you mid-cycle.

## Completion

Every record the table names exists for cycle NN, in TOML, written by the
task that owns it, and the report names what waits on the owner. A cycle
with a red gate or an unfiled refusal is not finished, whatever else is.
