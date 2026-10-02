# RDM

RDM keeps the design record of regulated health software as Markdown and tests
in git: user needs, the design inputs that meet them, the risks and the controls
that reduce them, and the evidence that each was verified. This glossary is the
language of that record, aligned with the requirements and risk-analysis skills.

## Needs and requirements

**User need**:
What an intended user must be able to do, and the result they need, stated
without reference to a solution. It is validated, not verified.
_Avoid_: user story, feature, epic

**Design input**:
A uniquely identified `shall` requirement on the system or one of its
subsystems, owned by one bounded context and traced to the user need it refines.
It is one acceptance criterion, accepted or not as a whole; what must be
accepted separately is a separate design input.
_Avoid_: spec item, story, ticket, the test, sub-criterion

**Baseline acceptance criterion**:
A design input that follows from a user need alone and mentions no risk.
_Avoid_: functional requirement, happy path

**Risk-based acceptance criterion**:
A design input allocated as a risk control. It counts as met only when it is
verified and the residual risk of the risk it controls is acceptable.
_Avoid_: safety requirement, mitigation requirement

**Bounded context**:
The unit of design that owns design inputs and the components that implement
them; what the requirements skill calls a subsystem. Exactly one design
document describes each. In the architecture it is a boundary around
components, which may sit in more than one container. It is drawn where the
language changes, not where the workflow does: the stages of a design input's
lifecycle are one context, and a context is named for its domain, never for
what its code does or the technology it uses.
_Avoid_: module, component, service, container, stage, layer

**Realising context**:
A bounded context that implements part of a design input another context owns.
It does not own the input and is not where the input is declared.
_Avoid_: co-owner, contributor

**Task acceptance criterion**:
A checklist item on a planning task. Planning, never the record: it is not a
design input and nothing verifies it.
_Avoid_: acceptance criterion (unqualified), requirement

## The record and its gates

**Design record**:
Everything authored under control: user needs, design inputs, design
documents, the design review, the risk register, the architecture workspace and
the tagged tests, in git. It is the only source of truth, and only a reviewed
pull request changes it.
_Avoid_: database, docs, spec

**Design history file**:
The design record as a regulator receives it: the documents rendered from the
record, with their evidence. Abbreviated DHF.
_Avoid_: the record (for the rendered output)

**Design document**:
The one controlled document per bounded context that declares the design
inputs it owns and describes how the context meets them.
_Avoid_: spec, SDD (unqualified)

**Controlled document**:
A document with an id and a revision whose every change goes through review.
_Avoid_: doc, file

**Approved**:
Said of a design document or design review that is committed and merged
through a reviewed pull request. Git is the approval record; no sign-off table
repeats it.
_Avoid_: signed off, accepted

**Independent review**:
Approval of a pull request by someone other than its author: the record's
independent verification. An agent never gives it.
_Avoid_: code review (unqualified), self-review

**Design input lifecycle**:
The stages of one design input: declared, approved, verified, released; and
amended or retired from any of them. Stages of one design input, not bounded
contexts.
_Avoid_: phase, status (unqualified), workflow context

**Design gate**:
The check that blocks implementation until the design record is complete and
approved, and every id in it is declared once.
_Avoid_: pre-commit check, lint

**Release gate**:
The check that blocks a release until there is release-grade evidence, every
risk is acceptable and every user need is validated.
_Avoid_: CI, green build

**Design specification**:
The core bounded context: user needs, design inputs, the design review, and
the design input lifecycle with the design and release gates that guard it.
_Avoid_: design controls (for this context; the term names the regulatory
process RDM as a whole implements), requirements module

## Architecture

**Software system**:
The product in scope, or a significant external system it depends on; the
top level of the C4 model.
_Avoid_: application, platform, project

**Container**:
Something that runs or stores data within a software system: an application,
a command-line process, a data store, a queue. Not a Docker image unless the
image is the thing that runs.
_Avoid_: service, module, deployment, image

**Component**:
A logical building block inside exactly one container, owned by one bounded
context and naming the code that implements it.
_Avoid_: module, class, bounded context, service

**Person**:
A human role that uses the software system.
_Avoid_: user (unqualified), actor, account

**Relationship**:
A directed dependency between two architecture elements, labelled with what
the source does to the target.
_Avoid_: link, connection, arrow

**Architecture workspace**:
The one Structurizr workspace (`dhf/c4/workspace.dsl`) that holds the C4 model
and its views. It is written by people and is the source of every view.
_Avoid_: diagram (for the model), architecture file

**Architecture view**:
One view of the architecture workspace at one level: system context,
container or component. Its image is drawn from the workspace and stamped with
it; it is never edited.
_Avoid_: diagram (for the model), picture

**Architecture**:
The bounded context that owns the architecture workspace, its views, and the
conformance of the code and the record to it. The system architecture document
describes the system; this context keeps its C4 model.
_Avoid_: c4 (for the context), diagrams

## Verification and validation

**Test**:
An automated check tagged with the design input it verifies. It verifies the
acceptance criterion; it is not the criterion.
_Avoid_: acceptance criterion, scenario, spec

**Verification step**:
One named check within a test, with its own result; a test is the ordered set
of its verification steps. It belongs to the test, never to the acceptance
criterion: a failed step fails the test, and the design input stays one
criterion however many steps check it.
_Avoid_: acceptance criterion, clause, sub-criterion, assertion

**Test run**:
One execution of a test, at one commit, by one executor.
_Avoid_: build, job, test case

**Executor**:
Who or what ran a test run: a CI job or a person's machine, recorded with the
run.
_Avoid_: runner, agent (for the executor)

**Test evidence**:
The bounded context that takes test results in the formats tools write them
(Allure, xunit) and translates them into test runs of the record's tests,
labelled with the record at test time. The tools' own words (result, label,
container) stop at its boundary.
_Avoid_: test results (for the context), reports

**Mutation probe**:
A reviewer's check that one test fails when the behaviour it verifies is
deliberately broken: evidence that the test proves its design input.
_Avoid_: mutation testing (for a whole suite)

**Verified**:
Said of a design input with a passing run of a test tagged with it and no
failed one. It shows the requirement is met, not that the test proves it.
_Avoid_: validated, tested, done

**Release-grade evidence**:
Verification in which every design input is verified, no run failed, and every
run tested the record's commit with no uncommitted changes.
_Avoid_: green build, passing CI

**Validated**:
Said of a user need whose intended users, in its intended use, were shown to
reach its result.
_Avoid_: verified, accepted

**Traceability matrix**:
The table from user need to design input to test to result, generated from the
record and the test runs. It is never edited.
_Avoid_: trace table, RTM (unqualified)

**Evidence bundle**:
A release's verification data, traceability matrix and executed results with
every attachment they reference, in one archive with a manifest.
_Avoid_: artifact, report

## Compliance

**Compliance**:
The bounded context that holds standards as checklists and checks the
controlled documents against them.
_Avoid_: gap analysis (for the context; it is the activity), audit

**Checklist**:
A selection of a standard's clauses that a set of documents must reference,
composable by including other checklists.
_Avoid_: standard (for the selection), template

**Checklist clause**:
One requirement of a standard, as a checklist lists it, named by its key. A
controlled document references it; nothing in a test does.
_Avoid_: clause (unqualified), requirement, criterion

**Gap**:
A checklist clause that no controlled document references. Coverage is the
share of a checklist's clauses that are referenced.
_Avoid_: finding, missing item (unqualified)

## Risk

**Risk analysis**:
Identifying the hazard, the sequence of events, the hazardous situation, the
harm, and the estimate of its probability and severity.
_Avoid_: risk assessment (for this step alone)

**Risk evaluation**:
Comparing a risk estimate with the risk acceptability criteria, giving
acceptable or unacceptable. Without approved criteria it is blocked, not
guessed.
_Avoid_: risk scoring, risk rating

**Risk acceptability criteria**:
The project's own severities, probabilities, levels and the acceptability of
each level, approved before any risk is evaluated against them. Not a universal
matrix.
_Avoid_: standard risk matrix, ISO matrix

**Hazardous situation**:
A circumstance in which people, property or the environment are exposed to one
or more hazards. Not the harm, and not the cause.
_Avoid_: hazard, threat, scenario

**Harm**:
The injury or damage that a hazardous situation can lead to. Defined once and
referenced, so its severity cannot diverge between the risks that share it.
_Avoid_: impact, consequence

**Harm pathway**:
One pair of a hazardous situation and a harm, carrying its own probability.
One situation can have several, each with its own severity and probability.
_Avoid_: risk line, risk row

**STRIDE threat**:
A security threat classified as spoofing, tampering, repudiation, information
disclosure, denial of service or elevation of privilege. It complements safety
analysis and may link to a safety risk; it does not replace it.
_Avoid_: vulnerability, attack (for the classified threat)

**Risk control**:
Something done to reduce a risk, allocated to a design input that implements it.
Defined once and referenced from every harm pathway it bears on.
_Avoid_: measure, mitigation, safeguard

**Control effect**:
What one risk control does to one harm pathway's probability, whether relative
or absolute, and where it sits in the order controls are applied.
_Avoid_: measure effect, reduction, impact

**Effective**:
Said of a risk control that is verified and whose risk has been evaluated again
with an acceptable residual. A passing test alone makes a control verified, not
effective.
_Avoid_: controlled, mitigated, done

**Initial risk / residual risk**:
The same assessment at two stages: before any risk control, and after the
applied controls fold together. Stages are rows, not a before-and-after pair of
columns.
_Avoid_: gross risk, net risk, pre/post mitigation

**Proposal**:
A rating, a control, an acceptability criterion or a verification plan that no
person has approved yet. It is shown as a proposal until someone approves it.
_Avoid_: draft, assumption, default

## Publishing

**Publishing**:
The bounded context that renders the design record into controlled documents:
templates filled with data generated from the record, then Markdown, PDF and
DOCX. It reads the record; it never changes it.
_Avoid_: rendering (for the context), export

**Template**:
A controlled document's source, with placeholders that generated data fills.
The rendered document is output, not source.
_Avoid_: form, the document (for the source)

## Knowledge graph

**Knowledge graph**:
The design record, with its test runs, history, risks, checklists and
architecture, projected into RDF with RDM's vocabulary, rules and derived
relations, for people and agents to query. Read-only and rebuilt from the
record: never edited, never the source of a fact. `graph` names it in commands
and code.
_Avoid_: database, knowledge base, graph model, the record (for the graph)

**Projection**:
Turning one source of the record into facts of the knowledge graph, one named
graph per source.
_Avoid_: import, sync, export

**Vocabulary**:
The classes and properties the knowledge graph uses: the published language
that queries and agents rely on.
_Avoid_: schema (for the vocabulary), data model

**Gate rule**:
A shape over the knowledge graph. A violation blocks a release; a warning only
informs the reviewer.
_Avoid_: lint, constraint (unqualified)

**Derived relation**:
A fact a rule infers, kept apart from what the record states and never stated
by it.
_Avoid_: computed field, cache
