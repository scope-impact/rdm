# Ticket 04: Several specs, each read, stamped and checked on its own terms

Slice: `02-write-the-spec` (`plan.md`, Scope In: "one spec or several (until
12-several-specs holds it)"); `dhf/spec/12-several-specs/plan.md`. Regression
items: the scenarios sit in slice 12, and they are the parser's, the
stamper's and check's, so building those components builds them (ADR 0004,
ADR 0005). Builds on tickets 02 and 03.

## What it delivers

- spec/parser, `spec-parser.specs`: the configuration's `:specs`, each
  `{:name :dir :id-prefix}` a folder of its own, read for its feature files,
  `model.edn`, `events.edn`, `needs.edn`, `glossary.md`, `ids.edn` and its
  `domain.edn` where it keeps one; with no `:specs`, one spec from
  `:spec-dir` as before. A configuration it cannot read apart refused before
  anything runs (exit 2): a name declared twice, an id prefix declared twice,
  two specs in one folder, a spec whose folder does not exist (naming the
  spec and its folder). `spec-parser.parse` reads every spec, each
  scenario's need, component and context resolved within its own spec.
- spec/stamper: each spec stamped from its own ledger, with its own prefix.
- spec/check: each spec checked on its own terms, one spec's finding failing
  the check and naming that spec's file only.
- Both tasks settle the specs first (ED-03), so a refused configuration is
  reported before a stage not built.

## Scenarios it satisfies

- `@spec-0385` Several specs are the configuration's :specs, each a folder of its own, `12-several-specs/parser.feature`
- `@spec-0386` A configuration of several specs rdm-spec cannot read apart is refused before anything runs (4 rows), `12-several-specs/parser.feature`
- `@spec-0387` Each spec is checked on its own terms, and one spec's finding fails the check, `12-several-specs/check.feature`
- `@spec-0388` Each spec stamps from its own ledger, with its own prefix, `12-several-specs/stamper.feature`

4 scenarios. spec-0386 (under ED-03) and spec-0388 are verified in cycle 2;
spec-0385 and spec-0387 run check to exit 0 or 1 and are pending until cycle 7
(`pending.md`).

## Components and interfaces it touches

spec/parser (`spec-parser.specs`, `parse`), spec/stamper (`stamp`),
spec/check (`gate`, `run`).

## Done when

`wasm/test.sh` writes a passing result for spec-0388 and every row of
spec-0386, and runs spec-0385 and spec-0387 reporting each as it comes out;
spec/parser has unit tests for each refusal of `:specs` and for resolution
within one spec; earlier tickets' results unchanged.
