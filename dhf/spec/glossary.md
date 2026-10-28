# Glossary of the spec

The vocabulary of `dhf/decisions/adr-0002-spec-first-regeneration.md`, and
of this repository since the cutover (2026-10-26). When a term here is resolved
or changes, this file changes in the same commit.

## The spec

**rdm-spec**: the product this spec describes: a harness that any project
uses to keep its spec, regenerate its implementation from it in a clean room,
verify, grade and measure the result, and, under the regulated profile, audit
it. It is RDM rebuilt around this decision: the suffix says the spec is the
source and the code is built from it. `RDM` alone means the old record, before
the cutover.

**Project**: a repository rdm-spec is used on, set up by `init` or `adopt`,
or one folder of a larger repository (a monorepo) that holds its own
`dhf/harness/config.edn`. This repository is one of them as well: rdm-spec regenerates
itself from this spec (see Release). Its spec, model and event catalogue
describe the project, never the harness that reads them. rdm-spec runs in a
project's root, the folder that holds its configuration; run in any other
directory, a project's subdirectory too, it refuses (exit 2) and says so.
Three tasks run anywhere: `help`, and `init` and `adopt`, which make a
project; an unknown task is refused as unknown wherever it is run.
Every path a project's configuration and spec name is relative to that root,
at a cut as in the working tree.

**Release**: a build of rdm-spec accepted by the spec owner after it was
scored, and installed. Release N runs cycle N+1 (fence, verify, checklist,
score, the oracle) on the build of N+1; a build never judges itself, and none
is installed before it is scored, as a compiler builds its next version and
is replaced only once that version passes.

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
to. Cited as `<source>:<path>@<commit>` (Citation). Where a source and the spec disagree,
the source wins and the spec is the bug. A source's implementation is fenced:
never cited, and absent from the clean room. A fenced path covers itself and
every path under it; `.` covers the whole source.

**Citation**: `<source>:<path>@<commit>`: a source id (a letter or digit,
then letters, digits, `.`, `_` or `-`), a path holding no space, quote or
`@`, and a commit of 7 to 40 hexadecimal digits. Citations are read from the
needs, where each entry of a need's `:sources` is one citation, whole, or
malformed, and from the wiki's footnotes, every citation on a footnote's
line. A commit matches a pin when either starts with the other, case
ignored. A citation of a retired source fails as retired, whatever its pin.

**Dropped pin**: a pin that an earlier version of the sources index had for a
source, and that the index as it stands lacks, the source's own entry
included: a source removed from the index drops every pin it had. The versions are every commit
that changed the index, oldest first, then the working tree; a version that
cannot be read as EDN holds no source. Its finding names the source, the pin,
and the first version after the last one that had it: that commit's id, its
first seven characters, or "the working tree". A pin dropped and later
restored is not dropped. Outside a git repository the index has no history.

**Domain wiki**: the domain distilled from the raw sources, `dhf/wiki/`: every
claim footnoted to a source, every page in the index, no implementation named.
The spec rests on it. Its index is `index.md`; its pages are every other `.md`
file under its folder. A footnote is a line starting `[^label]:`, and a page
with none cites nothing. The index lists a page by a Markdown link whose
target, relative to the wiki's folder and without its `#anchor`, is the page's
path; a link to a URL lists no page. A stack word is a configured word found
whole, case kept, one finding per word and line, on the pages and the index.
A project with no wiki folder has no wiki finding.
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

**Event**: a domain event that passes between bounded contexts within one run.
Steps that run at different times meet through files instead.

**Event catalogue**: a project's events, in the file the configuration's
`:events` names (`dhf/spec/events.edn`): each with its description, its one
publisher, the keys it carries and their shape. Its publisher, its keys
and their shape are its contract: a change to any reaches the publisher and
every subscriber (`scopes`); its description is not. It
is the project's design record: the model's lint checks it against the
model's relationships, and `scopes` compares it between cuts. It never
declares rdm-spec's own events, and the bus never reads it.

**Event bus**: rdm-spec's in-process bus, synchronous and in subscription
order. It carries rdm-spec's own events, the ones between its contexts, which
a release carries built in from rdm-spec's own spec; it refuses one rdm-spec
does not declare, whatever a project's catalogue holds. A refusal is
`cannot-run` (exit 2): a defect in rdm-spec, never a finding about the
project.

**Domain model**: what a project's event storm decides, kept as data when a
project keeps it at all. It is not the core's: a project that keeps one adds
the `ddd-domain` plugin, which holds it to the rules of domain-driven design
(ADR 0003).

**Event storm**: a way of finding the domain model with the people who know
the domain: actors, commands, events, policies, read models and hotspots. It
is a way of working, kept as prose when kept at all (dhf/storm/), never part
of the spec.

**Plugin**: a judge a project keeps for itself, outside the core: a command in
a kept path (`dhf/harness/plugins/<name>/`), declared in the configuration's
`:plugins` in the order it runs. It subscribes to events the core publishes
(its published language, `events.edn`), given each as EDN on its standard
input, or answers a request the core makes: `:Gate` before fence and verify,
`:Check` as one of verify's checks. It reports as a task does: findings on its
error stream, exit 0 holds, 1 findings, 2 cannot run, any other exit or a
signal cannot run; its standard output is not the task's report; past its `:seconds`
(60 by default) it is stopped and named as timed out. Every task that runs a
plugin refuses a declaration it cannot trust before anything runs. Its `:env` is added to its
environment beside `RDM_SPEC`; a `:Check` plugin may write results
of the spec's scenarios into the directory its request names, held to the
`:allure` mapping as the tests check's are. A plugin is added from a
template rdm-spec carries (`rdm-spec plugin add NAME`, which records the
template in `origin.edn`; every package of rdm-spec carries the same
templates as its sources) and is then the project's own, evolving with it
(dhf/spec/08-plugins/plugins.feature; ADR 0003).

**Published language**: the events rdm-spec publishes, as `events.edn`
declares them: what a plugin may read of the core, and all it may.

**Skeleton**: what `rdm-spec init` writes and `rdm-spec adopt` adds: the spec,
the harness configuration, the raw and wiki directories, decisions, the
pre-commit hook, the cut workflow and `.gitignore`, as listed by
`dhf/spec/01-start-a-project/init.feature`.

**Kernel**: shared code every bounded context may use and that belongs to none
of them, such as the event bus. Specified in the slices that reach it (the model's `:kernel`), never drawn as
a container.

**Model**: `dhf/spec/model.edn`, the one C4 source. Every view is generated
from it.

**Retired source**: a raw source kept in `dhf/raw/sources.edn` with its pins
and `:retired` and the reason, because a pin is never dropped; nothing may
cite it.

**Model hash**: the SHA-256, as lowercase hex, of the model read as EDN and
printed back with every map's entries and every set's elements sorted by the
printed form of the key or element, each printed as EDN prints it with no
namespace map syntax, so reformatting the file keeps it. Printed exactly: a
map as `{k v, k v}`, its entries separated by a comma and a space; a vector
as `[a b]` and a set as `#{a b}`, separated by a space; a keyword with its
namespace, `:heat/coil`; a string in double quotes, with `"`, `\`, a newline
and a tab written `\"`, `\\`, `\n` and `\t`, every other character as it is;
an integer in decimal digits, and `true`, `false` and `nil`; the text hashed
as UTF-8. A spec change that
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
room. A human or an agent. One builder per cycle, in one room, building its
scopes one after another in the order `scopes` lists them; never contexts in
parallel.
_Avoid_: coding agent (as the role's name).

**Spec owner**: the role that reads the scoreboard and decides what the spec
must learn next. Never the builder of the cycle it reads.

## The harness

**Harness**: the bounded contexts that check the spec, verify a build and score
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

**Allure result**: one `<uuid>-result.json` file in a results directory, in
Allure's own model, filled from the Gherkin scenario
(`dhf/spec/05-verify-by-results/runner.feature`): `name` (the title), `fullName`,
`historyId` (the scenario's id, and the row's values for an outline row),
`status` (`passed`, `failed`, `broken` or `skipped`) and `stage`,
`statusDetails` when it did not pass, `start` and `stop`, `steps` (one per
Gherkin step), `parameters` (an outline row's values), `labels` (`feature`,
`story` from the rule, `epic` and `suite` from the context, `subSuite`
the component's id without its colon, a `tag` per Gherkin tag without its
`@`, `framework` `gherkin`, and rdm-spec's own
`spec` (the scenario id, exactly one), `context`, `component` and `planned` =
`true` for a scenario tagged `@wip`), `links` (one `issue` per need, its url
`<needs>#UN-nnn`, `<needs>` the configuration's `:needs`, `dhf/spec/needs.edn`
unless it says otherwise) and `attachments` (evidence). `failed` is a Then
whose assertion did not hold; `broken` is anything else that went wrong. A status is decided by `status`, the `spec` label
and an outline row's `parameters` only; everything else is kept for the
report. A result with no `spec` label verifies nothing.

**Grader**: the role that grades each checklist item against a verified build,
however they grade. A human or an agent, never the builder of what is graded.

**Verdict**: a grader's pass or fail for one checklist item, with the evidence. Verdicts are
the score; the builder's tests never are.

**Verified / failed / unverified / planned**: a scenario's status, derived
from the oracle: a passing result and none that did not pass; a result that did
not pass; no result at all; tagged `@wip`, whatever the results. An outline is
one scenario: verified only when every row has a passing result, failed when
any row's result did not pass, else unverified. A result counts toward a row
only when it names the row, by its parameters. Status never
enters a score: only a grader's verdicts do.

**Slice**: a piece of behaviour, planned and rebuilt whole (ADR 0004; Löyly's
`docs/plan/` slice). Under `:spec-layout :slices` it is a folder of the spec,
`<NN-name>/`, holding its plan (`plan.md`: goal, references to the needs it
serves, scope in and out, decisions) and one feature file per component it
reaches. A change to any of its files makes it a scope, and a slice scope
rebuilds, and grades, every scenario of every component it reaches, in
whichever slice the scenario sits.

**Slice development**: working slice by slice (ADR 0005; Löyly's
way of working, sections 1, 2 and 6). A behaviour change starts in a slice;
a cycle builds one slice (`cycle.edn` `:slice`), whole; its checklist marks
each item `:role` `"slice"` (the slice's own scenario) or `"regression"` (a
scenario of a component it reaches, in another slice), and its score counts
them apart; status reports each slice, and a release can be asked of one. A
slice's plan says its status, `planned`, `ready`, `building` or `done`, and
its `decisions.md` keeps what was decided in it. A slice is done when its
last cycle was accepted and every one of its scenarios is verified.

**Spec folder**: one spec of a project that keeps several: a folder
with its own feature files, model, event catalogue, needs, glossary, id ledger
and domain model, declared in the configuration's `:specs` with its name and
id prefix. A need, a component and a context resolve within their own spec; a
cycle may be scoped to one spec, and `status` and `release` read every spec or
the one named. A project that keeps one spec declares no `:specs`.

**Cut**: a merged spec change tagged `spec-N`. Regeneration reads a cut and
nothing else: the project's root as committed at the tagged commit. A tag
belongs to the whole repository; in a monorepo, the cut of a project is its
folder at that commit.

**Fence**: the step that builds a clean room from a cut: one fresh commit
holding every file committed at the cut except the denied ones
(`:clean-room :deny`: the scope's implementation, the measurements, the
generated output), as Löyly's fence is the repository minus every
implementation and measurement. Robust by three rules: it reads only the cut's
committed tree, so nothing untracked or ignored reaches a room; every
committed top-level path and every path directly under `dhf/` is either denied
or kept (`:clean-room :keep`), or `rdm-spec check` fails; and no kept file may
carry the bytes of a removed implementation file.

**Scope**: what a regeneration rebuilds: the whole project, one bounded context
or one component, or a list of contexts and components. The kernel counts as
a context named `kernel`. A cycle's scopes are what `rdm-spec scopes` lists
between two cuts: the components whose own spec changed, and, for each
event whose shape changed or that was added or removed, its publisher and
every subscriber; a component whose only change is upstream code is not
rebuilt. Every few cycles one is a whole-project cycle, rebuilding everything,
because a scoped cycle hides what it does not rebuild. The rest of the implementation stays in the clean
room as it was at the cut. A checklist names an item's context and component by
the model's `:name`. A cycle's checklist holds the items of its scope,
and nothing else: a scoped cycle is graded on its scope only.

**Regeneration**: building the implementation again from a cut, inside the
fence.

**Spec check**: the rules `rdm-spec check` holds a spec to: one id and one
level per scenario, only known tags, needs resting on resolving sources,
planned work marked and no stack word. `rdm-spec check` also runs the model's
lint, the views' freshness and the wiki check.

**Verify**: `rdm-spec verify ROOM CYCLE`, a cycle's gate: the room's
implementation, in place of the implementation of a fresh checkout of the cut,
passes every check the project declares (`:verify` in its configuration), in
order, the first that fails stopping it. Run by the installed rdm-spec; it
gates and never scores. Its record is `ratified.edn`.
_Avoid_: ratify (the old name, still in a few comments until their feature
next changes); gate, on its own (say spec check or verify); confusing it with
**Verified**, a scenario's status.

**Check**: one thing verify proves about a build, declared by the project, of
one of four kinds; a new kind is a spec change, never a configuration.
`:tests` (always declared, first): the project's test command passes and its
Allure results name and pass every scenario. `:serves`: the app the project
serves starts fresh, answers within its budget, and passes a probe that lives
in a kept path, never in the implementation. `:runs`: a command answers with
the exit and output the project expects, within its budget. Every check has a
budget and reports its margin; a check that cannot run (a command not found,
a port held) refuses the gate, never passes it. A `:serves` app answers when
`GET /` on its port returns any HTTP response, whatever its status; an
accepted connection with no response is not an answer. Declared in the
configuration's `:verify` as `{:kind :tests :command [...]}`, `{:kind :serves
:name N :start [... "{port}" ...] :port P :budget {:seconds S :polls K} :probe
[... "{url}" ...]}` and `{:kind :runs :name N :command [...] :expect {:exit E
:stdout-starts T} :budget {:seconds S}}` and `{:kind :plugin :name N}` (the
project's plugin N answering `:Check`, dhf/spec/08-plugins/plugins.feature); a
check's name is its kind's when it names none. A `:serves` check's probe is
given `{url}` as `http://127.0.0.1:PORT/` (with its "/") and runs for at most
`:probe-seconds` (30 by default) after the app answered; past it, it is stopped
and named as timed out, what it printed kept. A `:serves` check polls K times, poll i at i x S/K seconds
after its app starts; `:answered-on-poll` is the first poll answered and its
margin is the polls left (K minus that poll). A `:runs` check's margin is the
seconds of its budget left unused, to 0.01. A verify that cannot run before
any check runs (exit 2) writes no `ratified.edn`, since nothing was verified;
one refused (exit 1) writes it, with `:checkout` only when the checkout was
made. Once its checks have run, a check that cannot run (a plugin's, exit 2)
or a `:CycleVerified` subscriber that cannot run still leaves `ratified.edn`
written, that check `:refused`, and verify exits 2.

**Cycle**: one regeneration and its score: `dhf/cycles/NN/cycle.edn`, written
by hand (cut, scope, builder, grader, spec owner, and `:bootstrap` for the very
first), with `results/`, `build/` and `ratified.edn` (verify), `checklist.json`
and `score.edn` beside it, `test.log`, the test command's output
(verify), and `runs.edn`, each run observed (observe). The checkout is an
export of the cut: its files, and no history.

**Oracle (instrument)**: the judge of a project's instrument, held still: a
command the project keeps by hand, outside its implementation, never
regenerated: the project's plugin named "oracle", answering
`:Gate`. `rdm-spec oracle [ARGS]` runs it in the project's root with ARGS and
with `RDM_SPEC` set to a command line starting the same rdm-spec, however it
was installed (words separated by spaces; the rdm-spec it runs keeps the
caller's `TMPDIR`), relays its output, and exits as it exits; fence and verify
ask it first, with every other Gate plugin, and stop while it fails. No plugin
named "oracle" is no oracle, said on standard output only. rdm-spec's own is `dhf/harness/oracle/oracle.clj`: it runs rdm-spec as
a black box on cases that each carry their whole fixture, and compares exit,
output, error stream and written files with goldens recorded from an
accepted release and pinned by digest (`dhf/harness/oracle/README.md`). A
judge regenerated from the spec it judges agrees with the spec's mistakes;
Löyly holds its harness still for the same reason. It
judges the instrument; the Allure results remain the oracle of a project's
scenarios.

**Allure mapping**: the configuration's `:allure`: which element of the spec
each Allure element of a result carries (`:epic :context`, `:feature
:feature`, `:story :rule`, `:step :gherkin-step`, the labels and the need
links, and the `:evidence` each scenario level requires). `:evidence` is
`{level [attachment-name ...]}`, as `{:e2e ["browser-trace"]}`: a result of a
scenario at that level carries an attachment of each name, on the result or
on any of its steps. rdm-spec's default
is used when a project declares none; verify's tests check holds every result
to it.

**Finding**: one line of a task's error stream: a file, a line where one
applies, a RULE and what is wrong (`dhf/spec/README.md`, "How every task
reports"). A task's findings come in no stated order unless a scenario states
one, so a reader compares them as a set; its warnings follow its findings.

**Gate**: a stage the task table runs first and that stops the task when it
has findings that are not warnings; the stages after it never see it. Every other stage reports, so
one run names every finding. A stage whose code is not built, a gate or not,
refuses the task (exit 2), since a stage skipped is a check that reads as a
pass, as Löyly's gate refuses an instrument nobody checked
(loyly:harness/README.md@17d68d2, "What makes the checks run"). Publish runs
the views' freshness check as a gate before the site; audit runs its check
that everything it is made from is committed as a gate, before the release
check.

**Scoreboard**: the comparison of two cycles over the items both scored,
counting what got better and what got worse, never a net.

**Gherkin runner**: the kernel code that runs scenarios against their step
definitions and writes Allure results labelled with each scenario's id.

**Cutover**: the moment the regenerated core, passing the whole spec
at a cut, took over this repository from the old record: 2026-10-26
(ADR 0002).

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
(the configuration, the spec, the clause lists, the documents) differs from HEAD's tree, untracked and ignored files
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

## Runs

**Run**: one agent's or person's turn in a cycle, as a builder, a grader or
the integrator. It has two halves, never netted: its **capability**, what it
was for, proven by its result (verify, the score), never by its report; and
its **defence**, what it did outside its brief, observed from outside.

**Observation**: `rdm-spec observe begin` before a run and `observe end` after
it: the entries of the watched directories and of the temporary directory
compared, the project's git status outside the cycle compared, the results
directory looked for inside the room, the time measured and the cost given.
Each run is one entry of `dhf/cycles/NN/runs.edn`. Observing never prevents
and never stops a run; reads are not observed, and the record says so.

**Breach**: a run's write outside its room, an entry it left in the temporary
directory, a change it made to the project outside its cycle, or results it
wrote inside its room. Counted per cycle beside the score, never inside it.

**Integrator**: the role that fences, merges what builders built and runs
verify, checklist and score. Every fix it makes by hand is an integration
fix, recorded in the cycle's findings.
