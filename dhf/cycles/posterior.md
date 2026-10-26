# What the graded cycles say, as beliefs

Written by `dhf/cycles/analysis/posterior.py` (Python with numpy and scipy;
`dhf/harness/stack.md`, "Cycle analysis") on 2026-10-26, over every graded
cycle. Cycle 12 was never graded; cycle 17 was still being graded. The
model is in the script's docstring.

**How to read it:**
- Every number is a belief with a width, not a score.
- **Grader error:** the false-fail rate learns from the labelled fails
  (`dhf/cycles/NN/labels.yaml`); the false-pass rate is still a prior, since
  no item has been graded twice. So questions 1 and 3 are shown twice: at a
  5% grader-error prior, then at 1%.
- **Questions 1 and 3 swing with that prior.** They will stop swinging once
  graders are double-graded, or once fails confirmed by hand are fed back as
  labels.
- **Labels and decisions are in YAML beside each cycle:** `labels.yaml`
  (each fail, once looked at: real, grader or wording) and `decisions.yaml`
  (the builder's decisions where the spec was silent, by scenario), written
  by the integrator from what was found and from the builder's report.

Graded cycles: 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 13, 14, 15, 16; 1375 verdicts, 7 fails; 279 scenarios.

## 1. Can the latest build ship?

Cycle 16: P(some item of its slice is wrong, given its verdicts) = **71.7%** (90% interval 20.7% to 99.7%). Over the 5.0% bar.

| Cycle | Graded | Fails | P(something wrong) |
|---|---|---|---|
| 02 | 180 | 1 | 23.4% |
| 03 | 105 | 2 | 35.7% |
| 04 | 102 | 0 | 6.6% |
| 05 | 74 | 0 | 4.1% |
| 06 | 71 | 0 | 5.4% |
| 07 | 41 | 1 | 22.6% |
| 08 | 122 | 0 | 8.0% |
| 09 | 107 | 0 | 5.6% |
| 10 | 62 | 0 | 4.0% |
| 11 | 238 | 1 | 33.2% |
| 13 | 107 | 0 | 11.1% |
| 14 | 31 | 0 | 4.3% |
| 15 | 38 | 1 | 15.0% |
| 16 | 98 | 2 | 71.6% |

## 2. What the next grading should cover

| Scenario | Component | Graded | Fails | Last | Belief | 95% lower |
|---|---|---|---|---|---|---|
| spec-0298 | regeneration/scopes | 4 | 1 | fail (16) | 90.2% | 72.8% |
| spec-0062 | verification/results | 4 | 1 | fail (16) | 91.2% | 74.5% |
| spec-0305 | regeneration/scopes | 2 | 0 | pass (16) | 94.7% | 81.0% |
| spec-0306 | regeneration/scopes | 2 | 0 | pass (16) | 94.7% | 81.0% |
| spec-0300 | regeneration/scopes | 3 | 0 | pass (16) | 94.7% | 81.2% |
| spec-0301 | regeneration/scopes | 3 | 0 | pass (16) | 94.7% | 81.2% |
| spec-0299 | regeneration/scopes | 4 | 0 | pass (16) | 94.8% | 81.3% |
| spec-0241 | regeneration/scopes | 6 | 0 | pass (16) | 94.8% | 81.4% |
| spec-0237 | regeneration/scopes | 7 | 0 | pass (16) | 94.8% | 81.4% |
| spec-0238 | regeneration/scopes | 7 | 0 | pass (16) | 94.8% | 81.4% |
| spec-0286 | agency/observe | 1 | 0 | pass (13) | 94.9% | 81.8% |
| spec-0287 | agency/observe | 1 | 0 | pass (13) | 94.9% | 81.8% |

## 3. Is each fail real?

Grader false fail 4.3% (the prior, learned from 3 labelled fails), false pass 5.0% (prior only): how likely each fail not yet looked at was a real defect.

| Cycle | Scenario | P(real) | 90% interval |
|---|---|---|---|
| 02 | spec-0173 | 12.6% | 0.0% to 76.3% |
| 03 | spec-0098 | 13.4% | 0.0% to 79.3% |
| 03 | spec-0172 | 20.2% | 0.0% to 86.8% |
| 07 | spec-0250 | 20.4% | 0.0% to 86.9% |
| 11 | spec-0274 | 20.6% | 0.0% to 87.4% |
| 15 | spec-0307 | labelled **wording** | (looked at) |
| 16 | spec-0062 | labelled **real** | (looked at) |
| 16 | spec-0298 | labelled **real** | (looked at) |

## 4. Which scenarios the spec should look at first (lowest belief)

| Scenario | Component | Graded | Fails | Belief | 95% lower |
|---|---|---|---|---|---|
| spec-0298 | regeneration/scopes | 4 | 1 | 90.2% | 72.8% |
| spec-0062 | verification/results | 4 | 1 | 91.2% | 74.5% |
| spec-0305 | regeneration/scopes | 2 | 0 | 94.7% | 81.0% |
| spec-0306 | regeneration/scopes | 2 | 0 | 94.7% | 81.0% |
| spec-0300 | regeneration/scopes | 3 | 0 | 94.7% | 81.2% |
| spec-0301 | regeneration/scopes | 3 | 0 | 94.7% | 81.2% |
| spec-0299 | regeneration/scopes | 4 | 0 | 94.8% | 81.3% |
| spec-0241 | regeneration/scopes | 6 | 0 | 94.8% | 81.4% |

## 5. Did a spec change help?

Builder decisions where the spec was silent: cycle 16: 8, cycle 17: 18.

Scenarios decided in more than one cycle are the ones the spec keeps leaving open; a spec change helped when they stop appearing.

| Scenario | Cycles it was decided in |
|---|---|
| spec-0318 | 16 (x2), 17 |
| spec-0299 | 16, 17 |
| spec-0309 | 16, 17 |
| spec-0326 | 17 (x4) |
| spec-0032 | 17 |
| spec-0068 | 16 |
| spec-0086 | 16 |
| spec-0169 | 17 |
| spec-0207 | 16 |
| spec-0229 | 17 |

## 6. How many more gradings until the 95% lower bound clears 95.0%

| Scenario | More all-pass gradings needed |
|---|---|
| spec-0298 | 52 |
| spec-0062 | 48 |
| spec-0305 | 31 |
| spec-0306 | 31 |
| spec-0300 | 31 |

## 7. Is rdm-spec cheaper than Löyly's harness (the Löyly app, tokens per cycle)?

Ratio rdm-spec / Löyly-harness = **0.64** (90% interval 0.41 to 1.01); P(rdm-spec cheaper) = **94.8%**. The spread is rdm-spec's own builders' (log sd 0.23). Runs per side to know at 95%: about **2**.

## 8. Is the spec done?

87 of 279 scenarios (31.2%) have a 95% lower bound at or above 95.0%. Done would be all of them, with builder decisions near zero (question 5).

---

# The same, with a 1% grader-error prior (questions 1 and 3 only)

## 1. Can the latest build ship?

Cycle 16: P(some item of its slice is wrong, given its verdicts) = **87.9%** (90% interval 43.1% to 100.0%). Over the 5.0% bar.

| Cycle | Graded | Fails | P(something wrong) |
|---|---|---|---|
| 02 | 180 | 1 | 23.9% |
| 03 | 105 | 2 | 48.9% |
| 04 | 102 | 0 | 1.4% |
| 05 | 74 | 0 | 0.8% |
| 06 | 71 | 0 | 1.1% |
| 07 | 41 | 1 | 33.9% |
| 08 | 122 | 0 | 1.7% |
| 09 | 107 | 0 | 1.1% |
| 10 | 62 | 0 | 0.8% |
| 11 | 238 | 1 | 35.8% |
| 13 | 107 | 0 | 2.4% |
| 14 | 31 | 0 | 0.9% |
| 15 | 38 | 1 | 18.5% |
| 16 | 98 | 2 | 87.8% |

## 3. Is each fail real?

Grader false fail 1.0% (the prior, learned from 3 labelled fails), false pass 1.0% (prior only): how likely each fail not yet looked at was a real defect.

| Cycle | Scenario | P(real) | 90% interval |
|---|---|---|---|
| 02 | spec-0173 | 21.3% | 0.0% to 93.9% |
| 03 | spec-0098 | 22.8% | 0.0% to 94.6% |
| 03 | spec-0172 | 33.3% | 0.0% to 96.8% |
| 07 | spec-0250 | 33.6% | 0.0% to 96.9% |
| 11 | spec-0274 | 33.8% | 0.0% to 96.8% |
| 15 | spec-0307 | labelled **wording** | (looked at) |
| 16 | spec-0062 | labelled **real** | (looked at) |
| 16 | spec-0298 | labelled **real** | (looked at) |

