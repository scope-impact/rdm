# What the graded cycles say, as beliefs

Written by `dhf/cycles/analysis/posterior.py` (Python with numpy and scipy;
`dhf/harness/stack.md`, "Cycle analysis") on 2026-10-26, over every graded
cycle. Cycle 12 was never graded; cycle 17 was still being graded. The
model is in the script's docstring.

**How to read it:**
- Every number is a belief with a width, not a score.
- **Grader error is a prior, not learned.** No item has been graded twice,
  and no fail has been fed back as confirmed. So questions 1 and 3 are shown
  twice: at a 5% grader-error prior, then at 1%.
- **Questions 1 and 3 swing with that prior.** They will stop swinging once
  graders are double-graded, or once fails confirmed by hand are fed back as
  labels.
- **Known labels:** the two fails of cycle 16 (spec-0298, spec-0062) were
  confirmed real by hand. Even at 1% the model gives them only about 70%,
  because its prior belief in the build is high. That gap is the case for
  feeding confirmed fails back as labels.

Graded cycles: 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 13, 14, 15, 16; 1376 verdicts, 8 fails; 279 scenarios.

## 1. Can the latest build ship?

Cycle 16: P(some item of its slice is wrong, given its verdicts) = **71.4%** (90% interval 22.0% to 99.6%). Over the 5.0% bar.

| Cycle | Graded | Fails | P(something wrong) |
|---|---|---|---|
| 02 | 180 | 1 | 24.2% |
| 03 | 105 | 2 | 34.8% |
| 04 | 102 | 0 | 6.9% |
| 05 | 74 | 0 | 4.5% |
| 06 | 71 | 0 | 5.4% |
| 07 | 41 | 1 | 21.6% |
| 08 | 122 | 0 | 8.1% |
| 09 | 107 | 0 | 5.9% |
| 10 | 62 | 0 | 4.0% |
| 11 | 238 | 1 | 33.3% |
| 13 | 107 | 0 | 12.0% |
| 14 | 31 | 0 | 5.3% |
| 15 | 38 | 1 | 32.7% |
| 16 | 98 | 2 | 71.0% |

## 2. What the next grading should cover

| Scenario | Component | Graded | Fails | Last | Belief | 95% lower |
|---|---|---|---|---|---|---|
| spec-0298 | regeneration/scopes | 4 | 1 | fail (16) | 90.2% | 72.8% |
| spec-0062 | verification/results | 4 | 1 | fail (16) | 90.7% | 73.7% |
| spec-0307 | verification/status | 2 | 1 | pass (16) | 93.3% | 78.9% |
| spec-0305 | regeneration/scopes | 2 | 0 | pass (16) | 94.7% | 81.0% |
| spec-0306 | regeneration/scopes | 2 | 0 | pass (16) | 94.7% | 81.0% |
| spec-0300 | regeneration/scopes | 3 | 0 | pass (16) | 94.7% | 81.2% |
| spec-0301 | regeneration/scopes | 3 | 0 | pass (16) | 94.7% | 81.2% |
| spec-0312 | verification/results | 1 | 0 | pass (16) | 95.1% | 81.8% |
| spec-0313 | verification/results | 1 | 0 | pass (16) | 95.1% | 81.8% |
| spec-0299 | regeneration/scopes | 4 | 0 | pass (16) | 94.8% | 81.3% |
| spec-0241 | regeneration/scopes | 6 | 0 | pass (16) | 94.8% | 81.4% |
| spec-0237 | regeneration/scopes | 7 | 0 | pass (16) | 94.8% | 81.4% |

## 3. Is each fail real?

With the grader prior only (5.0% false fail, 5.0% false pass): how likely each fail was a real defect.

| Cycle | Scenario | P(real) | 90% interval |
|---|---|---|---|
| 02 | spec-0173 | 12.0% | 0.0% to 74.2% |
| 03 | spec-0098 | 12.8% | 0.0% to 75.7% |
| 03 | spec-0172 | 19.4% | 0.0% to 86.0% |
| 07 | spec-0250 | 19.5% | 0.0% to 85.4% |
| 11 | spec-0274 | 19.5% | 0.0% to 85.0% |
| 15 | spec-0307 | 28.1% | 0.0% to 90.8% |
| 16 | spec-0062 | 44.0% | 0.6% to 95.2% |
| 16 | spec-0298 | 47.2% | 1.1% to 95.8% |

## 4. Which scenarios the spec should look at first (lowest belief)

| Scenario | Component | Graded | Fails | Belief | 95% lower |
|---|---|---|---|---|---|
| spec-0298 | regeneration/scopes | 4 | 1 | 90.2% | 72.8% |
| spec-0062 | verification/results | 4 | 1 | 90.7% | 73.7% |
| spec-0307 | verification/status | 2 | 1 | 93.3% | 78.9% |
| spec-0305 | regeneration/scopes | 2 | 0 | 94.7% | 81.0% |
| spec-0306 | regeneration/scopes | 2 | 0 | 94.7% | 81.0% |
| spec-0300 | regeneration/scopes | 3 | 0 | 94.7% | 81.2% |
| spec-0301 | regeneration/scopes | 3 | 0 | 94.7% | 81.2% |
| spec-0299 | regeneration/scopes | 4 | 0 | 94.8% | 81.3% |

## 5. Did a spec change help?

No cycle records its builder's decisions per scenario (dhf/cycles/NN/decisions.edn), so this cannot be answered yet. Recording them each cycle (E6) gives a Poisson rate per scenario, and a spec change helped when that rate drops for the scenarios it touched.

## 6. How many more gradings until the 95% lower bound clears 95.0%

| Scenario | More all-pass gradings needed |
|---|---|
| spec-0298 | 52 |
| spec-0062 | 50 |
| spec-0307 | 39 |
| spec-0305 | 31 |
| spec-0306 | 31 |

## 7. Is rdm-spec cheaper than Löyly's harness (the Löyly app, tokens per cycle)?

Ratio rdm-spec / Löyly-harness = **0.64** (90% interval 0.41 to 1.01); P(rdm-spec cheaper) = **94.8%**. The spread is rdm-spec's own builders' (log sd 0.23). Runs per side to know at 95%: about **2**.

## 8. Is the spec done?

68 of 279 scenarios (24.4%) have a 95% lower bound at or above 95.0%. Done would be all of them, with builder decisions near zero (question 5).

---

# The same, with a 1% grader-error prior (questions 1 and 3 only)

## 1. Can the latest build ship?

Cycle 16: P(some item of its slice is wrong, given its verdicts) = **88.7%** (90% interval 46.6% to 100.0%). Over the 5.0% bar.

| Cycle | Graded | Fails | P(something wrong) |
|---|---|---|---|
| 02 | 180 | 1 | 24.3% |
| 03 | 105 | 2 | 48.5% |
| 04 | 102 | 0 | 1.4% |
| 05 | 74 | 0 | 0.9% |
| 06 | 71 | 0 | 1.1% |
| 07 | 41 | 1 | 33.9% |
| 08 | 122 | 0 | 1.7% |
| 09 | 107 | 0 | 1.2% |
| 10 | 62 | 0 | 0.8% |
| 11 | 238 | 1 | 36.0% |
| 13 | 107 | 0 | 2.6% |
| 14 | 31 | 0 | 1.1% |
| 15 | 38 | 1 | 47.4% |
| 16 | 98 | 2 | 88.6% |

## 3. Is each fail real?

With the grader prior only (1.0% false fail, 1.0% false pass): how likely each fail was a real defect.

| Cycle | Scenario | P(real) | 90% interval |
|---|---|---|---|
| 02 | spec-0173 | 21.5% | 0.0% to 94.1% |
| 03 | spec-0098 | 22.7% | 0.0% to 94.4% |
| 03 | spec-0172 | 33.4% | 0.0% to 97.0% |
| 07 | spec-0250 | 33.6% | 0.0% to 96.9% |
| 11 | spec-0274 | 33.6% | 0.0% to 96.9% |
| 15 | spec-0307 | 46.2% | 0.0% to 98.1% |
| 16 | spec-0062 | 66.6% | 3.3% to 99.1% |
| 16 | spec-0298 | 69.9% | 5.4% to 99.2% |

