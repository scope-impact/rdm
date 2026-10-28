# Ticket 03: The spec check: ids, levels, tags, needs, sources, planned work, stack words

Slice: `02-write-the-spec` (`plan.md`, Scope In: "The spec check: one id, one
level, a need for every scenario, a source for every need, planned work, no
stack words"); the glossary's "Spec check". Builds on ticket 01.

## What it delivers

spec/check, `spec-check.gate`, the spec check stage of `rdm-spec check`,
publishing SpecGated (its findings, passed when none):

- Ids: a scenario with two ids (naming the scenario and both), an id used by
  two scenarios (naming both files and the id), a scenario with no id (its
  file and line, saying to run `rdm-spec stamp`), an id above the highest the
  ledger issued (naming it, saying the ledger never issued it).
- Levels: exactly one of `@e2e` and `@contract`; none or both a finding
  naming the scenario's id and the levels.
- Tags: a tag that is not an id, a level, a need or `@wip` a finding naming
  the scenario's id and the tag, listing the tags a scenario may carry.
- Needs: a need tag on a feature or a rule covering every scenario under it;
  a scenario tracing to no need, and a need tag naming no declared need
  (`needs.edn`), each a finding; a need citing no source a finding naming
  it.
- Planned work: `@wip` accepted.
- Stack words: each line of a spec file naming a configured stack word
  (`:stack-words`) a finding naming the file, the line and the word.

Each rule a file breaks is its own finding (`dhf/spec/README.md`). Rule
names as ED-06 settles.

## Scenarios it satisfies

- `@spec-0042` A scenario with two ids fails the spec check, `check.feature`
- `@spec-0043` An id used by two scenarios fails the spec check, `check.feature`
- `@spec-0186` A scenario with no id fails the spec check, `check.feature`
- `@spec-0234` An id the ledger never issued fails the spec check, `check.feature`
- `@spec-0044` Each level is accepted (2 rows), `check.feature`
- `@spec-0045` A scenario with no level fails the spec check, `check.feature`
- `@spec-0046` A scenario with two levels fails the spec check, `check.feature`
- `@spec-0150` A tag that is not an id, a level, a need or @wip fails the spec check, `check.feature`
- `@spec-0047` A need tag on the feature is inherited by every scenario under it, `check.feature`
- `@spec-0048` A scenario that traces to no need fails the spec check, `check.feature`
- `@spec-0049` A need tag naming no declared need fails the spec check, `check.feature`
- `@spec-0134` A user need citing no source fails the spec check, `check.feature`
- `@spec-0050` The spec check accepts a scenario tagged @wip, `check.feature`
- `@spec-0209` A spec file naming a configured stack word fails the spec check, `check.feature`

14 scenarios: 13 verified in cycle 2; spec-0047 runs trace and is pending
until cycle 6 (`pending.md`). The rule of spec-0047 (a feature's need tag
covers its scenarios) is the parser's `needs` of each scenario, unit-tested
here.

## Components and interfaces it touches

spec/check (`spec-check.gate`), spec/parser (`spec-parsed`'s needs and
tags), the event record `spec-gated`.

## Done when

spec/check's unit tests cover every rule above on `spec-parsed` values, a
passing spec giving `spec-gated` with passed true and no finding;
`wasm/test.sh` writes a passing result for each scenario above but
spec-0047, which it runs and reports as it comes out; tickets 01 and 02's results unchanged.
