# The Löyly method, where the spec is silent

rdm-spec applies Löyly's method to itself. Where rdm-spec's spec leaves a
choice open, the choice follows Löyly, and the spec change that settles it
cites the rule it followed. This page is that reference: each rule as Löyly
states it in its own readable record, never its tools, which are fenced.

## The loop

- The spec is cut at a fixed time, tagged and frozen, and a regeneration reads
  that cut and nothing else.[^idea]
- The whole application is regenerated from the cut inside a fence: a clean
  room from which every prior implementation and every measurement is deleted,
  history included, so nothing can be copied, only rebuilt.[^idea]
- Anything not written into the spec dies with the implementation; that cut is
  what forces learning back into the spec.[^idea]
- A fence is proved, not asserted: a weaker probe once reported success while
  the previous implementation was still reachable from an older commit.[^failures]

## The gate

- "Done" is not the builder's claim: a script the builder does not control
  ratifies it, and the gate trusts the suite's exit code, not its
  prose.[^pieces]
- The gate states its own budget and reports its margin on a green run, because
  a number nobody reads until it fails is a number nobody sees approaching.[^budget]

## The instrument

- A harness feeds what it learns back into the generation loop; an instrument
  reports to a person, once a cycle, and never into the loop.[^idea]
- The instrument is checked before it is used: nothing is fenced or ratified
  while its own check is red, because a gate downstream of an unchecked
  instrument ratifies a number rather than a build.[^checks]
- A golden changes only for a stated reason, and re-recording one to get a
  green destroys the only evidence that anything moved.[^checks]
- Deterministic tools built by the model, run without it, derive what no model
  should be trusted to enumerate.[^pieces]

## Ids, checklist, score, scoreboard

- Ids are handed out once and never computed from wording, so rewording a rule
  cannot break the comparison between cycles.[^pieces]
- An id, once assigned, is never renumbered, and deleting a scenario does not
  free its number.[^ids]
- The checklist is derived, never written: one item per stamped scenario, and
  the spec is its only author.[^checklist]
- An unstamped scenario stops the checklist rather than shrinking it, and an
  empty spec stops it rather than producing an empty one.[^stops]
- Diagnostics go to the error stream, so standard output is the result
  alone.[^stderr]
- Zero verdicts means the grading did not happen, not that everything failed;
  an item nobody reached is a failure, never dropped; a verdict for an id not on
  the checklist is fatal.[^score]
- The scoreboard counts both directions on the shared items, never the net; a
  moved denominator is restated out loud; new items are counted
  separately.[^pieces]

## Where rdm-spec departs from Löyly, on purpose

- **One installed command** instead of scripts carried by hand between
  repositories: no fork check is needed.
- **The clean room is a deny-list, as Löyly's, made robust by classifying
  every path** (it was an allow-list until cycle 13): every committed path is
  denied or kept, or the check fails, and no kept file may carry a removed
  implementation file's bytes.
- **A cycle rebuilds the scopes a spec change touched,** one builder building
  them one after another, where Löyly regenerates the whole application every
  cycle; a whole-project cycle is still run from time to time.
- **Ratify also requires every scenario named by a passing result,** which
  Löyly leaves to the grader; rdm-spec boots nothing, being a command rather
  than a server.
- **The oracle lives outside the repository,** one file in one artifact read
  by its digest, where Löyly keeps `harness/oracle` in the repository and
  fences it.[^fenced] No allow-list can leak goldens that are not in the
  repository, and a digest names exactly one set of bytes, so a golden cannot
  change unseen.

Each departure is recorded in `dhf/decisions/adr-0002-spec-first-regeneration.md`.

[^idea]: `loyly:harness/README.md@17d68d2`, "The idea in five lines".
[^failures]: `loyly:harness/README.md@17d68d2`, "The failure modes this shape was learned from".
[^pieces]: `loyly:harness/README.md@17d68d2`, "The pieces, in pipeline order".
[^budget]: `loyly:harness/README.md@17d68d2`, "The gate's boot budget".
[^checks]: `loyly:harness/README.md@17d68d2`, "What makes the checks run".
[^ids]: `loyly:harness/spec.feature@17d68d2`, "An id, once assigned, is never renumbered and never re-derived".
[^checklist]: `loyly:harness/spec.feature@17d68d2`, "The checklist is one item per stamped scenario, and the spec is its only author".
[^stops]: `loyly:harness/spec.feature@17d68d2`, "An unstamped scenario stops the checklist rather than shrinking it".
[^stderr]: `loyly:harness/spec.feature@17d68d2`, "The skip lines go to stderr, so stdout is the checklist alone".
[^fenced]: `loyly:harness/README.md@17d68d2`, "The idea in five lines" 2.
[^score]: `loyly:harness/spec.feature@17d68d2`, "Zero verdicts means the grading did not happen, not that everything failed".
