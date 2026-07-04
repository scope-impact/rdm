---
id: SDS-DC-001
kind: design
context: document_control
satisfies: [UN-001, UN-002, UN-003, UN-004]
design_inputs:
  - id: DI-1
    text: "Changes to the default branch shall require a pull request with at least one code-owner approval, verified commit signatures, passing status checks, and protection against history rewriting and branch deletion, enforced by a repository ruleset kept as configuration code."
    traces_to: [UN-002, UN-004]
  - id: DI-2
    text: "Every controlled document shall declare its identity and revision in frontmatter."
    traces_to: [UN-001]
  - id: DI-3
    text: "A document release shall be triggered by pushing a release tag and shall attach both human-readable rendered copies and a complete electronic archive of the document set to the release."
    traces_to: [UN-003]
  - id: DI-4
    text: "Each rendered controlled document shall embed its revision history from repository data, not a hand-maintained table."
    traces_to: [UN-001]
  - id: DI-5
    text: "The document control procedure shall address every item of the Part 11 document-control checklist, with the gap analysis reporting full coverage."
    traces_to: [UN-004]
---

# Document control — Design

## Design Inputs

This context owns the whole system (one bounded context — the example is a
single procedure):

- **DI-1 (gated approval as e-signature)** — the ruleset is the enforcement of
  §11.10(f)/(g), §11.50/70/100 mechanics: independent code-owner approval,
  signed commits, required checks, immutable history. Refines UN-002, UN-004.
- **DI-2 (document identity)** — id + revision in frontmatter makes the
  current approved revision identifiable. Refines UN-001.
- **DI-3 (release copies)** — tag-triggered release with rendered PDF copies
  and a `git archive` electronic set (§11.10(b)/(c)). Refines UN-003.
- **DI-4 (generated history)** — the revision-history table in a rendered
  document comes from repository data (§11.10(e) altitude: the record is
  generated, not transcribed). Refines UN-001.
- **DI-5 (Part 11 coverage)** — the SOP must reference every checklist item;
  `rdm gap` exit-zero is the acceptance criterion. Refines UN-004.

## Design Outputs

- `github/rulesets/controlled-documents.json` — the branch ruleset (DI-1).
- `github/CODEOWNERS` — routes controlled paths to the quality team (DI-1).
- `github/workflows/release-documents.yml` — tag-triggered release (DI-3).
- `documents/document_control_procedure.md` — the SOP: frontmatter identity
  (DI-2), embedded history template (DI-4), Part 11 references (DI-5).
- `checklists/part11_document_control.txt` — the audited Part 11 subset (DI-5).

Acceptance criteria are verified by `@allure.story("DI-1"…"DI-5")` tests in
`tests/acceptance/test_document_control.py`.
