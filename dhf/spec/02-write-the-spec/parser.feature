@UN-001 @UN-002
Feature: Parser
  Reads the declared Gherkin subset and refuses anything outside it.

  Rule: The spec is read at a revision

    @spec-0220 @contract
    Scenario: A task that names a cut reads the spec as it was at that cut
      Given dhf/cycles/03/cycle.edn names the cut spec-3 and the scope :project
      And the spec at spec-3 holds 12 stamped scenarios and the working tree's spec holds 13
      When "rdm-spec checklist 03" is run
      Then the checklist holds the 12 scenarios of spec-3

  Rule: The spec is written in a declared subset of Gherkin

    @spec-0051 @e2e
    Scenario: A feature file using every supported construct passes the check
      Given a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables
      When "rdm-spec check" is run
      Then it passes

    @spec-0052 @e2e
    Scenario: An unknown keyword is refused with its line
      Given a feature file containing a line starting with "Scenario Template:"
      When "rdm-spec check" is run
      Then it fails and names the file, the line number and the keyword

    @spec-0053 @e2e
    Scenario: A feature file holds exactly one feature
      Given a feature file with two Feature lines
      When "rdm-spec check" is run
      Then it fails and names the file and the line of the second Feature
