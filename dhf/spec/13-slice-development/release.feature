@UN-004
Feature: Release check
  Checks a release of one slice.

  Rule: A release can be asked of one slice

    @spec-0415 @e2e
    Scenario: A slice's release needs its own scenarios verified, and no other slice's
      Given a configuration with :spec-layout :slices; the slice 12-several-specs with every scenario verified by results R; and the slice 11-project-in-a-folder with one unverified
      When "rdm-spec release --results R --slice 12-several-specs" is run
      Then it passes
      And "rdm-spec release --results R" refuses, naming 11-project-in-a-folder's unverified scenario
