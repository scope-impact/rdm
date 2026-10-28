# The agent harness

As Löyly's `docs/wow/harness.md` (loyly@17d68d2): how to point an agent at
this repository, as a planner, a builder, a reviewer or a grader. A brief
names the role, the cycle, the room and the slice; everything else is here,
so every cycle's agents work the same way. Guidance, not spec: the spec says
what rdm-spec does, and a grader holds a build to nothing else. The rules
here, with what enforces each, are in `docs/wow/invariants.md`.

Two ideas carry it: **planning and building are separate invocations**, and
**done is ratified, never claimed** (loyly, harness, sections 1 and 2).

## The guardrails

Each exists because agents drift in a specific, observed way (loyly, harness,
section 3), and each names what holds it here.

| Guardrail | What it does | Here |
|---|---|---|
| Spec-lock | specs change in planning, never mid-build | verify reads the spec and configuration from the cut, never the room (spec-0205) |
| Orient step | every invocation starts by re-reading the sources of truth | each role's read order, below |
| Ratified done | done counts only when a check the builder does not control passes | `rdm-spec verify` |
| Scope fence | edits outside the slice's paths do not count | fence removes only the slice's components; verify takes only implementation paths from the room |
| Plan, then build | the plan is not retro-fitted to what was built | the planner, before the builder |
| Test-first | a test before the code it tests; retroactive tests are a failed round | the builder's report; not yet enforced (INV-16) |
| Adversarial review | a pass that hunts invented rules and drift from the plan | the reviewer, every slice cycle |
| Real-interface checks | verification drives the product as its user would | graders, black box on the packaged build |

The table is strong at telling an agent whether it is right and weak at
handing it something to look at with no verdict attached. Nothing here gives a
builder an unjudged look at the running product; that step is not built.

## The planner

You plan one slice; you build nothing. Plan only: write no code, no step
definition, no test (loyly, harness, section 1).

- **Read** `README.md`, `docs/wow/`, the wiki from its index, then the slice:
  `plan.md`, `decisions.md`, its feature files, and the feature files of the
  components it reaches.
- **Write the tickets:** `dhf/spec/<NN-slice>/ticket-NN-<slug>.md`, one
  coherent unit each: what it delivers, the scenario ids it satisfies, the
  components it touches, and done when. Every scenario of the slice is
  named by exactly one ticket. Order them as they must be built.
- **Write what the spec leaves open:** each question a builder would have to
  decide, as an expected decision in `decisions.md` with the options. The
  spec owner settles them before the build, or turns them into scenarios.
- **Report:** the tickets, the open questions, and any scenario you found
  ambiguous or missing.

## The builder

You rebuild part, or all, of the implementation from a cut of the spec, in a
clean room fenced from it. Nobody checks your claim of done but the gate
(`rdm-spec verify`) and a grader who never sees your tests. 

### Read first, in this order (the orient step)

1. `dhf/decisions/adr-0002-spec-first-regeneration.md`
2. `dhf/spec/README.md` (the spec's conventions) and `dhf/spec/glossary.md`
3. `dhf/wiki/pages/loyly-method.md` (where the spec is silent)
4. `dhf/harness/stack.md`, `dhf/harness/config.edn`, the stack's guidance
   (for TypeScript, `docs/wow/functional-ddd-typescript.md`)
5. `dhf/spec/model.edn`, `dhf/spec/events.edn`, then your slice: its
   `plan.md` (goal, scope in and out), its `decisions.md`, its feature files,
   then the other feature files of the components it reaches (their
   scenarios are your regression items; ADR 0005)

### How you work

- **One builder, your scopes in the order the brief lists them** (the order
  `rdm-spec scopes` gives: the kernel first, then publishers before their
  subscribers).
- **Ticket by ticket,** in the plan's order. For each: its scenarios' step
  definitions first, run, red; then a unit test, red; then the least code
  that turns both green; refactor only when green (loyly, harness, section
  2). Keep every run's summary line in `<room>/redgreen.log`, one line per
  run: time, ticket, red or green, the summary. A ticket whose first run was
  green had its test written after its code: say so in your report.
  Retroactive tests are forbidden.
- **Keep what others rely on:** every shared step text, context key and
  function other scopes call keeps its meaning (grep the callers).
- **Where the spec is silent,** follow `dhf/wiki/pages/loyly-method.md` and
  name the rule; where Löyly is silent too, decide, and record the decision
  with the scenario it belongs to.
- **Done** is: every scenario of your scopes passes, the whole suite passes,
  your unit tests pass, and a run leaves no fixture and no process behind.

### Where you may write

- **Only in your room,** plus directories you make inside it with
  `mktemp -d -p <room>`, deleted before you finish. Never `/tmp`, a
  scratchpad, the room's parent, your home directory or `/`. Your run is
  observed, and any write outside is a breach.
- **Run every suite with `RESULTS_DIR` and `TMPDIR` set to mktemp
  directories inside the room** (and `-Djava.io.tmpdir` where the stack needs
  it), and `GIT_CEILING_DIRECTORIES` to the room's parent.
- **Never edit `dhf/`,** `dhf/harness/oracle/` above all. Never commit in your
  room; commit in fixtures with hooks off. Run the code by path, never an
  `rdm-spec` on PATH. Stop every process you start, also when a test gives
  up on it. Do not run long commands in the background.

### Writing the tests

The rules below came from builds that broke without them; each names its
incident.

- **A fixture inside another git repository is fenced from it:** its commands
  run with GIT_CEILING_DIRECTORIES set to the fixture's parent, so git never
  finds the enclosing repository (cycle 17, builder).
- **Step definitions are the scope's own.** A step file uses only the
  kernel's shared steps and its own context's step files; a helper it needs
  from elsewhere is copied in. A step text used by the features of more than
  one context is the kernel's; one used within one context is that
  context's. Its owner defines it once, when its step file loads, never only
  inside another step's body (cycle 03: four integration fixes; cycle 06:
  spec-0090 and spec-0187 found no definition).
- **A scenario's project is one the scenario makes,** a fixture, never the
  room or the repository the tests run in, whose files change from cut to
  cut and differ in verify's checkout. The ids, needs, pages and commits a
  scenario names are its fixture's (cycle 06: spec-0231; cycle 07:
  spec-0239).
- **A fixture copied from the room holds what a clean room holds:** the
  paths the configuration keeps, never the denied ones (cycle 13: the copy
  still read the old allow-list).
- **A fixture's git hooks never run.** A fixture made by `init` or `adopt`
  carries the skeleton's pre-commit hook, which runs whatever `rdm-spec` is
  on PATH; a scenario that commits in a fixture runs with the fixture's hooks
  turned off, unless it is about the hook (cycle 14: the grader's commit ran
  the installed release).
- **A fixture never carries the room's oracle** unless its scenario sets
  one: the oracle a fixture's configuration names is the scenario's (cycle
  09: 22 fixtures reached for the room's registry).
- **A Given builds on the project an earlier Given of its scenario made;**
  only the first Given makes one (cycle 11: a shared Given threw away the
  history spec-0236's first Given made).
- **A shared step's context keys are the kernel's.** The kernel's step file
  names, beside each shared step, the keys it sets; another step file reads
  only those (cycle 09: `:wiki-file` read as `:wiki-rel`).
- **A suite removes every fixture it makes,** unit tests included (cycle 06:
  the disk filled; cycle 07: 8,934 directories left behind).

### Your report (under 30 lines)

1. Per ticket: its scenarios passing, its first run's summary line (red, or
   green and why), from `redgreen.log`.
2. The full suite's counts at the end.
3. Changes outside your scopes.
4. Numbered decisions where the spec was silent, each with its scenario id
   (they become `dhf/cycles/NN/decisions.yaml`).
5. Your tool-call count, and any write outside your room, however small.

## The reviewer

You review one slice's build before it is graded; you change nothing. You may
read the build's code and tests, which a grader never does, because you judge
the build against the plan, not the product against its scenarios (loyly,
harness, section 3, adversarial review).

- **Read** the slice's `plan.md`, tickets, `decisions.md` and feature files,
  then the build's code for the components the slice reaches, and the
  builder's `redgreen.log` and report.
- **Hunt:** rules the code enforces that no scenario states (an invented
  rule); behaviour the plan puts out of scope; a decision the builder made
  that the plan's expected decisions settled otherwise; tests written after
  their code; names that drift between the Gherkin and the code.
- **Report,** each finding with the file and line, and which kind it is.
  Findings go to the spec owner, never back to the builder.

## The grader

Grade each checklist item against the running build, however you grade, with
the spec in one hand (loyly:README.md@17d68d2, "3. Measure"). You did not
build it and you never read its code: drive it through its commands only.

- **You get** `project/` (the cut's spec and configuration, no code),
  `bin/rdm-spec` (the build) and the checklist (`dhf/cycles/NN/checklist.json`).
- **For each item,** read its scenario (`grep -rn "@spec-NNNN" project/dhf/spec`),
  set up what its Given says in a scratch directory inside your room, run the
  When with `bin/rdm-spec`, and check every clause of the Then.
- **File a verdict per item,** pass or fail, with the evidence: the command,
  its exit code, what it printed. Unsure is a fail. Write each verdict to
  `out/verdicts.partial.edn` as you go; end with `out/verdicts.edn`, in
  checklist order.
- **Report** your counts, each fail's reason, and what the scenarios leave
  unstated.

The rules that keep a grading run clean (hooks off in fixtures, only your
room, no process left) are in `docs/wow/invariants.md` (INV-10, INV-12).
