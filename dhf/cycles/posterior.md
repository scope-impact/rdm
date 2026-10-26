# What the graded cycles say, as beliefs

Written by `bb dhf/cycles/analysis/posterior.clj dhf/cycles` on 2026-10-26,
over the 14 graded cycles (02 to 11, 13 to 16): 1,376 verdicts, 11 fails.
Cycle 12 was never graded; cycle 17 was still being graded.

**The model:**
- **What it estimates:** θ, the probability that a build passes a scenario.
- **How it pools:** scenarios within their component, components within
  their context. Each level's posterior is the next level's prior, at a
  fixed strength (κ = 10).
- **What each verdict counts as:** one draw of θ.
- **What it leaves out:** grader error. No item has been graded twice, so
  grader error cannot be identified yet.

**How to read it:**
- **Posterior mean and 95% lower bound:** what the verdicts support about θ,
  with their width.
- **"P(every item of the slice right)":** the predicted chance that a fresh
  build of the same slice passes every item, on that cycle's evidence alone.
  It is about 50% even for a perfect cycle: one all-pass build of 107 items
  is weak evidence that the next build will be.
- **The last table:** the scenarios where one more grading would move belief
  most. They make the sample for a grading budget smaller than "everything".

## Per cycle: P(a scenario of the slice passes), flat prior

| Cycle | Graded | Fails | Posterior mean | 95% lower bound | P(every item of the slice right) |
|---|---|---|---|---|---|
| 02 | 180 | 1 | 98.9% | 97.4% | 25.1% |
| 03 | 105 | 2 | 97.2% | 94.2% | 12.5% |
| 04 | 102 | 0 | 99.0% | 97.1% | 50.2% |
| 05 | 74 | 0 | 98.7% | 96.1% | 50.3% |
| 06 | 71 | 0 | 98.6% | 95.9% | 50.3% |
| 07 | 41 | 1 | 95.3% | 89.2% | 25.3% |
| 08 | 122 | 0 | 99.2% | 97.6% | 50.2% |
| 09 | 107 | 0 | 99.1% | 97.3% | 50.2% |
| 10 | 62 | 0 | 98.4% | 95.4% | 50.4% |
| 11 | 238 | 1 | 99.2% | 98.0% | 25.1% |
| 13 | 107 | 0 | 99.1% | 97.3% | 50.2% |
| 14 | 31 | 0 | 97.0% | 91.1% | 50.8% |
| 15 | 38 | 1 | 95.0% | 88.4% | 25.3% |
| 16 | 98 | 2 | 97.0% | 93.8% | 12.5% |

## Per bounded context, all cycles pooled

| Context | Graded | Fails | Posterior mean | 95% lower bound |
|---|---|---|---|---|
| agency | 8 | 0 | 90.0% | 71.7% |
| architecture | 84 | 0 | 98.8% | 96.5% |
| compliance | 147 | 2 | 98.0% | 95.8% |
| kernel | 157 | 0 | 99.4% | 98.1% |
| knowledge | 139 | 0 | 99.3% | 97.9% |
| measurement | 159 | 2 | 98.1% | 96.1% |
| project | 69 | 0 | 98.6% | 95.8% |
| publishing | 161 | 1 | 98.8% | 97.1% |
| ratification | 52 | 0 | 98.1% | 94.5% |
| regeneration | 118 | 1 | 98.3% | 96.1% |
| spec | 123 | 0 | 99.2% | 97.6% |
| verification | 159 | 2 | 98.1% | 96.1% |

## The ten least certain components (shrunk to their context)

| Component | Graded | Posterior mean | 95% lower bound |
|---|---|---|---|
| agency/Observe | 8 | 94.4% | 83.8% |
| verification/Results reader | 22 | 96.3% | 89.7% |
| measurement/Scoreboard | 23 | 96.4% | 90.0% |
| regeneration/Scopes | 38 | 97.6% | 93.2% |
| compliance/Audit copy | 82 | 97.6% | 94.5% |
| verification/Status | 57 | 98.2% | 95.0% |
| measurement/Scorer | 60 | 98.3% | 95.3% |
| project/Hooks | 2 | 98.8% | 93.4% |
| regeneration/Cut | 15 | 99.3% | 96.4% |
| publishing/Site | 161 | 99.3% | 98.1% |

## Where the next grading buys most: highest posterior variance

| Scenario | Component | Graded | Fails | Last verdict | Posterior mean | 95% lower bound |
|---|---|---|---|---|---|---|
| spec-0172 | measurement/Scoreboard | 2 | 1 | fail | 88.7% | 71.3% |
| spec-0307 | verification/Status | 2 | 1 | pass | 90.2% | 73.7% |
| spec-0062 | verification/Results reader | 4 | 1 | fail | 90.2% | 75.0% |
| spec-0298 | regeneration/Scopes | 4 | 1 | fail | 91.1% | 76.5% |
| spec-0274 | compliance/Audit copy | 4 | 1 | pass | 91.1% | 76.5% |
| spec-0173 | measurement/Scorer | 5 | 1 | pass | 92.2% | 78.8% |
| spec-0250 | compliance/Audit copy | 6 | 1 | pass | 92.3% | 79.3% |
| spec-0289 | agency/Observe | 1 | 0 | pass | 94.9% | 81.8% |
| spec-0290 | agency/Observe | 1 | 0 | pass | 94.9% | 81.8% |
| spec-0286 | agency/Observe | 1 | 0 | pass | 94.9% | 81.8% |
| spec-0287 | agency/Observe | 1 | 0 | pass | 94.9% | 81.8% |
| spec-0293 | agency/Observe | 1 | 0 | pass | 94.9% | 81.8% |
