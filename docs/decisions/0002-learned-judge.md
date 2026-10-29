# 0002: A nondeterministic item may be judged by a learned judge

> Status: Accepted, 2026-10-29, by the spec owner (Sathish Narayanan): "for
> nondeterministic we do JEPA, DSPy".

## Context

A `@nondeterministic` scenario names what it cannot pin as a placeholder
whose shape is declared once (`harness/spec.feature`, "A scenario is
deterministic unless it says it is not"). A shape as a pattern covers a hash,
a duration, a reading. It does not cover an outcome that is right in many
wordings and wrong in many others: a rendered page, a generated text, a
monitor's trace that must show a command reaching its aggregate, the
evidence a grader writes. Those need a judge, and a judge that is a person
every time does not scale to a cycle.

## Decision

- **A judge is a tool.** It answers a scenario and writes a result in the
  result model, labelled `item` and `tool` (INV-11), like a runner or a
  monitor. It is declared in the configuration as a plugin that answers
  `item`: given a scenario's Then, the placeholder's name and the observed
  outcome, it returns `pass` or `fail` with evidence. The harness never
  reads how the judge decides.
- **A placeholder's shape is a pattern or a judge.** `spec/placeholders.toml`
  declares each placeholder with `shape = "<pattern>"` or `judge = "<name>"`.
  `check` refuses a placeholder naming no declared judge
  (`judge-declared`); the checklist carries the judge's name on the item.
- **The judge is built with DSPy**: a signature declaring its inputs (the
  Then, the placeholder, the outcome) and outputs (a verdict and evidence),
  as DSPy's own example of a faithfulness judge does
  (`raw/judge/dspy/dspy-signatures.md`, Example D); a metric that is itself
  a program (`raw/judge/dspy/dspy-metrics.md`); and an optimizer that tunes
  the judge's instructions and demonstrations against examples
  (`raw/judge/dspy/dspy-optimizers.md`). The examples are **the owner's own
  past verdicts**: every verdict a person gave on a nondeterministic item in
  an accepted cycle is a labelled example, so the judge learns the owner's
  judgment and nothing else.
- **The optimizer is GEPA** (`dspy.GEPA`; `raw/judge/dspy/dspy-gepa-overview.md`,
  `raw/judge/gepa-2507.19457.md`), the spec owner's choice, for a reason
  the harness already supplies: GEPA's metric returns a score **and
  feedback in words**, and reflects on the judge's traces in natural
  language to propose better instructions, keeping a Pareto frontier of
  candidates rather than one best. An owner's verdict is exactly that
  signal: `pass` or `fail` is the score, and the verdict's evidence (what
  the owner saw and why it decided) is the feedback. The feedback metric
  compares the judge's verdict with the owner's and returns the owner's
  evidence, and where they disagree, both reasonings, so the judge learns
  the rule behind the verdict and not the verdict alone. GEPA's recipe for
  feedback (use existing artefacts; decompose outcomes; expose trajectories;
  ground in checks) is met by the cycle's records as they are: the
  checklist, the results with their attachments, the review and the
  decisions.
- **Judgment in embedding space is a JEPA.** Where the outcome is a
  representation rather than a text (a page, a trace, an image of a
  screen), the judge predicts the representation of the expected outcome
  from the scenario and compares the observed outcome's representation to
  it, as I-JEPA predicts the representations of target blocks from a
  context block without generating them (`raw/judge/jepa/ijepa-2301.08243.md`).
  The placeholder's declaration carries the distance the owner accepts
  (`distance = 0.15`); the distance is the shape.
- **The judge is held still** (INV-3). It has goldens: the owner's labelled
  examples it must reproduce. As a plugin answering `check`, it is run on
  them at every `check`; a judge that no longer reproduces an owner's
  verdict refuses the project (`judge-goldens`), and a golden changes only
  when the owner's verdict changes, named in the commit.
- **A judge's verdict is a grader's verdict** (actor: agent). It is scored
  apart from a person's and agreed by majority like any other (INV-10); the
  owner accepts the cycle on the evidence, which records the value seen and
  the judge's reasoning. A judge is never in the gate: `verify` reads a
  tool's result, and a tool that uses a judge inside its own assertion is
  that tool's business.

## Consequences

- The harness's core gains nothing: a judge is a plugin, a placeholder's
  declaration gains a `judge` key, `check` gains `judge-declared` and the
  plugin's `judge-goldens`. The one thing stays one thing.
- The first judge is a plan of its own, after the harness runs end to end,
  on the first product that has a nondeterministic item a pattern cannot
  hold. It needs owner verdicts to learn from, so it comes after cycles,
  never before.
- The harness's own spec declares no judge: its seven placeholders are
  patterns.
