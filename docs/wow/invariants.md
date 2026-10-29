# Invariants

Rules that must hold, and what enforces each one. A rule with no named
enforcement point is a wish (Löyly, `raw/loyly/docs/wow/invariants.md`).

Each entry: the rule, the enforcement point, and what breaks without it.
Where the enforcement point is a tool that does not exist yet, it is written
as the tool's name and the rule stays a wish until the tool runs; the list of
such rules is the harness's backlog.

---

## INV-1: No storm, no cut

**Rule.** A project's model is derived from an event storm kept as data
(`storm.edn`: actors, commands, events, policies, aggregates, read models,
hotspots), each element citing a source. A cut is refused while the storm is
missing, while any element is unsourced, or while a hotspot is open.

**Enforced by.** `check` (refuses the project, exit 1, naming the first
offender) and `cut` (refuses to tag). The pre-commit hook runs `check`.

**Without it.** The architecture is drawn, not found. Components appear
because a builder needed a place to put code, and the spec describes one
codebase.

## INV-2: The spec never names the stack

**Rule.** A product's spec and storm, and `docs/wiki/` and `docs/plan/`,
hold no language, framework, database, hosting or toolkit names. Two things
are exempt: `raw/`, which is source, not spec; and the harness's own custody
(`docs/wow/way-of-working.md` section 9, `docs/decisions/`,
`harness/files.toml`), because the firewall exists to keep regeneration
honest and the harness is never regenerated (INV-3). `harness/spec.feature`
is not exempt: it states what the tools do, never what they are made of.

**Enforced by.** `check`, reading the technologies the model declares
(decision 0003) over the spec; review for `docs/`. The model's `technology`
keys are the one place the stack is written, and are exempt.

**Without it.** Regeneration stops measuring anything: the spec already
holds the answers, and a second implementation cannot be built honestly.

## INV-3: The harness is held still

**Rule.** The harness is written by hand, in one language, and is never the
thing regenerated. It is judged by its own `harness/spec.feature` and an
oracle whose goldens hold nothing that spec does not state. A golden changes
only with a stated reason naming every golden it changes.

**Enforced by.** The harness's own tests and oracle in the pre-commit hook and
in CI; the record of every golden change in `docs/decisions/`. The
experiment is always a product under `raw/` or a project of its own.

**Without it.** Every change to the judge is a crisis: rdm-spec re-recorded
goldens, rolled back an install, and accepted a build on evidence because
the spec changed the judge's own contract
(`raw/rdm/retrospective-01-12.md`, section 3; rdm-spec cycles 19, 21, 23, 25).

## INV-4: A scope is computed, never typed

**Rule.** The components a cycle rebuilds are read from the cut: the
components whose scenarios changed, closed over the events they publish and
consume, up to the container. Nobody adds a component by hand. A
whole-container cycle runs every Nth cycle.

**Enforced by.** `scopes` is the only input `fence` accepts; `cycle.edn`
holds no component list, only the cut and the result of `scopes`. The
scoreboard flags a cycle count that skipped the whole-container cycle.

**Without it.** A scope picked by hand misses what a rule reaches (rdm-spec
cycles 04 and 23), and a scoped cycle hides code nobody rebuilt for six
cycles (rdm-spec cycles 05 to 10).

## INV-5: "Done" is ratified, never claimed

**Rule.** A build counts when `verify`, a script the builder does not
control, boots the product in the clean room and proves its suite green,
twice, inside a named budget. The builder's own claim is not a state.

**Enforced by.** `verify` reads the suite's results (INV-11), never the
runner's exit code, and writes `verified.toml` with the room's tree hash;
`score` refuses a cycle with no such record.

**Without it.** "Green" is whatever the builder said.

## INV-11: The gate reads results, and results are tool-neutral

**Rule.** Every judge's output, the product's suite and the harness's own
oracle alike, is written as results in Allure's result model: one result per
scenario run, labelled with the item it verifies and the tool that wrote it,
with its status, its timing and the tool's attachments (traces, screenshots,
logs). The gate reads results only. A tool is anything that answers a
scenario and writes a result: a test runner, a browser driver, a metrics
query, a telemetry trace, a probe, a person. Which tool wrote a result is
recorded and never required, so a product may test in any stack, a scenario
may be answered in the room and in production by the same id, and a cycle's
evidence is read with one reader.

**Enforced by.** `verify` refuses a run that wrote no result
(`results-written`), a result naming no item (`result-labelled`) or an item
off the checklist (`result-item-on-checklist`), and an item with no result
(`every-item-has-result`); the results and attachments are copied into
`cycles/NN/results/run-N/`.

**Without it.** A gate once reported success because a missing config turned
the test command into a no-op that exits 0 (`raw/loyly/harness/README.md`,
"The failure modes"); an exit code says a process ended, not that anything
was verified. And every runner's own format is one more reader to hold
still.

## INV-6: The instrument never overstates

**Rule.** One checklist item per stamped scenario, derived from the spec and
never written. An item nobody reached is a fail. Zero verdicts is "not
graded", never 100 percent. The scoreboard shows both directions on the
shared items, never a net, and says aloud when the denominator moved.

**Enforced by.** `checklist`, `score` and `scoreboard`, each pinned by
`harness/spec.feature` and its goldens.

**Without it.** A gap quietly shrinks the denominator, and the pass rate
rises while the product gets worse.

## INV-7: An opinion is a refusal, not a flag

**Rule.** The harness's opinions (INV-1, INV-2, events-only between contexts,
one builder, the whole-container cycle) are refusals. No configuration key
turns one off. A project that needs one relaxed adds and owns a plugin that
answers `check`, and the plugin's existence is visible in the configuration.

**Enforced by.** The configuration's shape: `check` refuses an unknown key.
Review refuses a scenario in `harness/spec.feature` that reads "unless the
configuration says".

**Without it.** The opinions erode one reasonable key at a time, as
rdm-spec's did (read-by made optional, the domain rules moved out, contexts
allowed to relate by call), and nothing records that the intent moved.

## INV-8: Every held-still file is declared

**Rule.** Every file under `harness/` is in one of three lists: shared (one
text across every project that carries this harness), per-project (must
exist, content its own), or local. A file in no list is refused.

**Enforced by.** `check`.

**Without it.** A file nobody declared drifts for a year and nothing says so
(Löyly, 2026-09-08). Everything rdm-spec lost at a transition (goldens at
an install, the oracle at a layout change) was a held-still thing in no list.

## INV-9: Check runs on every commit, from the first

**Rule.** The pre-commit hook and CI run the harness's own gate (format,
lint, tests, oracle). There is no period in which the harness is verified by
hand.

**Enforced by.** `.githooks/pre-commit` (installed by `check --install`) and
`.github/workflows/check.yml`.

**Without it.** rdm-spec's branch carried 234 commits with no independent
gate after its cutover removed the hooks and CI.

## INV-10: The human steps cannot be skipped by the integrator

**Rule.** A verdict, a spec-gap decision and the acceptance of a cycle are a
person's. A cycle record names who decided each; an agent cannot fill the
spec owner's field.

**Enforced by.** `score` refuses a `cycle.edn` whose spec owner is an agent;
review of `docs/decisions/`.

**Without it.** The agent accepts its own plans "as recommended under the
goal", and decisions that were the owner's are made for them (rdm-spec,
slices 03, 04, 06 and 07).
