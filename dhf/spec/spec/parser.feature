# Component Parser, container spec: dhf/spec/model.edn.

@UN-001 @UN-002
Feature: Parser
  Reads the declared Gherkin subset and refuses anything outside it.

  # The parser is ours. A construct it does not know is refused, never skipped:
  # a scenario the parser silently drops is a requirement nothing checks.
  Rule: The spec is written in a declared subset of Gherkin

    @spec-0051 @e2e
    Scenario: The supported constructs parse
      Given a feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables
      When the parser reads it
      Then every scenario is read with its tags, steps and arguments

    @spec-0052 @e2e
    Scenario: An unknown keyword is refused with its line
      Given a feature file containing a line starting with "Scenario Template:"
      When the parser reads it
      Then it refuses and names the file, the line number and the keyword

    @spec-0053 @e2e
    Scenario: A feature file holds exactly one feature
      Given a feature file with two Feature lines
      When the parser reads it
      Then it refuses and names the file and the second Feature line
