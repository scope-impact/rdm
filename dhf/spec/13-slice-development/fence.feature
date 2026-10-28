@UN-006
Feature: Fence
  Removes the code of every component a slice reaches.

  Rule: A slice is fenced whole

    @spec-0397 @e2e
    Scenario: A slice scope removes the code of every component the slice reaches
      Given a configuration with :spec-layout :slices, and the slice 12-several-specs reaching :spec/parser, :spec/stamper and :measurement/checklist
      When "rdm-spec fence ROOM spec-3 --slice 12-several-specs" is run
      Then ROOM holds no code, unit test or step definition of those three components
      And it holds every other implementation file at the cut, unchanged
      And a slice the cut does not hold is refused with exit 2, naming it
