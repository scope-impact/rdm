# Decisions: slice 01-start-a-project

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `command.feature`, Feature: Command

Kernel component Command: dhf/spec/model.edn, :kernel.

The one entry point a hook, CI, an agent and a person all use: the rdm-spec
command, installed once, with nothing per project. It belongs to no bounded
context: it dispatches each task to the context that owns it.
Specs are written by editing files; the command only checks, stamps, draws,
cuts, fences, runs, verifies, derives checklists, files scores and publishes.
Grading is not a task: a grader grades, however they grade.

### `init.feature`, Scenario: Init takes an absolute path as well as a relative one (spec-0272)

An absolute path leaked an exception (cycle 07, grading).

