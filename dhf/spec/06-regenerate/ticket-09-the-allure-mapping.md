# Ticket 09: Every result held to the project's Allure mapping

Slice: `06-regenerate` (`plan.md`, Scope In: "Verify: ... the project's
checks ... run the same way and recorded"); `verify.feature`, Rule "The
tests check holds every result to the project's Allure mapping"; the
glossary's "Allure mapping" and "Allure result";
`05-verify-by-results/runner.feature`, the elements a result carries.
Builds on ticket 07.

## What it delivers

verification/verify's mapping, held by the tests check (ED-08):

- The configuration's `:allure` at the cut, else rdm-spec's default
  mapping, `:links`' N the configuration's `:needs`, `dhf/spec/needs.edn`
  when it names none (spec-0333); a mapping naming an element that is not
  the spec's, one that is not an Allure result's, or no label "spec",
  refused before any check runs, exit 2, saying why (spec-0332; G-32).
- Each result of a scenario the cycle requires held, on its text (the
  reader's `json`), to the scenario as the parser reads it at the cut:
  its steps (the background's first, an outline row's values in place) one
  per line, in order, the Gherkin's words (`steps-differ-from-scenario`);
  each mapped element (`:story` its rule, `:epic` its context, the labels,
  the need links, every need it traces to, inherited ones too) as the
  mapping says (`mapping-differs: ELEMENT`); and each attachment `:evidence`
  requires for its level, on the result or any step (`evidence-missing:
  NAME`). Each a finding naming the scenario, the rule and the result's
  file, the check `:failed`, exit 1 (spec-0331; G-33 for the line's file).
- A planned scenario's result held to its spec label only (spec-0347).
- The tag labels a result carries held as the runner writes them (the
  feature's, the rule's, then its own, each once): slice 05's spec-0403,
  whose last Then is this check, is unblocked by this ticket.

## Scenarios it satisfies

- `@spec-0331` A result that breaks the mapping fails the tests check, naming the scenario and the element (6 rows), `verify.feature`
- `@spec-0347` A planned scenario's result is held to its spec label only, `verify.feature`
- `@spec-0332` A mapping verify cannot read is refused before anything runs (3 rows), `verify.feature`
- `@spec-0333` A configuration with no :allure is held to rdm-spec's default mapping, `verify.feature`

4 scenarios, verified in cycle 7. This ticket also unblocks slice 05's
spec-0403 (`pending.md`).

## Components and interfaces it touches

verification/verify (the mapping), spec/parser (`parse`), verification/results
(`read`'s `json`).

## Done when

verification/verify's unit tests cover each row of spec-0331 and spec-0332,
the default mapping, a background's steps and an outline row's; a result
the runner (slice 05) wrote for a scenario passes the mapping; `wasm/test.sh`
writes a passing result for each scenario above; earlier tickets' results
unchanged.
