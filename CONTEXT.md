# RDM

RDM audits the record of regulated health software: the spec it is held to
(written by whoever owns it, a factory such as Löyly or a team), the risks and the controls
that reduce them, the checklists of the standards that apply, and the evidence
that each requirement was verified. This glossary is the language of that
record.

## Needs and requirements

**Spec**:
What the product must do, written the Löyly way: a plan of slices
(`.pi/plan/`), one per bounded context, each with its accepted Gherkin
scenarios, its Allium model and its event model, and an event storm behind
them. RDM's contract with a spec is only Gherkin and Allure, so plain feature
files work too (`spec:` in the DHF's configuration). RDM reads it and never
writes it.
_Avoid_: design inputs, SDD, the record (for the spec alone)

**User need**:
What an actor needs, as the spec captures it: a screen of a context's event
model, with the Allium surface it links (whom it faces, the rules it
provides, what it guarantees) and the event-storm events behind it (in plain
Gherkin, a Feature, its description the need). It is validated, not
verified.
_Avoid_: user story, feature, epic, UN-n

**Requirement**:
An accepted scenario of the spec, a concrete Given/When/Then example
stamped with an id (`@item-02-001`; in plain Gherkin, any id-shaped tag): one
acceptance criterion, accepted or not as a whole. It addresses the user
needs whose Allium rules or guarantees its `@allium-…` tags cover (in plain
Gherkin, the Feature it sits under). A test
run claims it with an Allure `tag` label of the same id.
_Avoid_: design input, DI-n, spec item, story, ticket, the test, sub-criterion

**Stamp**:
The id tag a scenario carries: never renumbered, never reused, kept when the
scenario is reworded. Whoever owns the spec stamps it (a factory's stamper,
or a person); a scenario without one blocks a release. RDM never stamps.
_Avoid_: id assignment, numbering

**Baseline acceptance criterion**:
A requirement that no risk's controls name.
_Avoid_: functional requirement, happy path

**Risk-based acceptance criterion**:
A requirement a risk's `controls` name. It counts as met only when it is
verified and the residual risk of the risk it controls is acceptable.
_Avoid_: safety requirement, mitigation requirement

**Bounded context**:
A unit of design: one context of the spec, one slice of its plan. It is drawn
where the language changes, not where the workflow does, and named for its
domain, never for what its code does or the technology it uses.
_Avoid_: module, component, service, container, stage, layer

**Task acceptance criterion**:
A checklist item on a planning task. Planning, never the record: it is not a
requirement and nothing verifies it.
_Avoid_: acceptance criterion (unqualified), requirement

## The record and its gates

**Design record**:
Everything RDM audits, in git: the spec, the design documents, the risk
register, the validation records, the controlled documents and the tests. It
is the only source of truth; the spec changes in the factory's own process,
the rest only by a reviewed pull request.
_Avoid_: database, docs

**Design history file**:
The design record as a regulator receives it: the documents rendered from the
record, with their evidence. Abbreviated DHF. The `dhf/` directory `rdm adopt`
and `rdm init` lay down holds the record those documents are rendered from;
it is named for what it becomes.
_Avoid_: the record (for the rendered output)

**Frontmatter**:
The YAML block between `---` lines at the top of a Markdown document: its id,
revision and title, and the record's declarations (risks, the risk policy,
the contexts and references of a document). The gates read them from it.
_Avoid_: header, metadata block

**Design document**:
The controlled document of one bounded context that describes how the
context meets the requirements of its slice of the spec.
_Avoid_: spec, SDD (unqualified)

**Design output**:
The architecture that meets a bounded context's requirements: its components,
what each is responsible for, and how they relate, in its design document. It
names components, never source files or functions.
_Avoid_: implementation notes, module list, code walkthrough

**Controlled document**:
A document with an id and a revision whose every change goes through review.
_Avoid_: doc, file

**Approved**:
Said of a design document that is committed and merged
through a reviewed pull request. Git is the approval record; no sign-off table
repeats it.
_Avoid_: signed off, accepted

**Independent review**:
Approval of a pull request by someone other than its author: the record's
independent verification. An agent never gives it.
_Avoid_: code review (unqualified), self-review

**Code coverage**:
How much of a unit's code its unit tests run: a unit-test measure. Never
acceptance evidence, never carried in Allure, never in the record's
traceability; RDM does not read it. Not checklist coverage (the clauses the
documents reference).
_Avoid_: coverage (unqualified), test coverage (for the requirements a test verifies)

**Release gate**:
The check that blocks a release until the spec is read whole (every scenario
stamped, no id twice), it holds at least one requirement, every requirement
is verified, every user need is addressed by a requirement covering its rules
or guarantees, and the risk rules raise no blocking finding. It warns, never blocks, on a user need with no
approved validation record. Whether the runs are release-grade evidence is
shown by the verification report and the knowledge graph, not decided by the
gate.
_Avoid_: CI, green build

**Design specification**:
The core bounded context: the spec, read — its user needs and
requirements. It depends on no other context; every other context conforms to
its ids. It reads a specification and never polices how one is written: that
is the factory's (ADR 0002).
_Avoid_: design controls (for this context; the term names the regulatory
process RDM as a whole implements), requirements module

**Release**:
The bounded context that decides whether a release may go ahead: the release
gate over verified status, risk and validation. It reads the specification,
test evidence and risk; none of them reads it. The evidence a release keeps
is published by publishing.
_Avoid_: deployment, CI (for the context)

**Shared kernel**:
The helpers every bounded context may use and that use none: YAML and files,
ids, git, frontmatter, the reconcile helpers. Not a context: it has no
language of its own and owns no requirement.
_Avoid_: utils, common, core (the core is the design specification)

**Composition root**:
The one place that wires the command line to every context (`rdm/main.py`).
It is in no context, so it may import any.
_Avoid_: main module, glue

## Verification and validation

**Test**:
An automated check tagged with the requirement it verifies. It verifies the
acceptance criterion; it is not the criterion.
_Avoid_: acceptance criterion, scenario, spec

**Verification step**:
One named check within a test, with its own result; a test is the ordered set
of its verification steps. It belongs to the test, never to the acceptance
criterion: a failed step fails the test, and the requirement stays one
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
(Allure) and translates them into test runs of the record's tests,
labelled with the commit under test. The tools' own words (result, label,
container) stop at its boundary.
_Avoid_: test results (for the context), reports

**Verified**:
Said of a requirement with a passing run of a test tagged with it and no
failed one. It shows the requirement is met, not that the test proves it.
_Avoid_: validated, tested, done

**Release-grade evidence**:
Verification in which every requirement is verified, no run failed, and every
run tested the record's commit with no uncommitted changes.
_Avoid_: green build, passing CI

**Validated**:
Said of a user need whose intended users, in its intended use, were shown to
reach its result.
_Avoid_: verified, accepted

**Traceability matrix**:
The table from user need to requirement to test to result, generated from the
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

**Checklist reference**:
A checklist clause's key written in a controlled document inside `[[ … ]]`
(`[[62304:5.1.1]]`), saying where the document meets the clause. Only text
inside the brackets counts; a bare mention does not.
_Avoid_: tag (a tag names a requirement on a test), citation

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
Something done to reduce a risk, allocated to a requirement that implements it.
Defined once and referenced from every harm pathway it bears on.
_Avoid_: measure, mitigation, safeguard

**Control effect**:
What one risk control does to one harm pathway's probability, whether relative
or absolute, and where it sits in the order controls are applied.
_Avoid_: measure effect, reduction, impact

**Effective**:
Said of a risk control that is verified and whose risk has been evaluated again
with an acceptable residual. A passing test alone makes a control verified, not
effective. The record shows both facts; judging that the control does what it
is meant to stays a person's.
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

**Data file**:
A YAML file whose content a template renders, named in the template by the
file's name without its extension (`data/device.yml` is `device`).
_Avoid_: config (a render configuration names the extensions, not data)

**Audit note**:
Any `[[ … ]]` text in a template, checklist references among them: kept in
the auditor's copy of a rendered document, removed from the engineers' copy
by the audit-note extension.
_Avoid_: comment, annotation

**Reference document**:
A Word file whose styles, header and footer Pandoc gives every rendered Word
document.
_Avoid_: Word template (it is not a template of the record)

**Device master record index**:
The generated list of controlled documents (id, title, path, revision) a DMR
document renders. Abbreviated DMR index.
_Avoid_: document list, register (the risk register is another thing)

**Instructions for use (IFU)**:
The product's instructions for anyone who uses it; in plain words, its user
manual (RDM's is its documentation site). A design output, listed page by page
by a controlled document of kind `manual`, so the DMR index holds it. What it
must teach is stated as requirements.
_Avoid_: docs (unqualified), help

## Knowledge graph

**Knowledge graph**:
The record — the spec, its test runs, history, risks and checklists —
projected into RDF with RDM's vocabulary, for people and agents to query. Read-only and rebuilt from the
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
