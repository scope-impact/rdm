# Decisions: slice 08-plugins

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `bus.feature`, Feature: Event bus

Kernel component Event bus: dhf/spec/model.edn, :kernel.

Decided by the event storm's hotspot H11 (dhf/storm/2026-10-24-rdm.md):
bounded contexts meet only on an in-process bus, within one run. How it delivers is design (ADR
0002, "Events"); what a black box can see of it is that every run can show
its events, and the same inputs always show the same ones.

### `bus.feature`, Rule: The bus carries rdm-spec's own events, whatever a project's catalogue declares

rdm-spec is the harness, and a project is what it is used on. Its own
events, those between its contexts, are part of rdm-spec: a release
carries them, built from rdm-spec's own spec. A project's catalogue holds
the project's events, its design record, and never what the bus carries:
a project that declared one event of its own had its check refused until
it copied rdm-spec's five, and an empty catalogue left rdm-spec's
contracts unchecked (spec owner, cycle 14; it replaces spec-0221, cycle
00, A5, whose bus read the project's catalogue).

### `oracle.feature`, Feature: Oracle

rdm-spec regenerates its own instrument every cycle, so the instrument needs
a judge of its own (cycle 03, dhf/cycles/03/proposed-spec-changes.md, C12;
loyly:harness/README.md@17d68d2, "The pieces, in pipeline order"). That judge
must not be regenerated with what it judges: an oracle rebuilt from the same
spec agrees with whatever the spec got wrong, and its goldens broke at each
rebuild (cycles 16 to 18; dhf/cycles/18/proposed-spec-changes.md). So, as
Löyly holds its harness still, the oracle is not this component: it is a
command the project keeps by hand, outside its implementation (for rdm-spec,
dhf/harness/oracle/oracle.clj, which says what it judges and how). This
component only runs that command as the gate before fence and verify
(spec owner, spec-23). The scenarios that described the oracle's own
workings (spec-0228 to spec-0231, spec-0276, spec-0277, spec-0280,
spec-0325 to spec-0328) are removed with it. Since spec-25 the oracle is a
plugin answering :Gate (dhf/spec/08-plugins/plugins.feature; ADR 0003), the one
named "oracle"; this component is the oracle task, its shorthand.

### `oracle.feature`, Rule: Nothing is fenced or verified while the oracle is red

A room built, or a build verified, by an instrument nobody checked hands
the next cycle a judge nobody checked (loyly:harness/README.md@17d68d2,
"What makes the checks run").
The oracle is a Gate plugin: the command asks every plugin answering
:Gate before fence and verify and stops the task while one fails, so
neither fence nor verify knows the oracle (dhf/spec/08-plugins/plugins.feature;
dhf/spec/01-start-a-project/command.feature, "A gate stops the task").

### `plugins.feature`, Feature: Plugins

Kernel component Plugins: dhf/spec/model.edn, :kernel.

The core harness, and plugins each project composes (dhf/decisions/
adr-0003-core-and-plugins.md, spec owner, spec-25). A project's own judge
(rdm-spec's held-still oracle, Löyly's affordance contract and its
conformance check, a domain model) was either built into the core or bolted
onto a check; in the core it grows the core with every project's idea and
cannot evolve with its project. A plugin is a command the project keeps in a
kept path, declared in its configuration's :plugins, that either subscribes
to an event the core publishes (its published language, events.edn) or
answers a request the core makes (a Gate, a Check). Löyly keeps the
configuration as everything a repository knows about itself
(loyly:harness/README.md@17d68d2, the pieces table, config.sh).

### `plugins.feature`, Rule: A subscriber receives each event it subscribes to, in every task that publishes it

The core's events are its published language: a plugin reads the event as
events.edn declares it, and nothing of the core's internals.

### `plugins.feature`, Rule: A plugin answers through one interface

A plugin reports as a task does (dhf/spec/01-start-a-project/command.feature,
spec-0178): its findings one per line on its error stream, as
FILE:LINE: RULE: what; its standard output is relayed as notes.

### `plugins.feature`, Scenario: A plugin that runs past its time limit is stopped and named as having timed out (spec-0355)

Cycle 04 of Löyly: a probe stopped at its check's limit was reported as
"exited ," with nothing it printed, and took two cuts to diagnose.

### `plugins.feature`, Rule: An answerer is asked at its request's place

The core asks; the plugin answers. A Gate runs before fence and verify, in
place of the oracle that was built in (dhf/spec/08-plugins/oracle.feature).

### `plugins.feature`, Rule: A plugin is added from the templates rdm-spec carries, and then owned

Plugins are shared as templates, never as a running dependency: a project
copies one and then owns it (ADR 0003, "Shared as templates").

### `plugins.feature`, Scenario: An added template's plugin runs as it is declared (spec-0405)

spec-32, from cycle 24's grader: the ddd-domain template's command ran
dhf/harness/plugins/ddd-domain/lint.clj, which the template did not
carry, so a project that added it got a plugin that could not run.

### `plugins.feature`, Scenario: rdm-spec carries the same templates however it was installed (spec-0364)

Cycle 21: packaged from its sources alone, rdm-spec carried no template,
and plugin add found none (spec-0359, spec-0360, wording). Every package
carries them (spec owner, spec-26).

### `plugins.feature`, Rule: What a plugin needs to know is in the configuration

spec-29, from halla-health's wallet: its oracle needed the e2e tests'
folder and the sandbox's send window, which lived in the integrator's
environment and the plugin's script, a difference nobody declared.

### `plugins.feature`, Rule: A plugin can follow a verified cycle

spec-29: a project's grading (deploying its build for its graders, however
the project ships) starts when a cycle is verified, which no event said.

### `verify.feature`, Feature: Verify

Slice 08-plugins: verify's :plugin check (ADR 0004).

### `verify.feature`, Rule: A :plugin check asks the project's own judge

A project's own judge joins verify as a plugin answering :Check
(dhf/spec/08-plugins/plugins.feature; ADR 0003): verify asks it at its place in
:verify, in the checkout, and it answers as every plugin does.

### `verify.feature`, Scenario: A Check plugin's results are held to the mapping, and count (spec-0391)

spec-29: halla-health's acceptance harness answers :Check with Allure
results of the spec's scenarios; they reached no task.



## Expected decisions (planned for the component build, ADR 0006)

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`, cycle 5 of its build order;
`docs/wow/harness.md`, "The planner"): each question a builder of this slice
would otherwise decide alone, with its options and the planner's
recommendation. The spec owner settles each before the build, or turns it
into a scenario. Where the spec is silent on what rdm-spec does, the
question is a spec gap (below), not a decision. The tickets (`ticket-01` to
`ticket-06`) cite them as ED-NN and G-NN; slice 01's, 02's and 03's are
cited as "slice 01's ED-NN" and so on. The architecture is in `dhf/wit/`
(`kernel-bus.wit`, `kernel-plugins.wit`, `measurement-oracle.wit`,
`slice-08.wit`, `slice-08.wac`). Slice 02's settlements hold here: a stage
not built is a warning (its ED-02), events are values handed on as EDN (its
ED-04).

### ED-01: Scenarios whose task another slice builds

As slices 01 to 04, settled there: no bridge. This slice's components carry
27 scenarios, all in this slice. Five run fence or verify (slice 06, cycle
7): spec-0357, spec-0358, spec-0232, spec-0383, spec-0384. Verify's `:plugin`
check (`verify.feature` here: spec-0361, spec-0391, spec-0362, spec-0363) is
verification/verify's, named by slice 06's tickets.

- A. Those five pending until cycle 7 (`pending.md`); ticket 06 builds what
  they need now, done on unit tests and a stand-in caller.

Recommendation: A.

### ED-02: How every publishing slice's events reach the bus

Each publishing slice keeps its event records in an events interface of its
own (slice 03's ED-04): `events` (02), `knowledge-events` (03), and later
05's, 06's and 10's. Slice 02's check imports this slice's plugins stage, so
this slice importing any slice's events interface is a cycle no composition
can wire, and each new publisher would rebuild this slice.

- A. Between slices an event is the published language only: `{name,
  published-by, edn}`, the EDN `{:event :NAME}` merged with its carried keys,
  exactly what a plugin is given. check hands its run's events to the
  plugins stage; every other task that publishes (status, 05; verify's
  CycleVerified, 06) hands them to `kernel-plugins.deliver`. The bus holds
  rdm-spec's own catalogue (names, publishers, carried keys) built in, holds
  each event's EDN to it, and routes it; it imports no events interface. The
  typed records stay each slice's contract within itself.
- B. The bus imports every events interface: a cycle with slice 02, and a
  rebuild of this slice for each later publisher.
- C. One typed variant of every event in `kernel-bus`: the catalogue in two
  places, and 05's, 06's and 10's records written before their plans.

Recommendation: A. The model has no relationship between kernel/plugins and
kernel/bus; the spec owner may add "Routes events with" in the next spec
change.

### ED-03: How fence's and verify's requests, and other tasks' events, reach the plugins

The glossary's "Oracle (instrument)" says fence and verify ask the Gate; this
file's "Rule: Nothing is fenced or verified while the oracle is red" says
"the command asks every plugin answering :Gate". kernel/command (slice 01)
is built, and reaches later slices only through their tasks.

- A. Fence and verify (slice 06) import `kernel-plugins` and call `gate`
  before anything else, verify's `:plugin` check calls `ask`, and verify
  calls `deliver` with CycleVerified; status (05) calls `deliver`. Each
  importing slice wires `"rdm:spec/kernel-plugins@0.1.0":
  plugins["rdm:spec/kernel-plugins@0.1.0"]` in its own instance line of
  `rdm-spec.wac` (composed by the planner against a stand-in importer).
  Neither task knows the oracle, only the Gate.
- B. The command asks the Gate: slice 01 rebuilt outside its fence, its
  world importing this slice.

Recommendation: A; the spec owner rewords the rule's "the command asks" to
"fence and verify ask", as the glossary has it.

### ED-04: Who exports the slice's tasks

The slice runs oracle (measurement/oracle) and plugin (kernel/plugins).

- A. As slice 02's ED-05: a composition component, `plugins-tasks`, holding
  no logic: the table's rows of oracle and plugin (slice 01's ED-18), each
  run with its component.
- B. kernel/plugins exports the tasks and imports the oracle: a relationship
  the model lacks.

Recommendation: A.

### ED-05: Where the component build carries the templates

The templates are `plugins/` in this repository, a kept path, so the clean
room holds them. A component reads only the folders the host opens to it.

- A. kernel/plugins' crate carries every file of each template inside its
  component, read from `plugins/` when it is built: every copy of the build
  carries the same ones, wherever it is installed. A template is a folder
  holding `template.edn` (`dhf/harness/stack.md`).
- B. Read at run time from `plugins/` beside the entry point: every
  installed copy must carry the folder too.

Recommendation: A.

### ED-06: How the oracle task runs its plugin

The model relates measurement/oracle to the repository ("Reads fixtures and
goldens from") and to the scoreboard ("Runs and compares"), both from before
the oracle became a plugin (this file, "Feature: Oracle").

- A. measurement/oracle imports `kernel-plugins` and calls `run-named`: one
  place runs every plugin, with one RDM_SPEC and one `:env`.
- B. The oracle runs its command through the host itself: a second copy of
  how a plugin runs.

Recommendation: A, and the spec owner rewords the oracle's relationships
("Runs the plugin named oracle with" kernel/plugins) in the next spec change.

### ED-07: RDM_SPEC in the component build

A command line starting this same rdm-spec, however it was installed (the
glossary's "Oracle (instrument)"): here, the entry point `wasm/rdm-spec`,
which runs the host on the composed command. A component cannot see where
it was started from.

- A. `wasm/rdm-spec` sets RDM_SPEC to its own absolute path, links resolved,
  before it starts the host; the core reads it from its environment and sets
  it on every plugin last, after its `:env`; absent (the host started some
  other way), a plugin cannot run, saying so. The environment passes through,
  so the rdm-spec RDM_SPEC starts keeps the caller's TMPDIR.
- B. The host sets it to its own path and the command's: the host grows
  logic, and `$RDM_SPEC` skips the entry point.
- C. A host function naming the command: `rdm:host` grows.

Recommendation: A.

### ED-08: A plugin's run through the host

- A. One `rdm:host/process.run` per run: its command as `argv` (the oracle
  task's arguments appended), the project's root (or the checkout) as
  `cwd`, its `:env` and RDM_SPEC as `env`, the event's or request's EDN as
  `stdin`, `:seconds` as `timeout-ms`; the host stops its process group on
  timeout (spec-0355); `cannot-start` is a command that cannot be started
  (spec-0356, spec-0345).

Recommendation: A.

### ED-09: A plugin's lines in check's report

The plugins stage answers check in check's finding record (`spec-check.wit`:
file, line, rule, message, warning), and check writes each back as a line.

- A. Each line of the shapes `dhf/spec/README.md` states is split into that
  record so check writes it back unchanged (spec-0354), a unit test holding
  the round trip; a line of no such shape as G-06 settles.
- B. A raw line in the record's message: check's own formatting would
  change it.

Recommendation: A.

### ED-10: The host's standard interfaces in this slice's worlds

As slice 02's ED-08, settled there: the cycle that builds the slice adds to
`plugins` and each component world the one line slice 01's worlds carry, in
the version they carry then.

Recommendation: A.

## Spec gaps

What the spec leaves unstated about this slice's components. For each: the
scenario or rule, what is unstated, the smallest statement that would close
it, and whether it must be stated before the build (a grader could see it)
or may stay the builder's choice (no grader could).

- **G-01** (spec-0352; the glossary's "Event bus"). The order of deliveries
  when plugins subscribe to several events: plugin by plugin, each given its
  events in the order published, or event by event, each to its subscribers
  in the order declared. Smallest: a row of spec-0352 with two events.
  Recommendation: state before the build; plugin by plugin ("in the order it
  runs").
- **G-02** (spec-0354, spec-0355). Whether the subscribers after one that
  cannot run still run, and whether the findings of the others are still
  written when the task exits 2. Smallest: "every subscriber runs; a task
  whose plugin cannot run exits 2, naming each that could not". State before
  the build.
- **G-03** (spec-0357). Whether a Gate plugin after a failing one runs.
  Smallest: "the Gate plugins after a failing one do not run". State before
  cycle 7 (the Gate is asked by fence and verify).
- **G-04** (spec-0357, spec-0358). The Gate request fence gives: only
  verify's is stated. Smallest: spec-0358 with a fence row, e.g.
  `{:request :Gate :task :fence :cut "spec-3"}`. State before cycle 7.
- **G-05** (this file, "Rule: A plugin answers through one interface"). "Its
  standard output is relayed as notes": no scenario says where a note goes
  or how it is written; spec-0354's last row says only "no line" for a plugin
  that writes nothing. State before the build: dropped, or a line of a stated
  shape.
- **G-06** (spec-0354). An error-stream line of no finding or warning shape,
  on exit 0 or 1; and exit 1 with no finding line. Smallest: "such a line is a
  finding naming the plugin; exit 1 with none is one finding naming the
  plugin". State before the build.
- **G-07** (the glossary's "Plugin"). An exit other than 0, 1 or 2, or a
  plugin killed by a signal. Smallest: "any other exit cannot run, naming
  the plugin and its exit". State before the build.
- **G-08** (spec-0359). "Prints the :plugins entry to add": the result's
  shape on standard output (EDN). Smallest: a contract scenario naming the
  key that holds the entry. State before the build.
- **G-09** (spec-0359). `origin.edn`'s V and D: the version as the template
  writes it, or a string; the date's form (e.g. "2026-10-28") and whose
  clock (local or UTC). State before the build.
- **G-10** (spec-0364, the glossary's "Plugin"). "As a package (a jar)": this
  build has no jar. Smallest: the row reworded "as a package (the command
  copied outside its sources)". State before cycle 5's verdict, or the row
  waits for the switch (`pending.md`).
- **G-11** (spec-0405, row `affordances`). The template declares a plugin
  answering `:Check`, which check never asks, so "check runs the plugin" cannot
  hold. Smallest: the Then reworded "check accepts its declaration, and
  nothing answers cannot run", or the row moved to verify. State before the
  build.
- **G-12** (spec-0359). "Copied unchanged": contents only, or also each
  file's executable bit. State before the build, or say "its files'
  contents".
- **G-13** (spec-0342, spec-0343). Whether the oracle task holds the plugin
  to its `:seconds`, and what its standard input is. Smallest: "the oracle
  task runs it with no time limit and nothing on its input". State before
  the build: a long recording is visible.
- **G-14** (spec-0233). The result's shape when there is no oracle (standard
  output is EDN). State before the build.
- **G-15** (spec-0350, the glossary's "Plugin"). Which tasks refuse an
  untrusted `:plugins` before anything runs: check is stated; oracle, plugin
  add, fence, verify and status are not. And within check, the plugins stage
  is the last: whether the earlier stages' findings are still written with
  the refusal. Smallest: "every task that runs a plugin refuses it first,
  writing nothing else". State before the build.
- **G-16** (spec-0350, row "under the implementation's paths"). Which words
  of a command are paths (any word, or the first file argument). May stay the
  builder's choice: any reading refuses the row's fixture.
- **G-17** (the glossary's "Oracle (instrument)": "words separated by
  spaces"). An install path holding a space cannot be written so. May stay
  the builder's choice (cannot run, saying why).
- **G-18** (spec-0361, spec-0363). Whether verify's `:plugin` check reads
  `:plugins` from the cut's configuration or the working tree's. Slice 06's
  to state before cycle 7; `kernel-plugins.ask` takes the root either way.
