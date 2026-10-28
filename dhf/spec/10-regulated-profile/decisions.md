# Decisions: slice 10-regulated-profile

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `audit.feature`, Feature: Audit copy

What a team hands an auditor (dhf/wiki/pages/regulated-profile.md): every
controlled document twice, the auditor's copy with its audit tags and the
engineer's copy without, the clauses with where each is met, the
traceability, and the evidence, with a manifest that pins it all. Every file
is generated from the record; none is edited by hand.

"A regulated project" is as in dhf/spec/10-regulated-profile/gap.feature: :regulated
with the clause list dhf/clauses/std.txt and the documents directory
dhf/documents. OUT and OUT2 are directories the scenario names.

### `audit.feature`, Rule: Only a releasable record has an audit copy

An audit copy of a record that is not releasable shows an auditor
something nobody may ship. The release check, gaps included, is the gate
before the audit copy, as the oracle is before fence
(dhf/spec/01-start-a-project/command.feature, "A gate stops the task"). The audit copy
reads the scenarios (SpecParsed), their statuses (VerificationDerived) and
the clauses (ClausesIndexed, within its own context) from the bus.

### `audit.feature`, Rule: Every document has an auditor's copy and an engineer's copy

The two copies are identical but for the notes
(innolitics-rdm:README.md@f21b508, "Easy auditablility").

### `audit.feature`, Scenario: The audit copy is refused while what it is made from is not committed (spec-0274)

An untracked document closed a gap while the manifest said :clean true
at a commit that did not hold it (cycle 08, grading); then an uncommitted
scenario title reached the traceability page, and the release check and
the audit read two revisions and disagreed (cycle 09, grading); then a
clause list removed from the configuration in the working tree only let
the audit pin a commit the release check refuses (cycle 10, grading).
Then a document git ignores did the same (cycle 11, grading). An audit
copy is of one commit or of nothing: every file it reads is HEAD's.

### `checklist.feature`, Feature: Checklist

Slice 10-regulated-profile: the checklist's regulated rule (ADR 0004).

### `checklist.feature`, Rule: Under the regulated profile, every clause is an item to grade

A clause named is not a clause met (dhf/wiki/pages/regulated-profile.md,
"Referenced is not met"). A grader judges it as a scenario is judged, and
an ungraded clause is a fail like any ungraded item.

### `gap.feature`, Feature: Gap analysis

The regulated profile (dhf/wiki/pages/regulated-profile.md): a project whose
configuration holds :regulated declares the clause lists it is held to and
where its controlled documents live. The scenarios stay the functional spec;
the clause lists are the non-functional one, met by documents. Gap analysis
is a stage of "rdm-spec check": the same command, the same hook, the same
findings. rdm-spec ships no clause list and no document: the project brings
both, whatever the standard.

"A regulated project" is a project set up by rdm-spec whose configuration
holds :regulated with the one clause list dhf/clauses/std.txt and the
documents directory dhf/documents, every document of which has frontmatter
with an id, a revision and a title unless the scenario says otherwise.

### `gap.feature`, Rule: Without :regulated, nothing of the profile runs

A project that never asked for the profile must not pay for it: Löyly's
spec carries no regulated part (loyly:README.md@17d68d2).

### `gap.feature`, the end of the file

The standard's clauses

### `gap.feature`, Scenario: A line of prose in a clause list is malformed, never a clause (spec-0282)

A stray prose line in a clause list became a clause (cycle 10, builder).

### `gap.feature`, Scenario: An audit tag in a list item's continuation paragraph names its clause (spec-0281)

A list item's continuation paragraph was read as code (cycle 09,
grading): in Markdown it is the item's text.

### `gap.feature`, Scenario: An audit tag in an indented code block names nothing (spec-0275)

A tag inside an indented code block named a clause (cycle 08, grading).

### `gap.feature`, Rule: Frontmatter names no clause

A title is not where a clause is met: cycle 07's spec-0250 put a tag on a
line that could only be frontmatter, and it counted.

### `gap.feature`, Rule: A placeholder is a warning

A document that still says nothing meets no clause, however many it names.

### `gap.feature`, Rule: A controlled document carries its identity

An auditor asks for a document by its id and revision; one without them is
not controlled (innolitics-rdm:README.md@f21b508).

### `release.feature`, Feature: Release check

Slice 10-regulated-profile: the release check's regulated rule (ADR 0004).

### `release.feature`, Rule: Under the regulated profile, a release needs every clause named

A clause no document names is unverified the way a scenario with no result
is (dhf/spec/10-regulated-profile/gap.feature).

