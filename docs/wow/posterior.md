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
logit(s) = logit(f) + softplus(η);  (η, logit f) ~ MVN   s > f: a reporter is never worse than a coin (2405.19521, eq. 3, in false-pass terms)
η ~ prior centred so that s ≈ 0.9, f ≈ 0.1          the old Beta(9,1), Beta(1,9), now as the prior on η and f
unreached verdict ≡ v = fail                      a rule of the instrument, not a likelihood (INV-6)
two runs of one suite ≡ one observation            the worse run; correlated executions are not two draws
deterministic items: z known                        anchors that identify every grader's (s, f)
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

## What the literature says, and what it changes

Found 2026-10-29 by a search of arXiv for each part of the model; the
abstracts are under `raw/posterior/` with a README that says why each is
there. Four findings, three of which change the model above.

### 1. The model has a name: IRT with sensitivity and specificity

Carpenter and colleagues' hierarchical crowdsourcing model
(`raw/posterior/2405.19521.md`, section 2 of the full text) is Dawid and
Skene's rater model with item effects added: a difficulty β_i, a
discrimination δ_i and a guessability λ_i, so that a rater's probability of
rating item i correctly is λ_i + (1 − λ_i)·logit⁻¹(δ_i(α_j − β_i)), with
α_j the rater's sensitivity or specificity on the log-odds scale. Its
reductions table recovers Dawid–Skene (no item effects) and the IRT 1PL,
2PL and 3PL. Our model is one of its reductions with a hierarchy added on
the items: our ν_i is its −β_i, our ρ is its λ_i restricted to gap items,
our (s, f) are its (α^sens, α^spec) on the probability scale, and our
container and component effects are its "population-level models"
extension (Appendix F). Two things it does that we did not:

- **It excludes adversarial raters by construction**, reparameterising
  α^sens = −α^spec + softplus(η_j) (with specificity = 1 − f this is
  logit s = logit f + softplus(η_j), so s > f), so a rater is always at least
  as informative as a coin; the liar symmetry is removed exactly rather than
  discouraged by a prior. **Change:** the harness's model adopts this. A
  grader's or tool's (s, f) are written as a spec effect and a softplus
  distance from the spammy boundary, with a hierarchical bivariate Normal
  over raters; the asymmetric Beta becomes the prior on that distance
  rather than the thing that breaks the symmetry. Simulation 4 of the
  artifact stays true (flat priors give two peaks) and the fix is now the
  textbook one.
- **It checks fit by posterior predictive checks and leave-one-out**, and
  shows Dawid–Skene underestimates dispersion. **Change:** fitting the
  model, when it comes, reports both; a model that fails its own posterior
  predictive check on the pass counts per component is refused as the
  scoreboard's basis.

### 2. Agents as graders are not conditionally independent

Three papers say that LLM judges violate the independence Dawid–Skene
assumes: they share data, architectures, prompts and failure modes
(`2601.22336`), repeated judgments of one item by one model have
intraclass correlations of 0.50 to 0.93 so ten ratings carry the
information of one or two (`2510.23874`), and with arbitrarily dependent
reporters the prevalence is not identified at all (`2606.15031`). The
harness's graders in their own rooms are independent in what they see, not
in how they think: two agent graders on one model are the correlated case.
**Changes:**

- Two agent graders sharing a model are one grader with two draws, not
  two graders. `score`'s majority counts them as one voice, and the model
  carries one (s, f) per distinct model, not per run.
- The two runs of a suite in `verify` are correlated executions of one
  build (`2208.07935` is the software-reliability statement of the same
  point); the model reads the pair as one observation, the worse run, not
  as two.
- What identifies the reporters is not vote counts but external anchors
  (`2606.15031`: "identifying power comes from externally calibrated
  scores and events"). The harness has two kinds already and the model
  should use them as such: **deterministic items, whose truth a byte
  comparison settles, are labelled anchors for every grader's (s, f)**, and
  the judge's goldens pin its own. This is the Hui–Walter identifiability
  problem (one population, few tests; Jones et al. 2010) solved by
  anchors rather than by five reporters.

### 3. Judge calibration confirms the shape and adds one estimator

Calibrated weighted majority voting with per-judge false-positive and
false-negative rates estimated from inter-judge disagreement alone
(`2609.12002`) is our majority vote with (s, f) as the weights, and shows
it can be run without labels; the same paper finds that more capable
examinees receive more lenient judgments from every judge, which for us
means a later, better build is judged more leniently than an earlier one
and the scoreboard's "both directions" must be read with that bias in
mind. The hierarchical Bayesian corrector with partial pooling across
rubrics (`2605.09227`) is our pooling across components, and its finding
that the linear corrector saturates while a flow keeps learning past
~1500 anchors is the budget rule for when the judge (decision 0002)
should move beyond a calibrated linear map. Youden's J (`2605.06939`,
`2510.23874`) predicts how well the latent state is recovered; **change:**
the scoreboard reports each grader's J = s + f' − 1 beside its verdicts,
so a grader near zero is visibly uninformative.

### 4. Regeneration as a measure has company, and one warning

A neutral textual specification as the pivot of code-to-code reengineering,
with a transformation loss estimated by graph similarity and prompts tuned
with DSPy (`2605.25232`), is the method's shape in another field; our loss
is the checklist's pass rate, graded black box, which is a behavioural
rather than structural distance. Completeness measured against
behaviour-changing mutants (`2509.09917`) is the same idea as grading
against a checklist derived from scenarios. The warning is `2608.21208`:
a specification is not agent-neutral; a spec written through one agent
degraded badly when another consumed it, and specification size did not
predict implementation quality. For the harness this says δ_t must carry
the builder's identity, and that "the spec carries the product" is a claim
about the spec and a builder population, which is why the second
implementation (Löyly's `oracle.sh py` and `oracle.sh bb`) is the test and
not a replacement.

### What did not change

The hierarchy on items (containers and components) is ours and has no
direct precedent in these papers beyond the population-level extension;
the gap-or-builder mixture is ours (IRT's guessing parameter is the
nearest relative); unreached-as-fail stays a rule; the instrument outside
every plate stays. No paper argues for feeding the posterior back into the
builder, and two (`2605.06939`, `2606.15031`) show calibration can make an
estimate worse when the anchors are few or the reporters dependent, which
is the harness's reason for keeping the instrument out of the loop.

## Status

A statement of what the instrument measures, not a tool. Fitting it (the
scoreboard reporting posteriors instead of rates) is a plan of its own,
after cycles exist to fit it to; rdm-spec did this once by hand
(`dhf/cycles/posterior.md` on the old branch, not under `raw/`).
