# Glossary

The harness's vocabulary. One name per thing, used at every layer and
re-cased per layer: "read model" in prose, `read-models` in the storm,
`read_model` in code. A term here, in `harness/spec.feature` and in a
product's storm must agree; a scenario that uses a word this glossary does
not hold, or a word it says to avoid, is a finding at review.

Each entry: the meaning; where the term appears (the file or key, the task
that reads it); what holds it (an invariant, a rule in the spec, or review);
and the words not to use for it. Cites `docs/wiki/pages/` for the why.

---

## The sources

**Source.** A file under `raw/`, immutable, cited as `<name>:<path>@<commit>`.
A source is never edited; a claim rests on one or is not finished.
_Where:_ `sources = [...]` on every storm element; every wiki footnote.
_Held by:_ `harness storm` (`element-sourced`, `source-is-raw`,
`source-exists`); review for the wiki. _Avoid:_ reference, link, origin.
→ [event-storm](pages/event-storm.md)

**Raw.** The folder `raw/`, and the allow-list `raw/README.md` that says
what entered it and what never does. _Avoid:_ input, corpus, background.

**Wiki.** `docs/wiki/`: the domain distilled from the sources, every claim
footnoted. Derived; where it disagrees with a source, the source wins.
_Held by:_ review (the minimum reference rule). _Avoid:_ documentation, notes.

**Firewall.** The rule that a product's spec and storm, the wiki and the
plans name no stack. _Held by:_ INV-2, `harness check` (`no-stack-words`).
_Avoid:_ abstraction, neutrality.

**Stack word.** A word of a technology the model declares (`technology` on
a container, component, deployment node, infrastructure node or
relationship), other than a common word. There is no hand-written list: the
firewall reads the model (decision 0003). _Where:_ the `stack-words` stage
of `check`; `harness/common-words.toml` for the words that never count.
_Avoid:_ banned word, blacklist.

## The storm

**Storm.** The event storm kept as data: `spec/storm.toml`, holding actors,
commands, events, policies, aggregates, read models and hotspots, each
element sourced. The front of the chain; no storm, no cut.
_Held by:_ INV-1, `harness storm`, `harness check` (stage `storm`,
`storm-exists`).
_Avoid:_ domain model, workshop, board. → [event-storm](pages/event-storm.md)

**Element.** Any entry of the storm, addressed as `[kind, name]`, for
example `["events", "SlotBooked"]`. _Where:_ `element` in a finding.

**Actor.** Who or what issues a command: a person or an external system, with
what it wants. _Where:_ `[actors.<name>]` with `text`.
_Avoid:_ user, role, persona.

**Command.** An intent to change something, issued by an actor to an
aggregate. _Where:_ `[commands.<Name>]` with `actor`, `aggregate`.
_Held by:_ `command-aggregate-named`. _Avoid:_ action, operation, request.

**Event.** A fact that happened, raised by one command, carrying named keys.
Events are the only way components of different contexts relate.
_Where:_ `[events.<Name>]` with `command`, `carries`; `event` on a
relationship. _Held by:_ `event-has-command`, `contexts-relate-by-event`,
`event-in-storm`, `event-carried`. _Avoid:_ message, signal, notification,
trigger.

**Carries.** The keys an event carries. A change to them reaches the
publisher and every subscriber (`scopes`). _Where:_ `carries = [...]`.
_Avoid:_ payload, schema, fields.

**Policy.** "When this event, then that command": a rule that reacts.
_Where:_ `[policies.<Name>]` with `when`, `then`. _Avoid:_ saga, handler,
automation, workflow.

**Aggregate.** What must be checked together, in one step, for its rules to
hold; the one place a rule is enforced. A component owns it.
_Where:_ `[aggregates.<Name>]`; `owns` on a component.
_Held by:_ `element-owned-once`. _Avoid:_ entity, record, table, object.

**Read model.** A view fed by events, owned by a component.
_Where:_ `[read-models.<Name>]` with `fed-by`. _Held by:_
`read-model-fed-by-event`. _Avoid:_ projection, query, report, view (that
word is kept for the architecture's drawings, if any).

**Hotspot.** A question the storm could not answer. Open until decided, and
a decided one carries its decision and a source. An open hotspot refuses a
cut. _Where:_ `[hotspots.<Id>]` with `text`, `status`, `decision`, `sources`.
_Held by:_ `hotspots-decided`, `decision-stated`. _Avoid:_ open question,
TODO, risk, issue.

## The model

**Model.** `spec/model.toml`: the software system, its containers, their
components, the relationships, and the deployment environments, in the C4
model's vocabulary by the Structurizr DSL's names (decision 0003), derived
from the storm and held to it. The harness adds three keys: `owns`, `spec`,
`event`. _Held by:_ `harness model` (`model-vocabulary`), `check` (stage
`model`). _Avoid:_ architecture (the word for the practice, not the file),
diagram, design, schema.

**Software system.** C4's top: what is being built, made of containers
(`raw/c4/c4model.com/abstractions.md`). _Where:_ `[softwareSystem]`.
_Avoid:_ product (the word for what a cycle regenerates), app.

**Container.** C4's container: an application or a data store, a runtime
boundary around code that runs or data that is stored; not Docker, and its
deployment a separate concern (`raw/c4/c4model.com/abstractions-container.md`).
**The ceiling of a scope**: a change that moves a component between
containers, and every Nth cycle, rebuilds the whole container.
_Where:_ `[containers.<name>]` with `description`, `technology`, `tags`.
_Held by:_ `container-has-components`. _Avoid:_ service, app, deployable,
module, pod.

**Component.** C4's component: related functionality behind an interface,
inside one container's process space, not separately deployable
(`raw/c4/c4model.com/abstractions-component.md`). **The unit of
regeneration**: sits in one container and one group, owns storm elements,
owns one feature file; rebuilt whole when its scenarios or the events it
carries change. Named `<container>/<name>`. A component may be reached by
several plans. _Where:_ `[containers.<container>.components.<name>]` with
`group`, `description`, `technology`, `owns`, `spec`. _Held by:_
`component-owns-storm-element`, `owned-element-in-storm`,
`feature-owned-by-component`, `component-has-scenarios`.
_Avoid:_ module, package, service, layer, slice.
→ [scope-and-container](pages/scope-and-container.md)

**Group.** C4's grouping, used for the bounded context: a part of the
domain with its own language. Components of different groups relate only
through events. _Where:_ `group` on a component. _Held by:_
`groups-relate-by-event`. _Avoid:_ context (in files; prose may say
"bounded context"), domain, area, boundary.

**Technology.** C4's attribute for what a container, component, deployment
node, infrastructure node or relationship is made of or runs on
(`raw/c4/structurizr/dsl-language.md`, "technology"). The one place the
stack is written; the source of the firewall's stack words. _Avoid:_ stack
(in files), implementation, platform.

**Deployment environment.** C4's named environment (production, staging,
development) holding deployment nodes (`raw/c4/c4model.com/diagrams-deployment.md`).
Where a monitor's results will be read against. _Where:_
`[deploymentEnvironment.<name>]`. _Avoid:_ stage, target, cluster.

**Deployment node.** C4's: where an instance of a container runs (physical,
virtual, containerised, an execution environment); nests. _Where:_
`deploymentNode.<name>` with `technology`, `instances`, `containerInstance`.
_Held by:_ `containerInstance-names-container`. _Avoid:_ host, server, box.

**Infrastructure node.** C4's: a load balancer, firewall, DNS service and
the like, in a deployment environment. _Where:_ `infrastructureNode.<name>`
with `technology`. _Avoid:_ middleware, service.

**Relationship.** C4's relationship, one component reaching another, with
the harness's `event`: the storm event it carries, required when the groups
differ. _Where:_ `[[relationships]]` with `from`, `to`, `description`,
`technology`, `event`. _Held by:_ `component-named`, `event-in-storm`.
_Avoid:_ dependency, call, edge, link.

**Publisher, subscriber.** For an event: the component owning the
aggregate of the event's command, and every component a relationship
carries it to. _Where:_ `harness model --events`; read by `scopes`.
_Held by:_ `element-owned`, `event-carried`. _Avoid:_ producer, consumer, emitter,
listener.

## The spec

**Spec.** A product's `spec/`: the storm, the model, the configuration and
one feature file per component. The asset; code is its build product.
_Avoid:_ requirements, docs, PRD.

**Feature file.** `spec/<component>.feature`: a component's scenarios, in
domain language, named by the component's `spec` key. _Avoid:_ test file,
suite.

**Scenario.** One behaviour, stated as Given, When, Then; the When runs the
product, the Then pins what it does. One checklist item each.
_Avoid:_ test, case, example (Gherkin's own `Scenario Outline` and
`Examples` keep their names).

**Rule.** Gherkin's `Rule:` grouping scenarios, and, separately, the name a
finding carries (`rule = "..."`). The two never share a name: a Gherkin rule
is a sentence; a finding's rule is a hyphenated key.

**Id, item.** `@item-NNN`: a scenario's stable id, handed out once by the
stamper, never re-derived from wording, never reused. An item is the
checklist's row for it. _Held by:_ `id-once`, `every-scenario-stamped`.
_Avoid:_ number, key, tag (the `@` line is a tag; the id is what it holds).

**Stamp.** `harness stamp`: give every untagged scenario the next free id.
Stamping twice changes nothing. _Avoid:_ number, label, annotate.

**Wip.** `@wip`: a scenario planned and not yet built; listed and marked on
the checklist, never dropped. _Avoid:_ pending, skip, todo.

**Deterministic.** The default for a scenario: its Then can be matched byte
for byte, by the oracle against a golden and by a grader against the
product. Not a tag; the absence of one. _Avoid:_ exact, stable, fixed.

**Nondeterministic.** `@nondeterministic`: a scenario whose outcome holds at
least one value the spec cannot pin (a hash, a duration, a uuid, a host, a
monitor's reading), each written as a placeholder. Matched by shape at the
placeholder and exactly everywhere else. The tag instructs the oracle and
the grader; the placeholder instructs the builder. _Where:_ the checklist's
`nondeterministic = true` and `placeholders`. _Held by:_ `placeholder-tagged`,
`nondeterministic-has-placeholder`, `placeholder-declared`.
_Avoid:_ flaky (that is a defect), random, unstable, approximate.

**Placeholder.** `<what it is>` in a Then: a value the spec cannot pin, named
by what it is, declared once in `spec/placeholders.toml` (the harness's own
in `harness/placeholders.toml`) as a pattern (`shape`) or a judge (`judge`). An Outline's `<column>` is not a
placeholder: its Examples pin it. _Held by:_ `placeholder-shape`,
`placeholder-declared`. _Avoid:_ variable, wildcard, regex (a shape is one;
the placeholder is the name).

**Judge.** A tool that answers a nondeterministic item a pattern cannot hold:
given the Then, the placeholder and the observed outcome, it writes a result
with a verdict and evidence. Declared as a plugin answering `item`; built
with DSPy (a signature; a metric that scores the judge against the owner's
past verdicts and returns the owner's evidence as feedback; GEPA as the
optimizer, which reflects on that feedback in words and keeps a Pareto
frontier of candidates). A non-text outcome (a page, a screen) reaches it
as the tool's attachment. Held still with goldens that are the owner's labelled verdicts;
checked at every `check`. Its verdict is a grader's, never the gate's.
_Where:_ `[[plugins]]` with `answers = "item"`; `judges` on a checklist
item. _Held by:_ `judge-declared`, `judge-goldens`; decision 0002.
_Avoid:_ LLM-as-judge (the method, not the name), evaluator, oracle (the
harness's own judge of its tools), classifier.

**Feedback.** What a judge learns from, beyond pass or fail: the owner's
evidence on a verdict, and where the judge disagreed, both reasonings.
GEPA's optimisation signal; the reason an owner's verdict always carries
evidence. _Avoid:_ reward, label (that is the verdict), score.

**Checklist.** Derived from the spec, one item per stamped scenario, grouped
by component; the spec is its only author. _Where:_ `cycles/NN/checklist.toml`.
_Held by:_ `harness checklist` (`every-scenario-stamped`, `spec-not-empty`);
INV-6. _Avoid:_ test plan, matrix, list.
→ [gate-and-instrument](pages/gate-and-instrument.md)

**Configuration.** `spec/config.toml`, with a closed shape: the paths, the
whole-container interval, verify's command and budget, the plugins. An
unknown or missing key is refused. _Held by:_ `config-shape`; INV-7.
_Avoid:_ settings, options, flags.

## The cut and the scope

**Cut.** A tag `spec-N` on a merged spec that `check` passed, with a clean
tree. A regeneration reads the cut and nothing else.
_Held by:_ `harness cut` (`tree-clean`, `tag-free`, and whatever `check`
refuses). _Avoid:_ release, version, snapshot, freeze.

**Scope.** The components a cycle rebuilds, computed from two cuts: the
changed, closed over their events to reached, up to their containers. Never
typed. _Where:_ the output of `harness scopes`; `changed`, `reached`,
`components`, `containers`, `whole-container`. _Held by:_ INV-4,
`scope-computed`, `cuts-differ`. _Avoid:_ selection, target, affected set.
→ [scope-and-container](pages/scope-and-container.md)

**Changed, reached.** The components whose own scenarios differ between the
cuts; the components the changed ones reach through events (publisher and
subscribers). Together they are the scope's `components`.

**Whole-container cycle.** A cycle that rebuilds every component of a
container: due every Nth cycle (`whole-container-every`), or when a component
moved between containers. _Where:_ `whole-container = true`;
`whole-container-due` on the scoreboard. _Avoid:_ full rebuild, big bang.

## The cycle

**Cycle.** One pass: cut, scope, fence, build, gate, grade, score,
scoreboard, accept. Numbered `NN`. _Where:_ `cycles/NN/`, its `cycle.toml`
naming `cut`, `from`, `builder`, `graders`, `spec-owner`.
_Avoid:_ sprint, iteration, run, round (a round is one builder transition).

**Cycle record.** `cycles/NN/cycle.toml`. It holds who, and the cuts; it
never holds a scope. _Held by:_ `scope-computed`, `one-builder`,
`spec-owner-human`, `grader-not-builder`.

**Plan.** `docs/plan/NN-slug/plan.md`: **the unit of work**. It is planned,
specified, built, verified and closed, and its decisions are its own. Holds
the goal, the wiki pages it rests on, the scenarios it delivers, its tickets,
its spec gaps. A plan may reach several components; it never names a scope.
No plan, no code. _Held by:_ review. _Avoid:_ epic, feature, story, slice.

**Ticket.** One coherent unit of a plan, naming the scenarios it satisfies.
_Avoid:_ task (that word is a harness command), issue, story.

**Spec gap.** A place the spec is silent where a grader could see the answer.
Visible: stated before the build. Free: the builder's choice, named.
_Held by:_ the planner's brief; the owner accepts the list.
_Avoid:_ ambiguity, assumption, TBD.

**Decision.** A chosen branch with the pruned one named: a builder's, in the
room's `decisions.toml`; a plan's divergence, in its `decisions.md`; the
harness's own, in `docs/decisions/NNNN-*.md`. _Avoid:_ note, rationale,
ADR (as a word; the files are decisions).

## The room

**Room.** The clean room: `cycles/NN/room/`, a clone of the cut with the
scope's implementation and every measurement removed from tree and history.
_Held by:_ `harness fence`; `room-fresh`, `room-proved`.
_Avoid:_ sandbox, workspace, checkout, worktree.
→ [clean-room](pages/clean-room.md)

**Fence.** The task that builds the room and proves it; also the proof
itself (`--prove`). _Where:_ `removed`, `kept`, `tree` in its output.
_Avoid:_ isolation, quarantine, jail.

**Kept, removed.** What the room holds of the implementation (components
outside the scope) and what it does not (the scope's, and `cycles/`).

**Tree.** A git tree hash: the room's content at a moment. `verify` records
it; `score` refuses a room whose tree moved since. _Held by:_
`verified-tree`. _Avoid:_ hash, commit (a commit has a tree; the tree is
what is compared), snapshot.

**Builder.** The one actor that writes implementation in the room, test-first,
from the cut alone. One per cycle. _Held by:_ `one-builder`; INV-10 for
who may grade. _Avoid:_ developer, agent (an agent may be a builder; a
builder may be a person), generator.

**Round.** One RED or GREEN transition of the builder, logged in
`redgreen.log`; every Nth is a review round (`REVIEW`), in which the
builder reads its own diff and builds nothing. _Avoid:_ cycle, iteration.

**Probe.** The plugin answering `verify` that asks the product to show
domain behaviour only this product has, so the gate knows the thing
answering is this build and not an orphan or a stand-in. A name is not
evidence. _Held by:_ `is-this-app`, `port-free`. _Avoid:_ health check,
ping, smoke test.

## The gate

**Gate.** `harness verify`: the script the builder does not control that
ratifies a build. _Held by:_ INV-5. _Avoid:_ CI, pipeline, check (that is a
different task), test.
→ [gate-and-instrument](pages/gate-and-instrument.md)

**Verify.** The gate's task: the suite twice, inside the budget, plugins
answering `verify`; writes `verified.toml`. _Avoid:_ validate, ratify (the
old name; kept only in the wiki's history), test.

**Ratified.** The state a build is in once `verify` passed. "Done" is
ratified, never claimed. _Avoid:_ done (as a state), approved, accepted
(that is the owner's word, below).

**Budget, margin.** The seconds a suite run may take, and how many were
left. _Where:_ `budget-seconds`, `margin-seconds`. _Held by:_
`inside-budget`. _Avoid:_ timeout, limit, deadline.

**Suite.** The product's own tests, run by the command the configuration
names; it must write at least one result. _Held by:_ `results-written`,
`suite-green-twice`. _Avoid:_ tests (plural, loose), test run.

**Result.** One scenario run, written by whatever tool ran it in Allure's
result model, one file per run: the item it verifies and the tool that wrote
it (labels), its status, its start and stop, its attachments. The only thing
the gate reads, and the one contract from dev to monitor: a result from the
room, from staging and from production carry the same item id and are read
by one reader. _Where:_ the configuration's `[verify] results` folder;
copied, attachments included, to `cycles/NN/results/run-N/`. _Held by:_ INV-11, `result-labelled`, `result-tool-named`,
`result-item-on-checklist`, `every-item-has-result`.
_Avoid:_ report, output, log, JUnit, exit code.

**Status.** A result's outcome, one of Allure's five: `passed`, `failed`
(an assertion did not hold), `broken` (the test could not run to its
assertion), `skipped`, `unknown` (`raw/allure/docs/test-statuses.md`). Only
`passed` verifies an item; the gate treats the other four alike.
_Where:_ `status` on a result and on each of its steps. _Avoid:_ state,
outcome, green/red (prose only).

**Label.** A name-value pair on a result, repeatable by name
(`raw/allure/docs/how-it-works-test-result-file.md`, "labels"). The harness
reads two: `item`, the scenario's id, and `tool`, what wrote the result; a
tool's own labels (`framework`, `language`, `host`, `tag`) travel with it
and are not read. _Avoid:_ tag (Gherkin's word), annotation.

**Attachment.** A file a tool kept with a result, stored beside it as
`{uuid}-attachment.{ext}` and named on the result by `name`, `source` (the
file) and `type` (its media type)
(`raw/allure/docs/how-it-works-test-result-file.md`, "attachments"): a
browser trace, a screenshot, a request log, a transcript. Evidence the
grader and the owner read with the result; copied into the cycle with it.
_Avoid:_ artefact, asset, upload.

**Step.** One named part of a result, with its own status, timing,
attachments and sub-steps (`raw/allure/docs/how-it-works-test-result-file.md`,
"steps"). The natural place for a scenario's Given, When and Then. The gate
reads a result's status, never its steps. _Avoid:_ action, stage (Allure's
word for a lifecycle state).

**Result file.** `{uuid}-result.json` in the results folder, one per run of
one scenario (`raw/allure/docs/how-it-works-test-result-file.md`). A
container file (`{uuid}-container.json`) describes fixtures around results
and is not read by the gate.

**Tool.** Whatever answered a scenario and wrote its result: a Gherkin
runner, a unit framework, a browser driver, a metrics query, a telemetry
trace, a synthetic probe, a person's session. Named on the result by the
`tool` label. The gate never reads the tool, only its results.
_Avoid:_ runner (as the general word), framework, integration.

**Monitor.** A tool that answers a scenario against the running product
rather than the room: a metrics query, a telemetry trace, a probe. Its
results carry the same item ids and are read by the same reader; they are
evidence about a deployment, never about a cut, so the gate never reads
them. _Avoid:_ observability (as the name), alert, dashboard, SLO.

**Span.** One operation in a telemetry trace. A span that shows a command
reaching its aggregate and raising its event is a monitor's answer to the
scenario that states it; the storm names the event the span must show.
_Avoid:_ log line, event (the storm's word).

## The instrument

**Instrument.** What measures and reports to a person, never into the loop:
checklist, score, scoreboard. _Avoid:_ harness (the word for what feeds the
loop), metrics, dashboard.
→ [gate-and-instrument](pages/gate-and-instrument.md)

**Grader.** An actor that drives the built product black box and gives one
verdict per item with evidence. Never the builder. Several are scored apart
and agreed by majority. _Where:_ `cycles/NN/verdicts-<name>.toml`;
`[[graders]]`. _Held by:_ `grader-not-builder`. _Avoid:_ tester, judge,
reviewer (a different role), QA.

**Verdict.** `pass` or `fail` for one item, with evidence. Nothing else is a
verdict. _Held by:_ `verdict-pass-or-fail`, `id-on-checklist`.
_Avoid:_ result, outcome, status, partial.

**Evidence.** What the grader did and saw. Empty is allowed and is recorded
as empty. _Avoid:_ notes, comment, proof.

**Unreached.** An item no verdict was given for; filed as `fail` with the
reason. Never dropped. _Avoid:_ skipped, untested, n/a.

**Score.** `harness score`: verdicts filed against the checklist, writing
`score.toml`; refused for a cycle `verify` did not ratify, and for zero
verdicts. _Held by:_ `graded`, `verified`. _Avoid:_ grade (the grader's
act), result, pass rate (that is the scoreboard's number).

**Scoreboard.** `harness scoreboard`: the pass rate, cycle over cycle; both
directions on the shared items, never a net; the moved denominator said
aloud; new and dropped items counted apart. _Where:_ `rate`, `previous`,
`shared`, `new`, `dropped`, `denominator-moved`. _Held by:_ `scored`.
_Avoid:_ trend, delta,
net, improvement.

**Shared, new, dropped.** Items on both cycles' checklists; items only on
this cycle's; items only on the previous. _Avoid:_ common, added, removed.

**Better, worse.** Shared items that went fail to pass, and pass to fail.
Both are always shown. _Avoid:_ fixed, regressed, net change.

**Reviewer.** The actor that reads the room's diff against the plan and the
spec, hunting invented rules and spec drift; never grades, never fixes.
_Where:_ `cycles/NN/review.md`. _Avoid:_ grader, auditor, approver.

**Integrator.** Who runs the cycle's machinery and files its records, and
makes none of the owner's decisions. _Avoid:_ operator, maintainer, lead.

**Owner.** The spec owner: the person whose decisions a cycle waits on
(accepting a plan, a spec gap, a cycle). Always a person.
_Where:_ `[spec-owner]` with `actor = "human"`. _Held by:_ INV-10,
`spec-owner-human`. _Avoid:_ product owner, PO, stakeholder, user.

**Accepted.** The owner's word for a cycle brought in on the evidence.
_Avoid:_ merged, approved, done, shipped.

## The harness itself

**Harness.** This repository's binary and spec: what feeds the loop (fence,
gate) and what measures (instrument), held still. Also, in Löyly's sense,
the agent harness: how an agent is pointed at a repository
(`docs/wow/harness.md`). _Avoid:_ framework, platform, tool (as the name),
rdm, rdm-spec.

**Task.** One command of the harness: `storm`, `model`, `stamp`,
`checklist`, `check`, `cut`, `scopes`, `fence`, `verify`, `score`,
`scoreboard`. Each prints one TOML document and exits 0, 1 or 2.
_Avoid:_ subcommand, verb, action.

**Passed, refused, cannot-run.** A task's three answers: 0, the project is as
the rule says; 1, a finding about the project; 2, the harness could not run
(its own defect or a missing input). A refusal is never silent, and a task
that cannot run is never a pass. _Avoid:_ ok, error, failure (a scenario
fails; a task refuses), warning.

**Finding.** What a refusal names: a `rule` and what it points at.
_Where:_ `[finding]`. _Avoid:_ error, violation, issue, problem.

**Stage.** The judge within `check` that refused: `config`, `storm`,
`model`, `stack-words`, `spec`, `harness-files`, `plugins`.
_Avoid:_ step, phase (a phase is plan or build, in the agent harness).

**Check.** `harness check`: runs every judge in order, refuses on the first
red, installs itself as the pre-commit hook. Its silence is never a pass.
_Held by:_ INV-9. _Avoid:_ lint, validate, gate (that is `verify`).

**Plugin.** A judge a project keeps for itself: a command declared in the
configuration, answering `check` or `verify`, speaking the same TOML and exit
codes. The one way the harness is pluggable. _Where:_ `[[plugins]]` with
`name`, `answers`, `command`. _Held by:_ `plugin-answers`.
_Avoid:_ extension, hook (that is git's word), add-on, subscriber.

**Opinion.** A rule the harness refuses rather than configures: no storm no
cut, no stack words, contexts relate by events, one builder, the
whole-container cycle. An opinion is never a flag. _Held by:_ INV-7,
`config-shape`. _Avoid:_ default, setting, preference, convention.

**Held still.** Not regenerated, changed only by a person in a planning
round against the harness's own spec, and judged by its oracle. The harness,
its stack-word list, its goldens. _Held by:_ INV-3. _Avoid:_ frozen, stable,
locked.

**Oracle.** The harness's own judge: its tools run against recorded
goldens, byte for byte. _Avoid:_ reference implementation, fixture, test
suite.

**Golden.** A recorded output of a harness tool the oracle holds it to. Moves
for three reasons only, each named: the behaviour changed (and a scenario
states it), the wording changed, the spec grew (additions only). Never
re-recorded to get a green. _Avoid:_ snapshot, baseline, expected output.

**Shared, per-project, local (files).** The three lists in
`harness/files.toml` every file under `harness/` must be in: one text across
every repository carrying this harness; must exist, content its own; this
repository's alone. _Held by:_ INV-8, `file-declared`, `file-present`,
`shared-identical`. _Avoid:_ common, vendored, custom.

**Sibling.** Another repository carrying this harness, named in
`harness/files.toml`, whose shared files must be one text with this one's.
A sibling not on this machine is a loud skip, never a pass.
