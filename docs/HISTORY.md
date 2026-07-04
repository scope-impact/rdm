# Change History

## Unreleased

- Add `rdm adopt`: bring an existing repository under record-first design
  controls from one command (DHF skeleton, agent runbook, design-gate
  pre-commit hook, session bootstrap, CI gates) — never overwrites.
- Add `rdm story new-input`: scaffold a traced design input — next free DI id,
  frontmatter entry in the owning context, failing tagged stub test, checklist.
- Make `rdm story audit` record-first aware: design-input test-tag coverage in
  the report and the score.
- Ship the `part11_document_control` built-in checklist, and hold RDM's own
  record to it (`dhf/documents/document_control.md`, enforced by test).
- Add the canonical change procedure `dhf/AGENT_WORKFLOW.md` and make the local
  design gate active by default (committed `.githooks/`, session bootstrap).
- Add the worked example `examples/github-document-control/` — git as a
  document control system with GitHub as the provider (Part 11, DMR, DHR).
- Docs site: user manual (installation, quickstarts, task guides, CLI
  reference), Mermaid diagrams, and a traceability-matrix evidence page
  generated from a live acceptance run at every build.
- Fix `rdm gap --coverage` with built-in checklist names.

## v0.11.0

- Replace the `rdm tex` command with heavily customized pandoc calls. This simplifies
  the RDM code and allows for more easier PDF-customization.
- Add support for folders in the documents folder.
- Add support word document generation.
- Remove support for SVGs in the PDF files, which was fragile anyway since the
  SVG-to-PDF conversion is imperfect.
- Start adding a few 510(k)-related documents.
- Ignore all of the release files by default.
- Various improvemnts to the documentation.
- Improve data file organization.
