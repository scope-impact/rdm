# Decisions: slice 07-measure

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `checklist.feature`, Rule: The checklist is derived from the cycle's cut

The checklist is derived from the spec, never written by hand
(loyly:README.md@17d68d2, "What to steal" 1).

### `checklist.feature`, Rule: An unstamped scenario stops the checklist

A scenario the checklist cannot name would shrink the denominator without
anyone noticing (loyly:harness/spec.feature@17d68d2, "An unstamped scenario
stops the checklist rather than shrinking it").

### `observe.feature`, Feature: Observe

Every run of a cycle, a builder's, a grader's or the integrator's, has two
halves: its capability, what it was for, proven by its result (ratify, the
score), and its defence, what it did outside its brief. "Done" is not the
builder's claim (loyly:harness/README.md@17d68d2, "The idea in five lines"
3), and neither is "stayed in my room". Builders and graders wrote at the
filesystem's root, beside their rooms and into /tmp in cycles 06 to 11, and
each was found only because someone looked (dhf/cycles/retrospective-01-12.md).

This component observes: it records, it never prevents, and it never stops
a run. Reads cannot be observed, and the record says so.

The configuration's :agency {:watch [DIR ...] :tmp DIR} names the
directories whose entries are compared before and after a run ("~" is the
home directory) and the temporary directory whose entries are counted. W1,
T and ROOM are directories the scenario makes.

### `observe.feature`, Rule: Two cycles' runs are compared side by side, never netted

Löyly's scoreboard counts both directions and never the net
(loyly:harness/README.md@17d68d2, "The pieces, in pipeline order"); so is
defence, and it is never folded into the score.

### `scoreboard.feature`, Rule: A scoped cycle is reported target first

A partial regeneration can break what it did not rebuild; the scoreboard
shows that apart from what it did. A scoped cycle's checklist holds only
its scope, so the rest is compared only where both cycles scored it, as a
project cycle does (cycle 02, dhf/cycles/02/proposed-spec-changes.md, A1).

### `scoreboard.feature`, Scenario: A scoped cycle is compared over its scope only (spec-0225)

spec-0172 (a scoped cycle that scored every scenario) is retired: under
the scope rule no such cycle can exist (cycle 03, A1).

### `scoreboard.feature`, Rule: A bootstrap cycle is never a baseline

Before any rdm-spec release exists, the first build is ratified by a reviewed
bootstrap cycle (ADR 0002). It says so, and nothing is measured against it.

### `scoreboard.feature`, Rule: The scoreboard's result has one shape

The scoreboard's result was pinned only by the oracle's goldens, which no
builder sees, and the first rebuild of the scoreboard since they were
recorded drifted from them in 25 places (dhf/cycles/11/proposed-spec-changes.md,
1). The shape is the spec's, as the goldens record it.

### `scorer.feature`, Feature: Scorer

Scoring is independent of the builder's tests, as in Löyly: a grader, human
or agent and never the builder, grades each checklist item against the
verified build, however they grade, and the scorer files the verdicts
(loyly:README.md@17d68d2, "3. Measure").

### `scorer.feature`, Scenario: The verdicts of several files are filed as one (spec-0379)

spec-28: two graders grade one cycle in two rooms; their files are scored
as one. A file that is not readable EDN names where it stops. How the
graders' rooms are built is the project's own (its grading, like its
oracle, is a project's judge, ADR 0003).

### `scorer.feature`, Rule: The instrument refuses to record what it did not measure

Incident: a scoring run whose every batch failed on a network outage once
committed "scored" over an empty record (loyly:harness/README.md@17d68d2,
"The failure modes this shape was learned from").

### `scorer.feature`, Rule: The builder neither grades nor reads its own score

Every role may be a human or an agent (UN-012), so the instrument cannot
rely on "a person" to stay out of the loop. What it relies on is that the
one who grades and the one who reads the score are not the one who built
(loyly:harness/README.md@17d68d2, "The idea in five lines").


## Expected decisions (planned for the component build, ADR 0006)

Accepted as recommended by the integrator on 2026-10-28, under the spec
owner's goal; the spec owner may overrule any. On the planner's questions:
the checklist reaches git itself through rdm:host/process, as verify does
(the model's lint holds contexts to events, so no checklist-to-cut
relationship); rdm-spec.wit's not-built gains include clause-index-not-built,
and slice 10's compliance/gap exports the clause index; the checklist and
verify keep two readings of a scope, held together by shared test fixtures;
the host keeps the root folder open while observe exists. The visible spec
gaps, with :writes-outside's paths outside the project and what :breaches
counts, are stated in the next spec change.

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`, cycle 8 of its build order;
`docs/wow/harness.md`, "The planner"): each question a builder of this slice
would otherwise decide alone, with its options and the planner's
recommendation. The spec owner settles each before the build, or turns it
into a scenario. Where the spec is silent on what rdm-spec does, the
question is a spec gap (below), not a decision. The tickets (`ticket-01` to
`ticket-08`) cite them as ED-NN and G-NN; other slices' are cited as "slice
06's ED-NN" and so on. The architecture is in `dhf/wit/`
(`measurement-checklist.wit`, `measurement-scorer.wit`,
`measurement-scoreboard.wit`, `agency-observe.wit`, `slice-07.wit`,
`slice-07.wac`). The settlements of slices 01 to 06 and 08 hold here: no
bridge (their ED-01), events are values handed on as EDN between slices
(slice 02's ED-04, slice 08's ED-02), a task's interface uses only its own
and builtin types (slice 01's ED-14), the folders the host opens are the
working folder and the root (slice 01's ED-05), git runs only through
`rdm:host/process` (slice 06's ED-03), and the git helpers are cut's (slice
06's ED-04).

### ED-01: Scenarios whose task another slice builds

As slices 01 to 06 and 08, settled there: no bridge. This slice's
components carry 40 scenarios: 31 in this slice (checklist 3, scorer 14,
scoreboard 6, observe 8), 2 in `10-regulated-profile`, 1 in
`11-project-in-a-folder`, 1 in `12-several-specs` and 5 in
`13-slice-development`. One needs a component a later cycle builds:
spec-0266's clause items come from compliance/gap (slice 10, cycle 10).
spec-0173, spec-0389 and spec-0395 also run verify, built in cycle 7. This
cycle unblocks slice 02's spec-0220 and slice 01's spec-0368 (row
`checklist 03`), verified as regression items without those slices being
rebuilt (`pending.md`).

- A. spec-0266 pending until cycle 10; ticket 03 builds the clause items
  now, done on unit tests and a stand-in index.

Recommendation: A.

### ED-02: How observe reaches directories outside the project

observe compares the entries of directories outside the project: the
configuration's `:agency :watch` (rdm-spec's own: `"/"` and `"~"`) and
`:agency :tmp` (`"/tmp"`), and in its scenarios W1 and T, directories each
scenario makes (`observe.feature`; slice decisions above). A component sees
files only through the folders the host opens, and runs a process only
through `rdm:host/process`.

- A. **Through the root folder the host opens.** The host opens the working
  folder as `.` and the root as `/` (slice 01's ED-05, settled for init's
  absolute paths), so every watched directory and the temporary directory
  are read through the host's standard interfaces from `/`: each listed,
  each entry's kind, size and modification time read; `~` is the home
  directory the environment names (HOME), which the host passes; an
  absolute path is read from `/`, a relative one from the project's root.
  The host holds no logic and grows nothing. Its consequence for the host's
  preopens: the root must stay opened, read and write, for as long as
  observe exists; a host narrowed to the project's folder (ED-05's option
  C) would leave observe blind, and could open the watched directories
  only by reading the configuration, which is logic in the host. The
  host's comment says so when this slice is built.
- B. **Through processes**: `ls` or `find` with their modification times,
  run by `rdm:host/process`: output parsed in the core, a tool the stack
  does not name, and the same root reached anyway.
- C. **The host opens the watched directories itself**, read from the
  configuration: logic in the host.

Recommendation: A. A watched directory that cannot be listed (no such
directory, no permission) is named in `:unobserved`, never assumed clean
(spec-0291; G-32, G-36).

### ED-03: How the checklist reads the cut

spec-0019 derives the checklist "from the spec at the cut"; spec-0220
reads "the spec as it was at that cut"; spec-0406 and spec-0368 read the
project's folder at the cut; spec-0396 refuses "a slice the cut does not
hold"; spec-0266 reads "the cut's clause lists" under a regulated project.

- A. **Slice 06's git helpers and slice 02's parser.** cycle.edn is read
  from the project's folder, as written by hand (ED-07). Its `:cut` is
  resolved with regeneration/cut's `resolve` (a revision that is no cut,
  exit 2, as spec-0037 refuses it; G-02); the cut's configuration
  (`:regulated`, `:spec-layout`, `:specs`) read with `show` at the cut
  (G-03); the spec at the cut read with spec/parser's `parse` at the cut
  and the project's folder (slice 02's ticket 06), which runs git itself;
  the cut's slices listed with `tree` at the cut. Nothing is written out:
  the checklist reads no file of the cut but through these. The model
  gains "Reads cuts with the git helpers of" from checklist to cut, as
  slice 06's ED-04 adds it for scopes and verify.
- B. The cut written out to a temporary directory and read there (as
  scopes reads a cut's model): one more copy, and a temporary directory
  observe would count if left.
- C. git run by the checklist itself: a fourth reading of a cut.

Recommendation: A. The checklist then imports regeneration/cut's instance
(on its own in `rdm-spec.wac`, slice 06's ED-01) and slice 02's parser; it
runs no process of its own.

### ED-04: The clause items before slice 10

spec-0266 makes each clause of the cut's clause lists an item; the model
has compliance/gap publish ClausesIndexed to the checklist; compliance/gap
is built in cycle 10. Check's clauses stage (`spec-check.wit`) is asked of
the working tree, with check's arguments, and the checklist needs the
clauses at the cut.

- A. **A clause index, asked at a revision.** The checklist imports
  `clause-index`, an interface written out in its own world
  (`measurement-checklist.wit`): `index(revision, folder)`, answering
  ClausesIndexed as EDN (the published language), none when not built, an
  error when the lists cannot be read. The not-built stand-in exports it
  (`clause-index-not-built`, which `rdm-spec.wit`'s `not-built` includes,
  as slice 02 added `check-stages-not-built`); slice 10's compliance/gap
  exports the same interface, wired by name ahead of the spread. Asked
  only under `:regulated` at the cut and with a scope reaching compliance
  (G-11); none then refuses the checklist, exit 2 (`cannot-run: checklist's
  clause items are not built`): a checklist without its clauses would
  shrink the denominator, as an unstamped scenario would (spec-0211's
  rule).
- B. **Check's clauses stage, the cut passed in its arguments**: no new
  interface, but a convention check's own arguments do not have, and the
  stage asked for the working tree by check and for a cut by the checklist.
- C. **The checklist reads the clause lists itself**: a second reader of
  the lists, and the model's ClausesIndexed to the checklist unserved.

Recommendation: A. slice 10's plan then gives compliance/gap three ways in:
check's clauses stage, release's (slice 05's ED-07), and this index.

### ED-05: The scenarios a scope holds, read once for the checklist and verify

spec-0389 and spec-0395 hold the checklist and verify's tests check to the
same scenarios ("verify requires a result for each of them, as for any
scope"); spec-0173 runs both on one cycle. Verify reads the spec at the cut
with the parser alone (slice 06's ED-09; its world imports no model), so a
slice reaches, for verify, the components owning a scenario in its folder.

- A. **The checklist reads a scope as verify does**: the parser's scenarios
  at the cut, each with its context, component and file; a context or
  component scope by the scenario's owner; a spec scope by the folder its
  file is in (the cut's `:specs`); a slice scope by the components owning a
  scenario whose file is in the slice's folder, then every scenario of
  those components, wherever it sits. Unit tests in both components on the
  same fixtures hold the two readings together, and spec-0389's and
  spec-0395's last Thens hold them on the build.
- B. **One function**: verification/verify exports the scenarios a cycle's
  scope requires, and the checklist imports it: one reading, but a change
  to slice 06's interface and composition (verify would then be
  instantiated before this slice, which it already is).
- C. **architecture/model's ownership on the cut written out** (as scopes
  reads a slice, slice 06's ED-05): exact for a feature file with no
  scenario (G-12), but a reading verify does not share.

Recommendation: A for this cycle, B proposed for the next spec change that
touches slice 06 (question below).

### ED-06: The scorer's verified-build check, reading ratified.edn

spec-0214: a cycle whose `ratified.edn` "holds :outcome :refused" or "does
not exist" is not scored; spec-0174 takes `:room-tree` from the
verification. ratified.edn is verify's record (slice 06; G-15, G-28 there):
`:cut`, `:room-tree`, `:outcome`, `:checkout`, `:checks`, and a refused
one's reason.

- A. **The file, read as EDN**: the scorer reads
  `dhf/cycles/CYCLE/ratified.edn` in the project's folder; missing, not
  EDN, or any `:outcome` but `:passed`, exit 1 saying the cycle has no
  passing verification, nothing written; `:room-tree` copied into
  score.edn. It imports nothing of verify (the model relates the scorer
  only to the repository and the checklist), so the two meet through the
  file, as the model's event catalogue says steps that run at different
  times do.
- B. As A, and the record's `:cut` held to cycle.edn's: a verification of
  another cut is no verification of this cycle (G-17).
- C. The scorer asks verify (an import of slice 06): a dependency the model
  does not draw.

Recommendation: A, with B if the spec owner states G-17.

### ED-07: One reader per file of a cycle

cycle.edn is read by the checklist, the scorer and the scoreboard;
checklist.json by the scorer; score.edn by the scoreboard. The model draws
"Scores" from the scorer to the checklist and "Compares the filed scores
of" from the scoreboard to the scorer.

- A. **The readers exported along the model's relationships**: the
  checklist exports `read-cycle` (cycle.edn, also as slice 06's G-23 reads a
  scope) and `derived` (checklist.json), the scorer exports `filed`
  (score.edn), and the scorer and the scoreboard import them within the
  slice's composition. checklist.json is JSON, written with its keys in
  spec-0210's order, by the checklist alone; score.edn and runs.edn are
  EDN.
- B. A copy of each reader in each crate.
- C. The kernel library (slice 01's ED-13): slice 01's crate, outside this
  cycle's fence.

Recommendation: A.

### ED-08: Where observe keeps what begin saw

begin and end are two runs of the command; end compares what it reads with
what begin read (spec-0287 to spec-0290), and spec-0286 has runs.edn hold
"one more entry" after end, so begin adds none.

- A. **A file in the cycle's directory**: begin writes
  `dhf/cycles/CYCLE/observe-ROLE.edn` (the time, ROOM, each watched
  directory's and the temporary directory's entries, the project's status);
  end reads it, appends the entry, and removes it. It is inside the cycle,
  so it is no change to the project outside it (spec-0289), and outside
  the temporary directory, so it is not counted there (spec-0288).
- B. An open entry in runs.edn, closed by end: runs.edn would hold an entry
  with no `:ended`, against spec-0286's exact keys.
- C. A file in the temporary directory: counted by `:tmp-left`.

Recommendation: A (G-39).

### ED-09: Which entries observe compares

The glossary's "Observation": "the entries of the watched directories and of
the temporary directory compared". `/` and `~` hold the project and the
room of most runs, and a run's own writes into its room change the
modification time of the room's directory.

- A. **Each watched directory's own entries, not their subtrees**: added,
  removed or changed (kind, size, modification time); ROOM, the project's
  root, every directory holding either, and the temporary directory where
  it is watched too, never counted as writes outside (G-29). A write deeper
  than a watched directory's own entries is seen only as its top entry's
  change, when the system changes it.
- B. Every watched directory walked whole: `/` walked twice per run.

Recommendation: A, and G-29 stated.

### ED-10: How observe reads the project's git status

- A. `git status --porcelain=v1 -z --untracked-files=all` through
  `rdm:host/process`, in the project's root, on the project's folder, at
  begin and at end, with each listed path's content id (`git hash-object`)
  so a path changed again is seen; every path under `dhf/cycles/CYCLE/` and
  under ROOM, when ROOM is inside the project, left out; paths relative to
  the project's folder, sorted.
- B. The status lines alone: a file already modified at begin and modified
  again is not seen (G-33).

Recommendation: A.

### ED-11: Who exports the slice's tasks

- A. As slice 02's ED-05 and slice 06's ED-12: a composition component,
  `measure-tasks`, holding no logic: the table's rows of checklist, score,
  scoreboard and observe exactly as spec-0176 writes them (slice 01's
  ED-18), each run with its component.

Recommendation: A.

### ED-12: The host's standard interfaces, and asynchronous functions

- A. As slice 06's ED-13: the cycle that builds the slice adds to `measure`
  and each component world the one line slice 01's worlds carry, in the
  version they carry then. The functions that reach a process (the
  checklist's `run`, through cut's helpers; observe's `run`) are
  asynchronous, as `tasks`' `run` and the host's `run` are; the scorer and
  the scoreboard read and write files only, and stay synchronous.

Recommendation: A.

### Questions for the spec owner

- ED-03: the model gains "Reads cuts with the git helpers of" from
  checklist to cut (with slice 06's ED-04 for scopes and verify).
- ED-04: `rdm-spec.wit`'s `not-built` gains `include
  clause-index-not-built;`, and slice 10's compliance/gap exports the
  clause index beside its clauses stages.
- ED-05: one reading of a scope's scenarios, exported by verify (a change
  to slice 06's interface), or two readings held together by tests.
- ED-02: the host keeps the root opened (slice 01's ED-05) for observe.
- G-31: `:writes-outside` names paths outside the project, which the
  spec's README ("Published output names paths relative to the project")
  does not allow for.
- G-38: whether `:breaches` counts runs that breached or breaches.

## Spec gaps

What the spec leaves unstated about this slice's components. For each: the
scenario or rule, what is unstated, the smallest statement that would close
it, and whether it must be stated before the build (VISIBLE: a grader could
see it) or may stay the builder's choice (FREE: no grader could).

### Checklist

- **G-01** (spec-0019, spec-0210). checklist's standard output on exit 0.
  Smallest: a contract row, e.g. `{:checklist {:cycle "03" :cut "spec-3"
  :items 11 :planned 1}}`. VISIBLE.
- **G-02** (spec-0019, spec-0173). A cycle with no cycle.edn, one that is
  not EDN, one with no `:cut`, or whose `:cut` is no cut (`spec-N` tag):
  exit 2 naming it, nothing written. Smallest: one row of spec-0211's kind
  each. VISIBLE.
- **G-03** (spec-0266, spec-0389, spec-0395). Which configuration the
  checklist reads (`:regulated`, `:spec-layout`, `:specs`): the cut's, as
  verify reads the cut's (spec-0205), or the working tree's. Smallest: "the
  checklist reads the configuration at the cut". VISIBLE. Recommendation:
  the cut's (ED-03).
- **G-04** (spec-0200, spec-0389, spec-0396). A scope naming a context, a
  component or a spec the cut does not hold (spec-0396 states it only for a
  slice); a cycle.edn with neither `:scope` nor `:slice`. Smallest: rows of
  spec-0396 (exit 2, naming it), and "no scope is the project". VISIBLE.
- **G-05** (spec-0210, spec-0266, spec-0414). The order of an item's keys
  when it carries `slice` and `role` (after `level`?); "sorted by id" for
  ids of different widths (`spec-0999`, `spec-01000`) or prefixes (several
  specs): by text or by number. spec-0266 already places clause items after
  every scenario item. Smallest: "slice and role follow level; ids sort by
  their prefix, then their number". VISIBLE.
- **G-06** (spec-0210; the glossary's "Scope"). An item's `context` for a
  kernel component (`"kernel"`, as a scope names it) and its `component`
  (the model's `:name`, `"Event bus"`). Smallest: one glossary sentence
  under "Scope", beside "A checklist names an item's context and component
  by the model's :name". VISIBLE.
- **G-07** (spec-0395, spec-0414). Which cycles' items carry `slice` and
  `role`: every item of a cycle scoped to one slice (`:slice`, or
  `[{:slice S}]`), none of another; and a list of two slices. Smallest: "an
  item of a slice cycle names its slice and its role; no other cycle's
  item carries either". VISIBLE.
- **G-08** (spec-0019, spec-0210). The planned scenarios of a scoped cycle:
  only the scope's, or every one. Smallest: "planned lists the scope's @wip
  scenarios". VISIBLE.
- **G-09** (spec-0211). A construct the parser refuses at the cut: exit 1,
  nothing written, naming it, as an unstamped scenario. Smallest: a row of
  spec-0211. VISIBLE.
- **G-10** (spec-0019). A checklist.json already there: written again.
  Smallest: "checklist writes checklist.json afresh". VISIBLE (a refusal
  would be seen). Recommendation: written again.
- **G-11** (spec-0266, spec-0271; the glossary's "Clause item"). Clause
  items in a cycle scoped to a slice (`10-regulated-profile` reaches
  compliance/gap) or to a spec, and their `role` in a slice cycle. The
  glossary names only the project and a scope naming the context
  compliance. VISIBLE.
- **G-12** (spec-0395; the glossary's "Slice"). A component that owns a
  feature file in the slice holding no scenario: reached, so its scenarios
  elsewhere are items, or not. Smallest: "a slice reaches the components
  owning a feature file in its folder" (then ED-05's option C, for verify
  too). VISIBLE.
- **G-13** (spec-0210). checklist.json's layout (indentation, a final
  newline): spec-0210 compares it read as JSON, with its keys' order. FREE.
  **The builder's choice: no grader sees it.**

### Scorer

- **G-14** (spec-0174, spec-0212). score's standard output on exit 0.
  Smallest: a contract row, e.g. `{:score {:cycle "03" :passed 1 :scored
  2}}`. VISIBLE.
- **G-15** (spec-0143, spec-0144, spec-0214, spec-0213, spec-0022). The
  order of the scorer's refusals when several hold, and the exit when one
  is exit 2 (no verdict) and another exit 1. Recommendation: cycle.edn and
  its roles, ratified.edn, the checklist, then the verdicts, no verdict
  last. VISIBLE only on a combined fixture.
- **G-16** (spec-0174). A cycle with no checklist.json: exit 2, nothing
  written. Smallest: one row. VISIBLE.
- **G-17** (spec-0214, spec-0174). ratified.edn's `:cut` other than
  cycle.edn's, or checklist.json's `cut` other than cycle.edn's: scored, or
  refused (exit 1, the cycle has no passing verification of its cut).
  VISIBLE (ED-06).
- **G-18** (spec-0022, spec-0379). Several files, one of them empty: zero
  is counted over every file given. Smallest: "no verdict in any file
  given". VISIBLE.
- **G-19** (spec-0174, spec-0418). score.edn's `:items` order (the
  checklist's, or by id), its `:scope` for a cycle with `:slice` (`[{:slice
  S}]` or as written), and `:slice` and `:regression` left out of a cycle
  that builds no slice. VISIBLE. Recommendation: the checklist's order; the
  scope as read; both keys only in a slice cycle.
- **G-20** (spec-0143, spec-0379). The builder compared with the grader and
  the spec owner by `:name` alone; several graders (spec-0379's "two
  graders") in one cycle.edn, whose `:grader` is one person. Smallest: "a
  cycle's :grader is one person or a vector of them; a builder is the same
  person when the names are equal". VISIBLE.
- **G-21** (spec-0212, spec-0213). A verdict with a key beyond `:id`,
  `:verdict` and `:evidence`; evidence that is an empty string, or not a
  string. Smallest: rows of spec-0213. VISIBLE.
- **G-22** (spec-0174). A cycle already scored: score.edn written again or
  refused. VISIBLE. Recommendation: refused, exit 2, so a filed score is
  never silently replaced.
- **G-23** (spec-0380, spec-0213). FILE in `FILE:LINE` as given on the
  command line; a `--verdicts` file that does not exist, or no
  `--verdicts` at all: exit 2. Smallest: one row each. VISIBLE.

### Scoreboard

- **G-24** (spec-0192). The result when a cycle is bootstrap: its shape
  (`{:scoreboard {:cycles [...] :bootstrap ["00"] :statements [...]}}`?).
  Smallest: a contract row. VISIBLE.
- **G-25** (spec-0020). A cycle with no score.edn, or no cycle.edn: exit 2,
  naming it. VISIBLE.
- **G-26** (spec-0021). The statement for a moved denominator, word for
  word (spec-0284 states its two), and whether `:only-in` holds the items
  or their count. Smallest: a row of spec-0283 with a moved denominator.
  VISIBLE.
- **G-27** (spec-0225, spec-0284). A scoped first cycle, two scoped
  cycles, and a slice cycle's target: its slice's own items, or every item
  it scored (regressions included). spec-0284 states only a whole first
  cycle and a scoped second. VISIBLE. Recommendation: the target is every
  item the scoped cycle scored; its `:scope` printed as read (G-19).
- **G-28** (spec-0283, spec-0284). `:better-items`, `:worse-items` and
  `:only-in` in order of id. VISIBLE.

### Observe

- **G-29** (spec-0287; the glossary's "Observation"). Which entries are
  compared: a watched directory's own entries or its subtree; what a
  change is (added, removed, changed size or time); ROOM, the project and
  the directories holding them left out (ED-09). Smallest: one glossary
  sentence under "Observation". VISIBLE.
- **G-30** (spec-0288). `:tmp-left`: the entries of T that were not there
  at begin; one there at begin and removed counts nothing. VISIBLE.
- **G-31** (spec-0286, spec-0287). Each key's form: `:role` (a keyword, as
  cycle.edn names roles, or a string), `:room` (absolute), `:began` and
  `:ended` (an instant, as text?), `:minutes` (to what precision),
  `:tokens` when `--tokens` is not given (nil or 0), `:writes-outside`
  paths (absolute, `~` kept?; the spec's README asks for paths relative to
  the project, which these are not). Smallest: a contract row of spec-0286
  with values. VISIBLE.
- **G-32** (spec-0291). `:unobserved`'s shape and words: a vector of
  strings, one of them naming reads outside the room. Smallest: "e.g.
  [\"reads outside the room\"]". VISIBLE.
- **G-33** (spec-0289). What "outside its cycle" leaves out (the cycle's
  directory; ROOM, when inside the project), whether a path already changed
  at begin and changed again counts, and whether an untracked directory is
  named as itself or by its files. VISIBLE. Recommendation: ED-10.
- **G-34** (spec-0290). "The configured results directory": the
  configuration's `:results-dir`, relative, looked for under ROOM; written
  when it exists there holding an entry. VISIBLE.
- **G-35** (spec-0292). begin twice for one role without end; a role other
  than builder, grader or integrator (the glossary's "Run"); a ROOM that
  does not exist. Smallest: rows of spec-0292 (exit 2). VISIBLE.
- **G-36** (spec-0286). A configuration with no `:agency`, and `~` with no
  home directory in the environment: watched nothing and named in
  `:unobserved`, or exit 2. VISIBLE.
- **G-37** (spec-0286, spec-0287). begin's and end's standard output, and
  whether `:tmp-left`, `:repo-touched` and `:results-in-room` warn as a
  write outside does. Smallest: a contract row. VISIBLE.
- **G-38** (spec-0293; the glossary's "Breach"). `:breaches`: the runs
  that breached, or every breach (each write, each entry left, each path,
  results in the room); a cycle with no runs.edn (zeros, or exit 2); a run
  begun and not ended. VISIBLE.
- **G-39** (ED-08). Where begin keeps what it saw, and its name. FREE: no
  scenario reads the cycle's directory beyond runs.edn. **The builder's
  choice: no grader sees it.**

VISIBLE: 37 (G-01 to G-12, G-14 to G-38).
FREE: 2 (G-13, G-39).
