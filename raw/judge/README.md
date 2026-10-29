# raw/judge: what a learned judge rests on

The spec owner's decision (docs/decisions/0002-learned-judge.md): a
nondeterministic item whose placeholder cannot be matched by a shape alone
is judged by a learned judge, a tool like any other (INV-11), built with
DSPy and optimised by GEPA. These are the sources, fetched 2026-10-29 and
never edited.

| Path | From | Pinned at | What it is |
|---|---|---|---|
| `dspy/dspy-README.md` | github.com/stanfordnlp/dspy, `README.md` | tag `3.4.1` (`099982b`) | what DSPy is: declarative programs over language models, compiled by optimizers |
| `dspy/dspy-signatures.md` | same, `docs/docs/learn/programming/signatures.md` | same | a signature: the declared inputs and outputs of a step; Example D is a judge of faithfulness to citations |
| `dspy/dspy-optimizers.md` | same, `docs/docs/learn/optimization/optimizers.md` | same | what an optimizer tunes (instructions, demonstrations, weights) from a metric and examples |
| `dspy/dspy-metrics.md` | same, `docs/docs/learn/evaluation/metrics.md` | same | a metric is a program; a language model may be the judge inside it |
| `dspy/dspy-gepa-overview.md` | same, `docs/docs/api/optimizers/GEPA/overview.md` | same | `dspy.GEPA`: reflective prompt mutation, rich textual feedback as the optimization signal, Pareto-based candidate selection; how a feedback metric is written |
| `dspy/dspy-gepa-GEPA_Advanced.md` | same, `docs/docs/api/optimizers/GEPA/GEPA_Advanced.md` | same | the feedback metric's contract and the optimizer's parameters in detail |
| `gepa-2507.19457.md` | arxiv.org/abs/2507.19457 | fetched 2026-10-29 | GEPA, the paper: learning high-level rules from natural-language reflection on trajectories, outperforming MIPROv2 and GRPO with far fewer rollouts |

A JEPA was named with DSPy and withdrawn the same day (decision 0002,
addendum); its source left this folder.
