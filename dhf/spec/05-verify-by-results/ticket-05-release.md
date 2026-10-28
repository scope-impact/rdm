# Ticket 05: The release check

Slice: `05-verify-by-results` (`plan.md`, Scope In: "the release check (its
regulated rule until 10-regulated-profile holds it)"; Goal: "cannot release
until every scenario is verified"); `release.feature`,
`10-regulated-profile/release.feature`, `12-several-specs/release.feature`,
`13-slice-development/release.feature`; the glossary's "Planned" and "Gap".
Builds on ticket 03. spec-0267 and spec-0278 need gap analysis
(compliance/gap, slice 10): pending until cycle 10 (`pending.md`), their part
here done on unit tests and a stand-in clauses stage.

## What it delivers

verification/release's `run`, "rdm-spec release [--results DIR] [--spec
NAME] [--slice SLICE]":

- Refuses on verification/status' statuses (the model's "Refuses on"):
  every scenario of the specs asked, or of the slice asked, not verified
  (planned, failed, unverified) is one finding naming its id and its status,
  exit 1 (spec-0059, spec-0188, spec-0189); every one verified, exit 0
  (spec-0190). The result and the lines as G-25 settles; unknown results and
  unreadable files as G-26 settles; VerificationDerived delivered as G-20
  settles.
- `--spec NAME` (spec-0390) and `--slice SLICE` (spec-0415), as status reads
  them (ticket 03).
- Under `:regulated` only: the clauses stage asked (ED-07), its findings the
  release's (exit 1: a clause no audit tag names, spec-0267; a controlled
  document with no id, spec-0278); an answer of none, the stage not built,
  `cannot-run: release's clauses stage is not built` (exit 2).

## Scenarios it satisfies

- `@spec-0059` The release check refuses while any scenario is tagged @wip, `release.feature`
- `@spec-0188` The release check refuses a failed scenario, `release.feature`
- `@spec-0189` The release check refuses an unverified scenario, `release.feature`
- `@spec-0190` The release check passes when every scenario is verified, `release.feature`
- `@spec-0390` Release reads every spec, or the one named (2 rows), `12-several-specs/release.feature`
- `@spec-0415` A slice's release needs its own scenarios verified, and no other slice's, `13-slice-development/release.feature`
- `@spec-0267` The release check refuses a clause no audit tag names, `10-regulated-profile/release.feature`
- `@spec-0278` The release check refuses a controlled document with no id, `10-regulated-profile/release.feature`

8 scenarios: 6 verified in cycle 6, spec-0267 and spec-0278 pending until
cycle 10.

## Components and interfaces it touches

verification/release (`verification-release.run`, its `clauses-stage`
import), verification/status (`derive`, `deliver`).

## Done when

verification/release's unit tests cover each status refused, a pass, two
specs, a slice, and, with a stand-in clauses stage, a regulated project's
gap, a document with no id, and the stage not built; `wasm/test.sh` writes a
passing result for each scenario verified in cycle 6; earlier tickets'
results unchanged.
