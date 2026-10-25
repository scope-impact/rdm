# Glossary of the spec

The vocabulary of `dhf/decisions/adr-0002-spec-first-regeneration.md`. It
replaces `CONTEXT.md` at cutover; until then `CONTEXT.md` describes the current
record and this file describes the spec beside it. When a term here is resolved
or changes, this file changes in the same commit.

## The spec

**rdm-spec**: the product this spec describes: RDM rebuilt around this
decision. The suffix says what changed: the spec is the source and the code is
built from it. Until cutover today's implementation and the `rdm` command keep
their name; `RDM` alone means today's implementation.

**Spec**: the source of truth for what the product does: the C4 model, the
event catalogue, the user needs, and the feature files the model's components
own. Everything under `dhf/spec/`; it never names its stack, which is in
`dhf/harness/stack.md`. The code is built from it, never the other
way round.

**Scenario**: one requirement, written in Gherkin and accepted as a whole. Its
steps are its verification steps, not separate requirements.
_Avoid_: design input, acceptance criterion, test case.

**Scenario id**: the `@spec-NNNN` tag a scenario carries, handed out once by
the stamper and never reused. It says nothing about the scenario's wording or
location.

**Ledger**: `dhf/spec/ids.edn`, the highest id ever issued. Only the stamper
writes it.

**Rule**: a heading in a feature file grouping scenarios. It carries no id
and is not itself a requirement.

**Level**: how a scenario is verified, exactly one per scenario: `@e2e`
(through the command) or `@contract` (a file format or an interface others
build on). There are no others; a checklist item that is a clause, not a
scenario, has the level `clause` (Clause item).

**User need**: what a user requires, independent of any solution. Declared
in `dhf/spec/needs.edn`; every scenario traces to at least one.

**Planned**: a scenario tagged `@wip`. It is not run, it is planned whatever
any result says, the spec check accepts it and the release check refuses it.
There is no planning tool besides this.
_Avoid_: task, ticket, backlog item.

**Raw source**: what the spec is distilled from, never edited: for rdm-spec,
three repositories (`innolitics/rdm`, `scope-impact/agent-skills` and
`skallinen/loyly`), each recorded in `dhf/raw/` with the commit it is pinned
to. Cited as `<source>:<path>@<commit>`. Where a source and the spec disagree,
the source wins and the spec is the bug. A source's implementation is fenced:
never cited, and absent from the clean room.

**Domain wiki**: the domain distilled from the raw sources, `dhf/wiki/`: every
claim footnoted to a source, every page in the index, no implementation named.
The spec rests on it.
_Avoid_: documentation, notes.

## The design

**Event storm**: the design step that turns the spec's needs into domain
events, commands, actors, policies and aggregates, from which the bounded
contexts and components follow. Its board is a working product, not part of the spec.

**Bounded context**: a part of the domain with its own language and model,
and a composable module of the rdm-spec container: it can be regenerated alone
while the others stand. Drawn as a boundary around its components.

**Container**: something that runs or stores data (C4 level 2). rdm-spec has
one, the `rdm-spec` command (a modular monolith), beside the repository.

**Component**: a logical building block inside one bounded context (C4 level
3). Each owns one feature file, `dhf/spec/<context>/<component>.feature`, and
may have design prose beside it as `<component>.md`.

**Event**: a domain event that passes between bounded contexts within one run,
declared in `dhf/spec/events.edn`. Steps that run at different times meet
through files instead.

**Event bus**: the in-process bus every event travels on, synchronous and in
subscription order. It refuses an event the catalogue does not declare.

**Skeleton**: what `rdm-spec init` writes and `rdm-spec adopt` adds: the spec,
the harness configuration, the raw and wiki directories, decisions, the
pre-commit hook, the cut workflow and `.gitignore`, as listed by
`dhf/spec/project/init.feature`.

**Kernel**: shared code every bounded context may use and that belongs to none
of them, such as the event bus. Specified under `dhf/spec/kernel/`, never drawn as
a container.

**Model**: `dhf/spec/model.edn`, the one C4 source. Every view is generated
from it.

**Retired source**: a raw source kept in `dhf/raw/sources.edn` with its pins
and `:retired` and the reason, because a pin is never dropped; nothing may
cite it.

**Model hash**: the SHA-256, as lowercase hex, of the model read as EDN and
printed back with every map's entries and every set's elements sorted by the
printed form of the key or element, each printed as EDN prints it with no
namespace map syntax, so reformatting the file keeps it. A spec change that
changes this definition is cut twice: before, and after a build that
implements it has redrawn the views.
A view records it on its first line, in a comment of the view's format:
`model-sha256: <hex>`. A view is fresh when it records the current one.

**View**: a generated diagram of the model at one level: context, container,
component or dynamic. Never drawn by hand.

**Role**: who acts on the spec: engineer, reviewer, builder, grader or spec
owner.
Every role can be filled by a human or an agent, and the machinery treats both
the same. Two rules hold whoever fills them: a reviewer is not the author of
what is reviewed, and the builder of a cycle neither grades it nor reads its
score.
_Avoid_: user (for a role), person (when either is meant).

**Builder**: the role that regenerates the build from a cut, inside a clean
room. A human or an agent.
_Avoid_: coding agent (as the role's name).

**Spec owner**: the role that reads the scoreboard and decides what the spec
must learn next. Never the builder of the cycle it reads.

## The harness

**Harness**: the bounded contexts that check the spec, ratify a build and score
it.
In rdm-spec the harness is the product, so it is specified like every other
component, under `dhf/spec/`; only its configuration (`dhf/harness/config.edn`)
and its stack (`dhf/harness/stack.md`) live apart.

**Step definition**: the code that executes a scenario's steps, for a project
that runs its scenarios with `rdm-spec run`. For rdm-spec itself they are the
builder's tests: implementation, rebuilt with the code, never scored.

**Oracle**: the Allure results of a run. A scenario is verified when a result
labelled with its id passed, and no result labelled with its id did not pass
(failed, broken, skipped or unknown).

**Allure result**: one `<uuid>-result.json` file in a results directory: a
JSON object with `name`, `status` (`passed`, `failed`, `broken` or `skipped`),
`steps` (each with `name` and `status`), `statusDetails.message` when it did
not pass, and `labels`: `spec` (the scenario id, exactly one), `component`,
`context` (`kernel` for kernel scenarios), and `planned` = `true` for a
scenario tagged `@wip`. A row of an outline is one result, its `name` ending
with the row as `key=value` pairs and its `parameters` holding them. A
result with no `spec` label verifies nothing.

**Grader**: the role that grades each checklist item against a ratified build,
however they grade. A human or an agent, never the builder of what is graded.

**Verdict**: a grader's pass or fail for one checklist item, with the evidence. Verdicts are
the score; the builder's tests never are.

**Verified / failed / unverified / planned**: a scenario's status, derived
from the oracle: a passing result and none that did not pass; a result that did
not pass; no result at all; tagged `@wip`, whatever the results. Status never
enters a score: only a grader's verdicts do.

**Cut**: a merged spec change tagged `spec-N`. Regeneration reads a cut and
nothing else.

**Fence**: the step that builds a clean room from a cut: one fresh commit
holding only the allowed paths (`:clean-room :allow`) and whatever
implementation its scope keeps.

**Scope**: what a regeneration rebuilds: the whole project, one bounded context
or one component, or a list of contexts and components. The kernel counts as
a context named `kernel`. The rest of the implementation stays in the clean
room as it was at the cut. A checklist names an item's context and component by
the model's `:name`. A cycle's checklist holds the items of its scope,
and nothing else: a scoped cycle is graded on its scope only.

**Regeneration**: building the implementation again from a cut, inside the
fence.

**Spec check**: the rules `rdm-spec check` holds a spec to: one id and one
level per scenario, only known tags, needs resting on resolving sources,
planned work marked and no stack word. `rdm-spec check` also runs the model's
lint, the views' freshness and the wiki check.

**Ratify**: `rdm-spec ratify ROOM CYCLE`: the room's implementation, in place
of the implementation of a fresh checkout of the cut, passes the builder's
test command, which writes results naming every scenario. Run by the installed
rdm-spec; it gates and never scores.
_Avoid_: gate, on its own (say spec check or ratify).

**Cycle**: one regeneration and its score: `dhf/cycles/NN/cycle.edn`, written
by hand (cut, scope, builder, grader, spec owner, and `:bootstrap` for the very
first), with `results/`, `build/` and `ratified.edn` (ratify), `checklist.json`
and `score.edn` beside it, and `test.log`, the test command's output
(ratify). The checkout is an export of the cut: its files, and no history.

**Oracle (instrument)**: `rdm-spec oracle`: the checklist, the scorer and the
scoreboard run on recorded fixture cycles and compared with their recorded
goldens. The oracle is one file, `oracle.edn`, in one artifact kept outside
the repository and named by the configuration's `:oracle {:repository R
:digest D}`; it is read by its digest only, from the registry repository R, or
from the local artifact layout the environment variable `ORACLE_LAYOUT` names
when set. `oracle.edn` holds `:project` (the one fixture project, each file's
path and text, committed in a scratch repository and tagged with every cut
the cycles name), `:cycles` (`{"NN" {:cycle :ratified :verdicts}}`), one key
per instrument component with its goldens (`:checklist {"NN" ...}`,
`:scorer {"NN" ...}`, `:scoreboard {"NN-MM" ...}`, compared as data) and
`:recorded`. No `:oracle` in the configuration is no oracle, said on
standard output only, so the gates stay quiet; one holding no
fixture cycle is a finding, since it judged nothing; one that cannot be read
cannot run (exit 2). It is the gate before fence and ratify: the command runs
it first and stops the task when it has findings. A golden is re-recorded
only with a stated reason: a new artifact, its entry in `:recorded`, and the
configuration's new digest, written by `record` and committed by a person,
reviewed like any spec change. It
judges the instrument; the Allure results remain the oracle of a project's
scenarios.

**Gate**: a stage the task table runs first and that stops the task when it
has findings that are not warnings; the stages after it never see it. Every other stage reports, so
one run names every finding. A gate whose code is not built refuses the task
(exit 2), as Löyly's gate refuses an instrument nobody checked
(loyly:harness/README.md@17d68d2, "What makes the checks run"). Publish runs
the views' freshness check as a gate before the site.

**Scoreboard**: the comparison of two cycles over the items both scored,
counting what got better and what got worse, never a net.

**Gherkin runner**: the kernel code that runs scenarios against their step
definitions and writes Allure results labelled with each scenario's id.

**Cutover**: the moment the regenerated core, passing the whole spec
at a cut, takes over this repository from the current record.

## The regulated profile

**Regulated profile**: what a project gets by declaring `:regulated` in its
configuration, `{:clause-lists ["dhf/clauses/std.txt" ...] :documents
"dhf/documents"}`: the clause lists it is held to and where its controlled
documents live. Without `:regulated` nothing of the profile runs. rdm-spec
ships no clause list and no document: the project brings both, for whatever
standard (dhf/wiki/pages/regulated-profile.md).

**Clause list**: a standard reduced to its clauses, one file of lines: `KEY
description`, `include FILE` (relative to the including file), a comment
starting `#`, or blank. Any other line is a finding, and so is an include
that leads back to a list already being read. A key is one run of letters, digits and `. _ : -` holding at least one
digit or colon, so a line of prose is a malformed line, never a clause.
The non-functional spec: what scenarios cannot run. Not the cycle's
checklist, which only grades.

**Clause**: one key of a clause list with its description, a mnemonic for the
clause, never a substitute for reading the standard. A key listed twice, in
one list or through includes, is one clause.

**Audit tag**: `[[ ... ]]` in a controlled document, outside code (a fenced
block, an indented block of four spaces or a tab that is not a list item's
continuation, or an inline code span),
never running past a blank line. It names a clause when its text holds the key, or
a dotted descendant of it, so every ancestor of the key it holds
(`STD:5.1.a` names `STD:5.1` and `STD:5`), bounded by
characters that are not key characters, a `: note` after the key allowed. A
key outside an audit tag names nothing, and neither does a tag in a
document's frontmatter: a clause is met in a document's body.

**Controlled document**: a Markdown file under the documents directory with
frontmatter `id`, `revision` and `title`. Its id is unique among them.

**Gap**: a clause no audit tag names. Gap analysis is a stage of `rdm-spec
check`, and every finding of it (a gap, a document with no id or a duplicate
id; warnings aside) blocks the release check, exit 1.

**Audit copy**: what `rdm-spec audit OUT` writes, only for a record the
release check passes and only of one commit: while any file it is made from
(the configuration, the spec, the clause lists, the documents) differs from HEAD, untracked
included, it refuses (exit 1, naming each). It writes the auditor's copy (`OUT/auditor/`, every audit tag
kept) and the engineer's copy (`OUT/engineer/`, every audit tag removed with
the space before it, in the frontmatter too, its line breaks kept so each
line keeps its number; the text after a tag that ends a line is left as it
was, a leading space included) of every controlled document, each also as a PDF; the
clauses page (`OUT/clauses.md`, one section per declared clause list, included clauses in
place); the traceability page
(`OUT/traceability.md`); the results (`OUT/results/`); and `OUT/manifest.edn`,
every other file with its SHA-256. The same commit and results give the
same bytes, PDFs included.

**Clause item**: a clause as an item of the cycle's checklist, at the level
`clause`, in a cycle scoped to the whole project or naming the context
compliance. Its grader reads the sections whose audit tags name it and passes it
only if they meet the clause. Named is not met.
