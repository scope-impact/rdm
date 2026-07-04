---
id: SDS-DC-001
kind: design
context: document_control
satisfies: [UN-001, UN-002, UN-003, UN-004, UN-005]
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
  - id: DI-6
    text: "Repository merge behavior shall be declared as configuration code: pull requests merge only by merge commit so the reviewed SHA is preserved in history, squash and rebase merges are disabled, head branches are deleted on merge, and the setup script shall apply and drift-check these settings against the live repository."
    traces_to: [UN-001, UN-004]
  - id: DI-7
    text: "The device master record shall be a controlled index document enumerating the specification set, rendered from repository data so it lists each controlled document with its identity and revision."
    traces_to: [UN-005]
  - id: DI-8
    text: "Each document release shall produce a device history record: a manifest recording the tag, commit SHA, releasing actor, timestamp, and artifact list, attached to the release alongside the copies."
    traces_to: [UN-005]
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
- **DI-6 (merge behavior as code)** — repository settings that shape the
  record are configuration code too: only merge commits (the SHA that was
  reviewed is the SHA preserved in history — the §11.70 linking argument),
  squash and rebase merges disabled (both rewrite the reviewed commits), head
  branches deleted on merge; `setup.sh` applies and drift-checks them like the
  ruleset. Refines UN-001, UN-004.
- **DI-7 (device master record)** — the DMR (§820.181 analog) is the current
  approved specification set: a controlled index document rendered from
  repository data (`data/dmr.yml`), listing each controlled document with its
  identity and revision. Being itself a controlled document, it flows through
  the same approval path it indexes. Refines UN-005.
- **DI-8 (device history record)** — the DHR (§820.184 analog) is the record
  of each release: the release workflow writes a manifest (tag, commit SHA,
  releasing actor, timestamp, artifact list) and attaches it to the GitHub
  Release with the copies — so every released document set carries the record
  of who released what, when, from which exact revision. Refines UN-005.

## Design Outputs

- `github/rulesets/controlled-documents.json` — the branch ruleset (DI-1).
- `github/CODEOWNERS` — routes controlled paths to the quality team (DI-1).
- `github/workflows/release-documents.yml` — tag-triggered release (DI-3);
  writes and attaches the device-history-record manifest (DI-8).
- `github/settings.json` + `setup.sh` — repository merge behavior as code,
  applied and drift-checked alongside the ruleset (DI-6).
- `documents/document_control_procedure.md` — the SOP: frontmatter identity
  (DI-2), embedded history template (DI-4), Part 11 references (DI-5).
- `documents/device_master_record_index.md` + `data/dmr.yml` — the DMR index,
  rendered from data (DI-7).
- `checklists/part11_document_control.txt` — the audited Part 11 subset (DI-5).

Acceptance criteria are verified by `@allure.story("DI-1"…"DI-8")` tests in
`tests/acceptance/test_document_control.py`.
