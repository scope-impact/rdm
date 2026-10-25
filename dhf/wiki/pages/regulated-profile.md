# The regulated profile: clauses, audit tags and the auditor's copy

Regulated software is held to standards whose clauses are not behaviour a
scenario can run: a plan exists, a risk is evaluated, a release is recorded.
A team shows an auditor where each clause is met by pointing at a controlled
document. This page is how that is done in the source rdm-spec takes it from,
and where rdm-spec departs.

## Clause lists

- A standard is reduced to a list of clause keys, each with a short
  description that is a mnemonic for the clause, never a substitute for
  reading the standard.[^checklists]
- A list may include another, so a stricter class is its base plus its own
  clauses.[^checklists]
- The source ships lists for one regulator's standards. rdm-spec ships none: a
  project declares the lists it is held to, so no standard is privileged.

## Audit tags

- A document names the clause it meets inside double square brackets, as
  `[[62304:6.2.4]]`: an auditor note.[^notes]
- The notes help an auditor, and they tell whoever adapts a document which of
  its parts a standard requires.[^notes]
- Gap analysis lists every clause no document names, and fails; the source
  recommends running it in continuous integration.[^gap]
- The source searched for a key anywhere in a document.[^gap] rdm-spec counts
  a key only inside an audit tag: a key mentioned in passing is not a claim
  that the clause is met there.

## Two copies of every document

- The team works from the copy without the notes, and gives the auditors two
  copies: that one, and the auditor's copy, with every note.[^auditable]
- Removing a note removes the white space before it, so the sentence still
  reads.[^notes]
- Apart from the notes, the two copies are identical.[^auditable]

## Referenced is not met

- Gap analysis proves that every clause is named somewhere, not that the
  named place meets it.[^gap] rdm-spec grades each clause, as it grades each
  scenario: a grader reads the tagged sections and judges, and a clause
  nobody graded is a fail.

[^checklists]: `innolitics-rdm:README.md@f21b508`, "Audit Checklists".
[^notes]: `innolitics-rdm:README.md@f21b508`, "Auditor Notes Extension".
[^gap]: `innolitics-rdm:README.md@f21b508`, "Audit Checklists", on `rdm gap`.
[^auditable]: `innolitics-rdm:README.md@f21b508`, design principle 4, "Easy auditablility".
