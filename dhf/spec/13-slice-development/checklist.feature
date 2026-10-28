# Component Checklist, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
# Slice 13-slice-development (ADR 0004, ADR 0005).

@UN-008
Feature: Checklist
  Grades a slice whole.

  # spec-30, from cycle 23: spec-29's several spec folders were one rule in
  # parser.feature but reached eight components more; the scopes named six
  # and the integrator widened them by hand (dhf/cycles/23/cycle.edn). The
  # spec owner chose Löyly's slice (loyly:README.md@17d68d2, "How to read the
  # repo", layer 4): a piece of behaviour, planned, and rebuilt whole.
  Rule: A slice is graded whole

    @spec-0395 @e2e
    Scenario: A slice scope's checklist holds every scenario of every component the slice reaches
      Given a configuration with :spec-layout :slices; the slice 12-several-specs reaching :spec/parser and :measurement/checklist; :spec/parser with 2 stamped scenarios in 12-several-specs and 3 in 02-write-the-spec; and dhf/cycles/03/cycle.edn whose :scope is [{:slice "12-several-specs"}]
      When "rdm-spec checklist 03" is run
      Then it holds one item for each of the 5 scenarios of :spec/parser and each of :measurement/checklist's, wherever their slice, each item naming its slice and its component
      And verify requires a result for each of them, as for any scope

  # spec-33, ADR 0005: the slice is the unit of work (Löyly,
  # loyly:docs/wow/way-of-working.md@17d68d2, sections 1, 2 and 6).
  Rule: A slice's checklist tells its own scenarios from its regressions

    @spec-0414 @e2e
    Scenario: Each item of a slice cycle says whether it is the slice's own or a regression
      Given a configuration with :spec-layout :slices; the slice 12-several-specs reaching :spec/parser, with 2 of :spec/parser's stamped scenarios in 12-several-specs and 3 in 02-write-the-spec; and dhf/cycles/03/cycle.edn whose :slice is "12-several-specs"
      When "rdm-spec checklist 03" is run
      Then the 2 items of 12-several-specs carry :role "slice" and the 3 of 02-write-the-spec carry :role "regression", each naming its slice
