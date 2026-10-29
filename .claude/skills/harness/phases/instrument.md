# Phase: instrument

You fit the instrument's model to the cycles' records and report what it
answers. You read `cycles/`, the model and the checklist; you write one
file, `cycles/NN/posterior.toml`; you change no record and no spec. The
model is `docs/wow/posterior.md`, which you read whole before fitting:
the hierarchy, the gap-or-builder mixture, reporters with s > f by
construction, anchors, the instrument's ω.

## Steps

1. **Assemble the data from the records, never by hand.**
   - items → components → containers: `cycles/NN/checklist.toml` and
     `spec/model.toml`;
   - anchors: items without `nondeterministic` in the checklist; their
     truth is the byte comparison the harness made, not a grader's word;
   - results: `cycles/*/verified.toml`, the worse of the two runs per
     item, labelled by `tool`;
   - verdicts: `cycles/*/verdicts-*.toml`; graders sharing a `model` are
     one voice, so merge them before anything else;
   - the judge's anchors: its plugin's goldens, as hits and misses on
     carried and uncarried items;
   - the builder of each cycle: `cycles/*/cycle.toml`, `builder.model`.
   Complete when every index array is traceable to a file and a key, and
   nothing in the data was typed.
2. **Fit.** The model as `docs/wow/posterior.md` states it, in the
   probabilistic program under `instrument/` (held still with its own
   goldens, like a plugin). Complete when the sampler's diagnostics pass
   (no divergences, R-hat below 1.01 on every parameter) or the report says
   which failed.
3. **Check the fit before trusting it.** A posterior predictive check on
   pass counts per component and per grader. A failed check sets
   `fit = "refused"` in the output and the scoreboard falls back to raw
   counts; you do not tune priors to pass it.
4. **Report**, in `posterior.toml`, keys in this order:
   - `completeness`: the interval on logit⁻¹(μ), not a point;
   - `components`: per component, β_k's interval and the probability it
     sits below the median;
   - `gaps`: items ranked by P(g_i = 1), with component and the cycles
     observed; this is the planner's input;
   - `shared`: per shared item, P(better) and P(worse) against the
     previous cycle, both, never a difference;
   - `graders`: per voice, s, f and Youden's J = s − f with intervals;
     a J interval touching zero is flagged `uninformative = true`;
   - `anchors`: how many deterministic items each component had; a
     component with none reports its graders' (s, f) as `prior-only`;
   - `whole-container-due`: the cycle the posterior width on a container's
     untouched items asks for, beside the fixed every-Nth;
   - `fit`: `passed` or `refused`, with the check that failed.

## What you never do

- Feed anything from `posterior.toml` to a builder or into a room: the
  fence removes `cycles/` and you do not route around it.
- Absorb a hard item into a parameter. A high P(gap) is a finding for the
  planner to state in the spec, not a difficulty to weight away.
- Re-record a golden, edit a verdict, or drop an unreached item to improve
  a fit.

## Completion

`cycles/NN/posterior.toml` exists with every key above, `harness
scoreboard NN` reads it, and the report names the top gaps, the
uninformative voices, and whether the fit was refused.
