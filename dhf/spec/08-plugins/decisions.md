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

