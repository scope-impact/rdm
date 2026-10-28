# Decisions: slice 05-verify-by-results

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `results.feature`, Rule: Allure results are the oracle, whoever wrote them

Incident: a test runner named its suites twice, once in imports and once in
a run list, so a forgotten suite was silently green
(loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").

### `results.feature`, Scenario: A results file that cannot be read is named, never skipped (spec-0062)

Cycle 16's reader relayed the parser's message, which held a newline, so
the finding took two lines (dhf/cycles/16/proposed-spec-changes.md, 2;
README, "How every task reports").

### `results.feature`, Rule: What decides a status, and what is only kept

A result is read for what status, trace and verify depend on, and every
other Allure element is kept: an attachment, a link, a parameter are
evidence a person reads in the report, never what decides a status.

### `runner.feature`, Feature: Gherkin runner

Kernel component Gherkin runner: dhf/spec/model.edn, :kernel.

Decided by the event storm's hotspot H10 (dhf/storm/2026-10-24-rdm.md): the
runner is kernel code, so any project whose step definitions are written for
it can use it. A project in another stack uses its own runner and only has
to write the same Allure results.

### `runner.feature`, Rule: Step definitions are written against one small API

rdm-spec run is offered to any project whose step definitions are written
for it, so the way a step is defined is a public contract.

### `runner.feature`, Scenario: A failing step fails the result and names the step (spec-0153)

spec-0153 and spec-0310 said a step that throws fails the result;
spec-0323 says only a Then whose assertion does not hold is failed
(cycle 17, grader).

### `runner.feature`, Rule: The runner discovers, and refuses to find nothing

Incident: a test runner named its suites twice, once in imports and once in
a run list, so a forgotten suite was silently green (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").

### `runner.feature`, Rule: Every result is written in Allure's own model, from its Gherkin scenario

rdm-spec reads Allure results and writes them; it never invents a format
beside Allure's. Each element a result carries is Allure's own, filled from
the Gherkin scenario, so an Allure report is one more view of the same run,
and a project in any stack can write the same results with its own
Allure-Gherkin adapter (spec owner, after the Löyly comparison,
dhf/cycles/loyly-comparison.md, finding 2: a builder could not learn the
result format from its own project).

### `runner.feature`, Scenario: Failed is a Then that did not hold; broken is anything else that went wrong (spec-0323)

Two of the six things cycle 02's builder of the Löyly app decided alone
(dhf/cycles/loyly-comparison.md, cycle 02).

### `runner.feature`, Rule: A scenario's tags are its own and those it inherits

spec-32, from cycle 24: cycles 19 to 23 wrote a tag label for every tag a
scenario inherits from its feature and its rule, cycle 24 only for its
own, and each build's verify refused the other's results; spec-0308 said
"one per Gherkin tag" and no more. A need tag sits on a feature or a rule
(README.md, "Conventions"), so the scenario's inherited tags are the ones
that trace it; Allure's own Gherkin adapters write them too.

### `status.feature`, Rule: Status's result has one shape

Every other task prints its result under its own name, and status printed
its map bare, a shape no scenario stated (cycle 14, builder; README, "A
task's result shape is the spec's").

### `status.feature`, Scenario: Results that name no row do not verify an outline (spec-0302)

An outline with fewer results than rows, all passing, counted as
verified (cycle 11, builder): a release could pass on rows never run.
Three results naming no row verified a three-row outline (cycle 13,
grading): re-runs of one row could pass a release.

### `status.feature`, Rule: Allure results are the oracle, whoever wrote them

Incident: a test runner named its suites twice, once in imports and once in
a run list, so a forgotten suite was silently green
(loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").

### `trace.feature`, Rule: The trace's result has one shape

Cycle 08: spec/check's step read trace's result in one shape and the
rebuilt trace wrote another; the shape is now the spec's.

## Expected decisions (planned for the component build, ADR 0006)

Settled on 2026-10-28. ED-02 by the spec owner: the step protocol (each step
file a command, in any language, speaking EDN: `definitions`, then `invoke
N`) is rdm-spec's behaviour, stated in this slice's runner scenarios, not a
stack choice. ED-09 by the spec owner, in cycle 26 (see ED-09). The rest
accepted as recommended by the integrator under the spec owner's goal,
among them ED-07 (release refuses, exit 2, under `:regulated` until slice 10
exists) and ED-03 (the model gains the runner reading scenarios with the
parser), both for the spec owner to overrule.

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`, cycle 6 of its build order;
`docs/wow/harness.md`, "The planner"): each question a builder of this slice
would otherwise decide alone, with its options and the planner's
recommendation. The spec owner settles each before the build, or turns it
into a scenario. Where the spec is silent on what rdm-spec does, the
question is a spec gap (below), not a decision. The tickets (`ticket-01` to
`ticket-07`) cite them as ED-NN and G-NN; other slices' are cited as "slice
08's ED-NN" and so on. The architecture is in `dhf/wit/`
(`kernel-runner.wit`, `verification-results.wit`, `verification-status.wit`
with the event record `verification-events`, `verification-trace.wit`,
`verification-release.wit`, `slice-05.wit`, `slice-05.wac`). The settlements
of slices 01, 02 and 08 hold here: no bridge (their ED-01), events are values
handed on as EDN between slices (slice 02's ED-04, slice 08's ED-02), a
task's interface uses only its own and builtin types (slice 01's ED-14).

### ED-01: Scenarios whose task another slice builds

As slices 01 to 08, settled there: no bridge. This slice's components carry
54 scenarios: 47 in this slice, 2 in `10-regulated-profile`, 3 in
`12-several-specs`, 2 in `13-slice-development`. Three need a task a later
cycle builds: spec-0403's last Then runs verify's tests check (slice 06,
cycle 7); spec-0267 and spec-0278 need gap analysis (compliance/gap, slice
10, cycle 10), which release asks under `:regulated` (ED-07).

- A. Those three pending (`pending.md`); their tickets build what they need
  now, done on unit tests and a stand-in clauses stage.

Recommendation: A.

### ED-02: What a project's step definitions are in the component build

The hardest question of the slice. `rdm-spec run` runs a project's scenarios
against its step definitions, "written against one small API" that is a
public contract (`runner.feature`, its Rule; `decisions.md` above). The stack
states that API only for the installed release, in the release's own
language (`dhf/harness/stack.md`, "Runner's step API"). The component build's
core runs inside a sandbox that cannot load code it was not composed with,
and reaches processes only through `rdm:host/process`, whose `run` takes the
whole standard input and returns at exit. spec-0168's Given writes a step
definition file "through the runner's step API", so a grader must know the
API: whatever is chosen is visible (G-01).

- A. **A step definition file is a command, speaking EDN.** Every executable
  file under the configuration's `:steps-dir`, in the order of its path, in
  any language, run through `rdm:host/process` in the project's root, with
  `RDM_SPEC` set as a plugin's is (slice 08's ED-07). Asked once per run for
  its definitions (`FILE definitions`: standard output an EDN vector of
  patterns, in order); asked once per step it matches (`FILE invoke N`,
  standard input `{:context C :captured [S ...]}`): exit 0, its standard
  output `{:return R :attachments [{:name :content :type}]}`, a map R the
  next context and anything else leaving it as it was (spec-0169); exit 1, a
  Then's assertion that did not hold (failed), its error stream the message;
  any other exit, a signal or no answer: broken, its error stream the trace
  (spec-0323). The same shape as a plugin's (one interface, the published
  language EDN, exits 0, 1, 2): nothing in the host changes. Its cost: one
  process per step.
- B. **A step definition file is a component** the project builds to a world
  `rdm:steps` (definitions, invoke), loaded at run time: the host grows
  (instantiating a component the composition does not hold), and writing one
  step needs a component toolchain, for a grader too.
- C. **The installed release's API kept:** step files in the release's
  language, run by that language's runtime through the host, with a runner
  script rdm-spec carries: logic outside the core, and the core depending on
  a runtime the stack has otherwise left.
- D. **One step process per run**, a server kept alive (the wire protocol of
  other Gherkin tools): needs streaming pipes `rdm:host/process` does not
  give; the host grows.

Recommendation: A, written into `dhf/harness/stack.md`'s component-build
table as its "Runner's step API" row before the build (G-01), the patterns'
matching stated in the glossary (G-02). At the switch the public contract
changes for any project whose step files are in the release's language: the
spec owner decides whether C is kept beside A as a path for them (question
below). rdm-spec's own scenarios are not run this way: the builder's driver
is native (slice 01's ED-10).

### ED-03: How the runner reads the scenarios

The runner labels each result with its scenario's id, context, component,
feature, rule and tags, inherited ones too (spec-0151, spec-0308, spec-0403),
and runs the background and every outline row: what spec/parser already
reads, with each scenario's owner from the model. The model has no
relationship from kernel/runner to spec/parser.

- A. kernel/runner imports `spec-parser` (slice 02; its import brings the
  event records the parser exports), as `kernel-runner-component` is written;
  `rdm-spec.wac` wires both in this slice's instance line. Slice 02 imports
  nothing of this slice, so no cycle. The model gains `{:from :kernel/runner
  :to :spec/parser :description "Reads scenarios with"}` in the next spec
  change.
- B. A Gherkin reader of the runner's own: the subset, the tags' inheritance
  and the model's ownership in two places.

Recommendation: A.

### ED-04: How status reads SpecParsed

The model has spec/parser publish SpecParsed to verification/status.

- A. As slice 02's ED-04, settled there: status calls `spec-parser.parse` on
  the working tree and reads the typed `spec-parsed` it returns, the event
  handed on as a value; trace does the same for a need's scenarios. Both
  import `spec-parser` from slice 02.
- B. Status reads the EDN of the event: a second reading of what the typed
  record already holds within one composition.

Recommendation: A.

### ED-05: Where VerificationDerived's record lives

`events.wit` holds one record per event; slice 03 added KnowledgeIndexed in
an interface of its own (its ED-04), since `events` is exported by
spec/parser.

- A. An interface of its own, `verification-events`, exported by
  verification/status beside `verification-status`, as knowledge/sources
  exports `knowledge-events`; written in `verification-status.wit`, beside
  its publisher. `events.wit`'s header comment names it in the cycle that
  builds the slice (this plan wrote only the slice's own files).
- B. The same interface moved into `events.wit`, with slice 03's.

Recommendation: A; B is a move with no change, for the spec owner's taste.

### ED-06: How VerificationDerived reaches the plugins

- A. As slice 08's ED-03: `verification-status.deliver` hands the event, as
  EDN, to `kernel-plugins.deliver`, after the task's own work; the status
  task calls it, and trace and release call it when G-20 says they publish.
  Its answer joins the task's report: the plugins' findings are the task's,
  exit 1; a plugin that cannot run, exit 2 (slice 08's G-02). Only status
  imports kernel/plugins (the model's only relationship of this slice to
  it).
- B. Each task component imports kernel/plugins: three relationships the
  model lacks.

Recommendation: A.

### ED-07: The release's regulated rule before slice 10

spec-0267 and spec-0278 refuse a release on gap analysis' findings
(compliance/gap, slice 10, cycle 10; the model's "Publishes ClausesIndexed
to" release). The plan keeps "its regulated rule until 10-regulated-profile
holds it" in this slice.

- A. release imports `clauses-stage`, the stage interface check imports,
  written out the same (`verification-release.wit`), wired by name in
  `rdm-spec.wac` to the not-built stand-in's spread until slice 10 is built.
  Asked only where the configuration holds `:regulated` (slice 01's ED-17);
  an answer of none then refuses the release (exit 2, `cannot-run: release's
  clauses stage is not built`): a release check is a gate, and a stage
  skipped reads as a pass (the glossary's "Gate"), unlike check's warning
  (slice 02's ED-02). Its findings refuse the release (exit 1), ClausesIndexed
  read from its published events as EDN.
- B. release imports compliance/gap's own interface: slice 10's types in
  this slice, before slice 10 is planned.

Recommendation: A. A consequence for slice 10's plan: audit runs the release
check (the glossary's "Gate"), so slice 10 imports this slice while this
slice imports slice 10's clauses stage: a cycle between the two slices'
instances. Slice 10's plan breaks it as slice 02's ED-02 foresaw for slice
06, compliance/gap instantiated in `rdm-spec.wac` on its own, before this
slice, and slice 10's composition importing it.

### ED-08: Who exports the slice's tasks

- A. As slice 02's ED-05 and slice 08's ED-04: a composition component,
  `verify-by-results-tasks`, holding no logic: the table's rows of run,
  status, trace and release (slice 01's ED-18), each run with its component.

Recommendation: A.

### ED-09: The command's refusal of a missing results directory, for trace and run

**Settled: A, in cycle 26 itself.** The command was still being built when this was found; slice 01's ED-16 was narrowed there (refusal only for status, release, publish, audit, and trace when given), so no later cycle changes slice 01's command.

The command (slice 01, built in cycle 1) settles the results directory of
every task whose usage takes `[--results DIR]` and refuses one that does not
exist, exit 2, before the task runs (slice 01's ticket 04, its ED-16). Two
scenarios of this slice's components, and one of slice 02's, need otherwise:
spec-0108 and spec-0047 run trace with no `--results` where the configured
directory does not exist, and expect a trace "with no status derived";
spec-0324 has run write into the configured directory, which a project set
up by init does not hold (G-03).

- A. kernel/command changes: a missing directory is refused for the tasks
  that read results (status, release, publish, audit; trace when `--results`
  or RESULTS_DIR names it, spec-0107); trace is handed none when the
  configured one does not exist; run is handed its directory and creates it.
  kernel/command is slice 01's: the spec owner decides how the change enters
  this cycle (slice 01's command in its scope, its scenarios regression
  items), or a cycle of its own before it.
- B. The spec changes instead: spec-0108 and spec-0047 give a results
  directory that exists, and spec-0324's Given makes the directory.
- C. Neither: the three scenarios fail in this build.

Recommendation: A, decided before the build; the scenarios state the
behaviour, so the command is what is wrong. The `tasks` request's
`results-dir` is already an option.

### ED-10: Reading and writing Allure's files

The runner writes Allure results and attachments; the results reader reads
them; verify (slice 06) reads them too, through this slice's reader.

- A. Each crate that needs the format takes it from a library of the stack's
  registry; nothing shared between crates: the runner writes, the reader
  reads, and a unit test of each holds the other's format (a result the
  runner wrote, read back by the reader).
- B. The kernel library (slice 01's ED-13) gains it: slice 01's crate,
  outside this cycle's fence.

Recommendation: A.

### ED-11: The host's standard interfaces in this slice's worlds

As slice 02's ED-08, settled there: the cycle that builds the slice adds to
`verify-by-results` and each component world the one line slice 01's worlds
carry, in the version they carry then.

Recommendation: A.

## Spec gaps

What the spec leaves unstated about this slice's components. For each: the
scenario or rule, what is unstated, the smallest statement that would close
it, and whether it must be stated before the build (VISIBLE: a grader could
see it) or may stay the builder's choice (FREE: no grader could).

### The runner

- **G-01** (spec-0168; the glossary's "Step definition"; ED-02). The step API
  of the component build: what a step definition file is, how it declares a
  pattern, what it is given (context, captured texts, its working directory,
  its environment), how it returns, attaches evidence and fails. Smallest:
  the "Runner's step API" row of `dhf/harness/stack.md`'s component-build
  table, as ED-02 settles. VISIBLE: spec-0168's Given writes a file in it.
- **G-02** (spec-0168, spec-0170). How a pattern matches a step: against its
  text without its keyword (spec-0168 implies it), the whole text or any part
  of it, in which expression dialect; whether And and But steps match like
  the step before them. Smallest: glossary "Step definition": "its pattern is
  a regular expression matched against the whole of a step's text, its
  keyword left out; each group is one captured text". VISIBLE.
- **G-03** (spec-0324, spec-0219). Whether run creates its results directory
  when it does not exist, or is refused as status is (slice 01's ticket 04).
  Smallest: spec-0324's Given adds "and no such directory", its Then "creates
  it". VISIBLE (ED-09).
- **G-04** (spec-0155, spec-0156). Whether run removes the results an earlier
  run left in its directory: a later status reads both. Smallest: "run adds
  its results and removes no file" or "replaces every result there".
  VISIBLE.
- **G-05** (`runner.feature`). run's exit when a scenario failed or broke (0
  as a report, or 1), and its result on standard output (EDN, a shape no
  scenario states: `dhf/spec/README.md`, "A task's result shape is the
  spec's"). Smallest: a contract scenario, e.g. `{:run {:results DIR :counts
  {:passed :failed :broken :skipped}}}` with its exit. VISIBLE.
- **G-06** (spec-0308, row steps; verify's spec-0331,
  steps-differ-from-scenario). Whether a feature's Background runs before each
  of its scenarios, and whether its steps are among the result's steps.
  Smallest: a row of spec-0308. VISIBLE.
- **G-07** (spec-0151, spec-0155). Which scenarios run under several specs
  (every spec of `:specs`) and under the slices layout; and a scenario with no
  id, or several: run or not, and its result's spec label. Smallest: "run
  runs every scenario of every spec; a result carries a spec label only for a
  scenario with exactly one id". VISIBLE.
- **G-08** (spec-0308, rows epic, suite, links; the glossary's "Allure
  mapping"). Whether run fills a result by the project's `:allure` mapping or
  always by rdm-spec's default; verify holds results to the project's.
  Smallest: "run fills each element as the project's mapping says".
  VISIBLE.
- **G-09** (spec-0169). Under ED-02 A, a step file that prints nothing, or
  no EDN, on exit 0: its return is "anything but a map". FREE once G-01 is
  stated.
- **G-10** (spec-0170, spec-0032, spec-0153). Where a result names the
  unmatched step, both definitions, or the failing step: its statusDetails'
  message is Allure's place. FREE: the scenarios say only "names".
- **G-11** (spec-0154). A planned scenario's result: its steps listed, each
  skipped, or none. FREE: verify holds it to its spec label only (spec-0347).
- **G-12** (spec-0311). An attachment's file name in the results directory.
  FREE (Allure's own `<uuid>-attachment.<ext>`).

### Results and status

- **G-13** (spec-0062, spec-0313). The exit of status, trace and release when
  a results file cannot be read, and whether its line is a finding (`FILE:
  RULE: what`, exit 1, the README's "the results") or a warning; its rule is
  the component's to choose. Smallest: spec-0062's Then gains its exit and
  its line's form. VISIBLE.
- **G-14** (spec-0068 row unknown, spec-0313). spec-0068 reads the status
  "unknown" as a status that does not pass, as the glossary's "Oracle" does;
  spec-0313's message names four statuses and the glossary's "Allure result"
  lists four. Smallest: spec-0313's message "passed, failed, broken, skipped
  or unknown", and the glossary's list the same. VISIBLE.
- **G-15** (spec-0060, spec-0307). Whether a result naming no stamped
  scenario is also a warning line, beside `:unknown-results`. VISIBLE.
- **G-16** (spec-0302, spec-0285, spec-0309; the glossary's "a result counts
  toward a row only when it names the row"). What naming a row is: the
  result's parameters exactly the row's headings, each with the row's value,
  in any order or in the header's; two identical rows; a result with
  parameters for a scenario that is no outline. Smallest: one glossary
  sentence. VISIBLE.
- **G-17** (spec-0408). The key naming each scenario's spec in status's
  result (`:spec`?), and whether `:counts` is per spec. Smallest: a contract
  row beside spec-0307. VISIBLE.
- **G-18** (spec-0419, spec-0307). Status's result under the slices layout:
  spec-0307 says "exactly this shape", spec-0419 adds `:slices`; whether each
  scenario map gains `:slice`; a slice whose plan has no status line, or no
  scenario. VISIBLE.
- **G-19** (spec-0408, spec-0419, spec-0390, spec-0415, spec-0409). A
  `--spec` or `--slice` that names none, `--slice` without the slices
  layout, `--spec` without `:specs`: exit 2 naming it? And spec-0176's usage
  of status, trace and release names neither flag. Smallest: one row each in
  spec-0219's style, and the usages in spec-0176. VISIBLE.
- **G-20** (`08-plugins/plugins.feature`, "A subscriber receives each event
  it subscribes to, in every task that publishes it"; `events.edn`,
  VerificationDerived). Which tasks publish VerificationDerived: status only,
  or trace with results and release too, each deriving statuses; and
  `:statuses`' keys (the id as a string) and `:unreadable-files`' paths
  (DIR/name). Smallest: a row of spec-0353's kind for each task. VISIBLE.

### Trace

- **G-21** (spec-0104, spec-0105). The shape of a scenario's trace; spec-0279
  states only a need's. Smallest: a contract scenario, e.g. `{:trace {:kind
  :scenario :id :title :needs [...] :component :context :level :status
  :results [{:file :status}]}}`. VISIBLE.
- **G-22** (spec-0279, spec-0104). `:component` and `:context`: the model's
  names ("Lint") or ids (`:architecture/lint`), and `:level` as a string or a
  keyword. VISIBLE.
- **G-23** (spec-0103, spec-0108). Where "no scenario traces to UN-007" and
  "no status was derived because no results were given" are said: in the
  result (`:scenarios []`, `:status` left out, a key saying why) or as a
  warning line; and their exit, 0. VISIBLE.
- **G-24** (spec-0108, spec-0219). Without `--results`, whether trace reads
  the configured results directory when it exists, as every task's
  directory defaults (spec-0219), or derives no status unless results are
  given. Smallest: a row of spec-0108 where the configured directory exists.
  VISIBLE (ED-09).

### Release

- **G-25** (spec-0059, spec-0188, spec-0189, spec-0190). Release's standard
  output (exit 0, and exit 1, `{}` when there is none), and each refusal's
  line: the scenario's file and line, as a finding about it. Smallest: a
  contract scenario of release's result and line. VISIBLE.
- **G-26** (`release.feature`). Whether unknown results, or a file that
  cannot be read, refuse a release. VISIBLE.
- **G-27** (spec-0267, spec-0278). The order of the clauses' findings and the
  scenarios' in release's report. FREE: findings come in no stated order
  (the glossary's "Finding").

VISIBLE: 22 (G-01 to G-08, G-13 to G-26). FREE: 5 (G-09 to G-12, G-27).

## Questions for the spec owner

- ED-02: the step API of the component build (A recommended), and whether a
  project's step files in the installed release's language keep running
  after the switch (C beside A), since the step API is a public contract.
- ED-09: how kernel/command's change (slice 01) enters this cycle, or
  whether the three scenarios change instead.
- ED-07: release refuses (exit 2) under `:regulated` until slice 10 is
  built, unlike check's warning; and slice 10 breaks the cycle between the
  two slices by instantiating compliance/gap on its own.
- The model: kernel/runner "Reads scenarios with" spec/parser (ED-03).
