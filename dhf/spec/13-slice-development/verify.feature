@UN-007
Feature: Verify
  Verifies a slice on the components it reaches.

  Rule: A slice-scoped verify answers for the components the slice reaches

    @spec-0404 @e2e
    Scenario: A cycle scoped to a slice is verified on the scenarios of the components it reaches
      Given a configuration with :spec-layout :slices; the slice 12-several-specs reaching :spec/parser and :measurement/checklist; dhf/cycles/03/cycle.edn whose :scope is [{:slice "12-several-specs"}]; and a room whose tests write a passing result for every scenario of :spec/parser and :measurement/checklist, in any slice, and none for any other component
      When "rdm-spec verify ROOM 03" is run
      Then the tests check passes, counting those components' scenarios only
      And with one of :spec/parser's results missing, it fails, naming that scenario
