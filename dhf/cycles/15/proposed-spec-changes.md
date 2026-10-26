# Cycle 15: score and proposed spec changes

spec-19: rdm-spec is the harness, and a project's event catalogue is its own.
`:read-by` became optional metadata, and status's result was pinned. Cycle
14's release ran this cycle, the first without the old allow-list.

| Run | Minutes | Tokens | Result | Breaches |
|---|---|---|---|---|
| builder (5 scopes) | 25.1 | 373,289 | 323 of 323 scenario results and 125 unit tests, twice; each scope seen failing first | 0 |
| grader (38 items) | 4.9 | 128,039 | 37 pass, 1 fail | 0 |
| **cycle** | **30.0** | **501,328** | | **0** |

The grader turned off the git hooks in every fixture, so this cycle had no
PATH incident. The integrator's own run and all reads are not observed.

1. **Scopes:** the release listed four. The integrator added kernel/command,
   because it hands the bus its catalogue and owns the fixture-hooks step.
   That gives five in all, in build order.
2. **Integrator's checks:**
   - Suite: 323 of 323, twice, with no fixture left (`/tmp` unchanged).
   - Oracle: 0 differences, and `check` on this repository exits 0.
   - The new `scopes` lists **3** scopes for spec-17 → spec-18; cycle 14's
     build listed 8. The first-appearance bug is fixed.
3. **Ratify:** passed against spec-19, run by cycle 14's release.
4. **Score:** **37 of 38.**
5. **Scoreboard 14 → 15:**
   - 31 compared: **0 better, 0 worse**
   - 7 new items
   - nothing out of scope

## The fail

**spec-0307, the status result.** The build prints an unknown result's
`:file` as `DIR/bbb-result.json`. The scenario says F is "the name of that
result's file within DIR", which reads as `bbb-result.json`. The builder
chose `DIR/name` on purpose, to match trace and release. The verdict stands
as the text reads.

For spec-20, the spec owner decides which form is meant:
- **Option 1:** the bare name, which matches the words as written. Then the
  code changes.
- **Option 2:** the path as DIR names it, which matches trace and release.
  Then the scenario text changes, and the next build passes as it is.

Option 2 is the integrator's recommendation: one form across every task, and
a contract scenario for trace's and release's result too.

## For spec-20

1. **spec-0307's F** (above).
2. **Order among several subscribers** (grader: 0298, 0300). The build uses
   the model's relationship order. State it.
3. **spec-0303's component** (grader). State whether the project's model
   must hold the publishing component; the build passes either way.
4. **spec-0301 row 2, "every key"**: say every key in `:carries`.
5. **Builder's decisions to pin:**
   - A bus refusal is `cannot-run` (exit 2): rdm-spec's own defect, not a
     finding about the project.
   - An event line names the publisher as `spec/parser`.
   - A configured results directory that nobody named and that does not
     exist counts as no results for trace and publish; a named one must
     exist.
   - A reworded collection-of-maps key with no inner key added or removed
     reaches the readers of every inner key.
6. **Still open from cycle 14:** the minors (svg reported as its mmd, the lint
   message's repeated name, the double heading, HTML determinism) and
   shared-file warning noise.
