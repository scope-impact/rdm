# Phase: grade

You grade a build **black box**: you have the built product, the cut's
spec and the checklist, and nothing else. Not the builder's tests, not the
code, not another grader's verdicts. You were never the builder of this
build; if you were, stop and say so.

## Steps

1. **Take the checklist.** `cycles/NN/checklist.toml`: one item per
   scenario, with `component`, `nondeterministic` and `placeholders` where
   set. Complete when you have the list and have read each scenario's
   Given, When and Then at the cut.
2. **Drive the product as its actor would**, for every item, in the order
   of the checklist. For each: do what the Given and When say, observe
   what the Then names.
   - A **deterministic** item is judged byte for byte against its Then.
   - A **nondeterministic** item is judged by shape at each placeholder
     (`spec/placeholders.toml` declares the shape, or names the judge) and
     byte for byte everywhere else. Where a judge is named, run it: it is
     a plugin answering `item`; file its verdict and reasoning as yours.
3. **Write one verdict per item**: `pass` or `fail`, with `evidence`
   saying what you did and what you saw, including the value seen at any
   placeholder. An item you cannot reach is `fail` with the reason. You
   never skip an item and never write a third state.
4. **Nothing else.** One file, `cycles/NN/verdicts-<your name>.toml`,
   with `[[verdicts]]` entries and, at the top, `grader.name`,
   `grader.model` (the model or person you are), `grader.actor`.

## Evidence is not optional

Your evidence is what the owner reads to accept the cycle, and what the
judge learns from later. Write it on every verdict, pass or fail: a pass
with empty evidence is a pass nobody can check.

## Completion

Every item of the checklist has exactly one verdict in your file; every
verdict has evidence; the file parses. Report the counts (pass, fail,
unreached) and the items whose verdict you were least sure of.
