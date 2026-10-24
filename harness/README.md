# The harness

RDM's harness, specified in `dhf/plan/01-harness/spec.feature` and designed in
`dhf/documents/design/harness.md`: Clojure, run by babashka from the
repository's root.

    bb harness stamp                      # stamp unstamped scenarios with item ids
    bb harness checklist --out harness/cycles/NN/checklist.json
    bb harness score NN --results dhf/allure-results    # or --verdicts FILE
    bb harness scoreboard NN
    bb harness verify [--base REF]        # ratify a build round

`config.edn` is everything the harness knows about RDM; `known/` holds the
records its instrument is checked against; `cycles/` the cycles scored.
After Löyly's harness (`loyly/harness/`).
