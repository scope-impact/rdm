# The agent harness

As Löyly's `docs/wow/harness.md` (loyly@17d68d2): how to point an agent at
this repository, as a builder or as a grader. A brief names the role, the
cycle, the room and the scopes; everything else is here, so every cycle's
agents work the same way. Guidance, not spec: the spec says what rdm-spec
does, and a grader holds a build to nothing else. The rules here, with what
enforces each, are in `docs/wow/invariants.md`.

## The builder

You rebuild part, or all, of the implementation from a cut of the spec, in a
clean room fenced from it. Nobody checks your claim of done but the gate
(`rdm-spec verify`) and a grader who never sees your tests. 

### Read first, in this order

1. `dhf/decisions/adr-0002-spec-first-regeneration.md`
2. `dhf/spec/README.md` (the spec's conventions) and `dhf/spec/glossary.md`
3. `dhf/wiki/pages/loyly-method.md` (where the spec is silent)
4. `dhf/harness/stack.md`, `dhf/harness/config.edn`, the stack's guidance
   (for TypeScript, `docs/wow/functional-ddd-typescript.md`)
5. `dhf/spec/model.edn`, `dhf/spec/events.edn`, then the feature files of your
   scopes

### How you work

- **One builder, your scopes in the order the brief lists them** (the order
  `rdm-spec scopes` gives: the kernel first, then publishers before their
  subscribers).
- **Test-first, per scope:** write or change its step definitions first and
  see them fail (keep the failing run's summary line), then implement; unit
  tests before the code they test. Retroactive tests are forbidden.
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

1. Per scope: scenarios passing, and the first failing summary line you saw.
2. The full suite's counts at the end.
3. Changes outside your scopes.
4. Numbered decisions where the spec was silent, each with its scenario id
   (they become `dhf/cycles/NN/decisions.yaml`).
5. Your tool-call count, and any write outside your room, however small.

## The grader

You did not build what you grade, and you never read its code or its tests:
you grade it as a black box, through its commands only (spec owner, cycle
19). You judge,
item by item, whether the build does what each checklist scenario says. Your
verdicts are the score; the builder's tests never are (Löyly: "grade each item
against the running app, however you grade"; the instrument reports to a
person, never into the loop).

### How you grade

- **Your room holds no implementation.** It holds `project/` (the cut's
  spec, configuration and documents, no `src/` or `test/`), `bin/rdm-spec`
  (which runs the build, installed outside your room) and `out/`. Never read
  outside your room, the wrapper's target included.
- **The checklist** (`dhf/cycles/NN/checklist.json`) lists the items; find
  each scenario by its tag (`grep -rn "@spec-NNNN" project/dhf/spec`) and read
  it exactly.
- **For each item, make a fixture** (`mktemp -d -p <your room>`): a copy of
  `project/` or a project made with `bin/rdm-spec init`; any implementation a
  scenario needs (a test command, an app to serve) you write yourself, small. Commit when the scenario
  needs history. Remove `:oracle` from a copied configuration unless the
  scenario needs one.
- **Run the build through `bin/rdm-spec` only,** never an `rdm-spec` on PATH,
  and compare what happened with every clause of the Then.
- **A pass needs evidence:** the command you ran, its exit code and the key
  output. Outlines: every row. A clause that differs is a fail; if you cannot
  reach a verdict, fail, and say why.
- **Write verdicts as you go** to `out/verdicts.partial.edn` (one map per
  line), so an interruption loses nothing; at the end, `out/verdicts.edn`, one
  `{:id :verdict :evidence}` per item in checklist order.

### Rules

- **Black box only:** learn what the build does from its commands, its
  output and the files it writes, never from its code. Do not run the
  builder's suite.
- **In a fixture made by `init` or `adopt`,** turn hooks off
  (`git config core.hooksPath /dev/null`) before any commit. Set
  `GIT_CEILING_DIRECTORIES` when a fixture sits inside another repository.
- **Write only inside your room,** with `TMPDIR` inside it. Never `/tmp`, `/`,
  `~`, the repository or the artifact layouts. Stop every process you start,
  remove your fixtures, and leave only `out/`.

### Your report

Pass and fail counts, each fail with its reason, what the scenarios leave
unstated, and your tool-call count. The unstated things are the next spec
change.
