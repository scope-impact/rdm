# The harness as a hierarchical Bayesian system

What the harness really estimates is one number per scenario: the
probability that the spec carries that behaviour into a fresh build.
Everything a cycle records (results, verdicts, judge calls, goldens) is an
observation of it, and the hierarchy is the C4 model: system, container,
component, item (decision 0003). This page writes the model down so that
the instrument's questions (`docs/wow/way-of-working.md`, section 7;
INV-6) have a stated form. Cites `docs/wiki/glossary.md` for every term.

## The plates

```
┌─ the instrument, held still ────────────────────────────────┐
│   ω ──► o_n   (golden n matched, every commit)               │
│   y_e         (the judge's goldens: the owner's verdicts)    │
└──────────────────────────────────────────────────────────────┘
        │ conditions every r and v; nothing in a cycle moves it
        ▼
  μ, σ
  └─ containers c
       α_c
       └─ components k
            β_k
            └─ items i            d_i  (deterministic? fixed by the tag)
                 ν_i, g_i
                 └─ cycles t      δ_t
                      z_it        carried in this build
                      ├─ r_ijt    result from tool j      (verify)   ◄ s_j, f_j
                      └─ v_igt    verdict from grader g   (score)    ◄ s_g, f_g
```

Latent: μ, σ, α, β, ν, g, δ, z, s, f, ω. Observed: r, v, o, y. Fixed by the
spec: d. The rendered plate diagram is the artifact "Harness Posterior"
(2026-10-29); this file is its text.

## The generative story

Read it top down, as the harness reads the model: a spec is carried well
or badly overall; each container deviates from that; each component from
its container; each item from its component. A cycle adds a builder's day.
What a tool or a grader reports is the truth seen through that reporter's
reliability.

### The hierarchy (the model's static structure)

```
μ              ~ Normal(0, 1.5)         how well the spec carries the product, logit scale
σ_C, σ_K, σ_I  ~ HalfNormal(1)          spread between containers, components, items
α_c            ~ Normal(μ, σ_C)         container c
β_k            ~ Normal(α_c(k), σ_K)    component k, the unit of regeneration
ν_i            ~ Normal(0, σ_I)         item i, a scenario of component k(i)
```

### The two reasons an item fails

```
g_i   ~ Bernoulli(π)                              the spec is silent where a grader could see: a spec gap
δ_t   ~ Normal(0, σ_T)                            cycle t: this cut, this builder, this room
θ_it  = logit⁻¹(β_k(i) + ν_i + δ_t)               how likely a careful builder carries item i in cycle t
z_it  ~ Bernoulli((1 − g_i)·θ_it + g_i·ρ)         carried, or not; a gap is carried only by luck (ρ small)
```

This is the split the audit made by hand: a failure is either a spec gap
(the item fails in every cycle whoever builds it, until the spec states the
answer) or a builder's (it fails with this cycle's δ and passes with
another's). The posterior over `g_i` is the list of visible gaps, ranked.

### What is observed, and through whom

```
r_ijt  ~ Bernoulli(z_it·s_j + (1 − z_it)·f_j)     a result from tool j in the room's run (verify)
v_igt  ~ Bernoulli(z_it·s_g + (1 − z_it)·f_g)     a verdict from grader g, black box (score)
s_j, s_g ~ Beta(9, 1)                             sensitivity: a carried item is reported as passed
f_j, f_g ~ Beta(1, 9)                             false pass: an uncarried item is reported as passed
unreached verdict ≡ v = fail                      a rule of the instrument, not a likelihood (INV-6)
```

A grader is a person, an agent, or the learned judge. For the judge, `s_g`
and `f_g` are not free: its goldens `y_e` are the owner's past verdicts,
and every `check` observes whether it reproduces them, so its reliability
is pinned by evidence before it grades anything (decision 0002). For a
deterministic item (`d_i = 1`) a verdict is a byte comparison and the
grader's `s`, `f` sit near 1 and 0; for a nondeterministic item they are
what the owner's labelled examples say they are.

### The instrument, outside every plate

```
ω    ~ Beta(50, 1)        the harness is true to its own spec; a tight prior, held still
o_n  ~ Bernoulli(ω)       golden n matched at this check (the oracle)
```

Every `r` and `v` is read conditional on `ω`: a wrong instrument moves them
together, which is why the goldens are checked on every commit (INV-9) and
never re-recorded for a green (INV-3).

## Where each node is read from

| Node | What it is | Observed or fixed by | Record |
|---|---|---|---|
| c, k, i | the plates: containers, components, items | the model and the stamped spec | `spec/model.toml`, `cycles/NN/checklist.toml` |
| d_i | deterministic or not | the `@nondeterministic` tag | checklist item: `nondeterministic`, `placeholders` |
| t | a cycle: cut, builder, room | `cut`, `scopes`, `fence` | `cycles/NN/cycle.toml` (`cut`, `from`, `builder`) |
| r_ijt | a result from tool j | `verify`, from the results folder | `cycles/NN/results/run-N/*-result.json`, label `tool` |
| v_igt | a verdict from grader g | `score`, majority apart | `cycles/NN/verdicts-<g>.toml`, `score.toml` with `by` |
| y_e | the judge's goldens | the owner's accepted verdicts, replayed at `check` | the judge plugin's goldens; `judge-goldens` |
| o_n | the oracle's goldens | `cargo test` and the oracle at every commit | `harness/oracle/`, the pre-commit hook, CI |
| g_i | spec gap | inferred; confirmed when the planner states the gap and the item passes afterwards | the plan's spec gaps; the decisions |
| whole-container | a cycle with every item of a container observed | `scopes` every Nth cycle | `whole-container = true` |

## What the posterior answers

- **How complete is the spec?** logit⁻¹(μ): the probability a fresh,
  careful builder carries an arbitrary item. rdm-spec's whole rebuilds
  (324/325, 341/343) are two draws from this; slice 01 in a new language
  (17/26) is the same quantity with a new δ and the gaps still latent.
- **Which component is weak?** β_k, shrunk toward its container. A
  component with few items borrows strength from its siblings, so one bad
  cycle does not condemn it; a component that fails across cycles is
  pulled down for real.
- **Gap or builder?** P(g_i = 1 | r, v across cycles). An item that fails
  under every δ_t is a gap; one that flips with δ_t is a builder's. This
  ranks the visible gaps without a person reading the spec.
- **Did the cycle move both ways?** P(z_it > z_i,t−1) and P(z_it < z_i,t−1)
  on the shared items, never their difference: the scoreboard's rule, as a
  pair of posteriors.
- **How much to trust a grader?** s_g and f_g per grader, pinned for the
  judge by its goldens, learned for an agent from its agreement with the
  person. A majority of three is a weighted vote with these as the weights.
- **Is a whole-container cycle due?** When the posterior on a container's
  items that no scoped cycle has touched grows wide, rebuilding it whole is
  what narrows it. The fixed every-Nth rule is the floor; this is the
  signal.

What this model refuses to do, on purpose: it never feeds a posterior back
into a build. A builder sees the cut and the room; the posterior is the
instrument's, reported to a person once a cycle. The arrow from ω into the
product plates goes one way.

## Priors worth arguing about

- **Beta(9,1) sensitivity and Beta(1,9) false pass** for tools and agents
  say a reporter is right about nine times in ten before any evidence.
  Löyly's failure modes (a gate that passed with no test run; a runner that
  forgot a suite; `raw/loyly/harness/README.md`, "The failure modes") are
  the reason f is not zero: a false pass is the lie the harness is built
  against.
- **ρ, the luck of a gap**: how often a builder guesses a silent rule the
  way the grader expects. rdm-spec's planners copied the old implementation
  until the spec-alone rule, which made ρ look high; in a clean room it is
  small.
- **σ_T, the cycle's spread**: one builder per cycle keeps it one number;
  parallel builders made it a merge seam no model could name
  (`raw/rdm/retrospective-01-12.md`, section 3).
- **π, the prior rate of gaps**: the audit found 36 in 20 rules of a
  hand-written spec. Start near a fifth; let the cycles move it.

## Status

A statement of what the instrument measures, not a tool. Fitting it (the
scoreboard reporting posteriors instead of rates) is a plan of its own,
after cycles exist to fit it to; rdm-spec did this once by hand
(`dhf/cycles/posterior.md` on the old branch, not under `raw/`).
