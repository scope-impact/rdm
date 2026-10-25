# Component Model, bounded context architecture of the rdm-spec container: dhf/spec/model.edn.

@UN-005
Feature: Model
  Reads model.edn and the event catalogue: the one source every view, every
  lint rule and every component's ownership of the spec is read from.

  Rule: The model is read whole or not at all

    @spec-0072 @e2e
    Scenario: A model that is not EDN is refused with its line
      Given a model file with an unclosed map on line 12
      When "rdm-spec check" is run
      Then it exits 1 and names the file and line 12

    @spec-0227 @e2e
    Scenario: A closing bracket with no opening one is refused with its line
      Given a model file with a "}" on line 7 that closes nothing
      When "rdm-spec check" is run
      Then it exits 1 and names the file, line 7 and the "}"

    @spec-0073 @e2e
    Scenario: A key the model's shape does not declare is refused
      Given a container carrying the key :owner, which the model's shape does not declare
      When "rdm-spec check" is run
      Then it exits 1 and names the container and the key

    @spec-0074 @e2e
    Scenario: An id used twice anywhere in the model is refused
      Given a container and a component both with the id :alpha
      When "rdm-spec check" is run
      Then it exits 1 and names the id and both elements

    @spec-0075 @e2e
    Scenario: A relationship naming an element that does not exist is refused
      Given a relationship from :spec to :archive, and no element :archive
      When "rdm-spec check" is run
      Then it exits 1 and names the relationship and :archive

  Rule: A component's feature file is found from the model, never guessed

    @spec-0076 @e2e
    Scenario: A component's spec path names its feature file
      Given the component :spec/parser with the spec path "spec/parser"
      When "rdm-spec check" is run
      Then the component owns the feature file dhf/spec/spec/parser.feature, and "rdm-spec check" passes only when that file exists

  # A hash of the text would mark every view stale after a reformat that
  # changed nothing, and a gate that cries wolf is a gate that gets skipped.
  Rule: Views go stale when the model's content changes, never its formatting

    @spec-0077 @e2e
    Scenario: Reformatting the model leaves its views fresh
      Given views generated from the model
      When the model's comments and whitespace are changed and nothing else
      And "rdm-spec check" is run
      Then it reports no stale view

    @spec-0078 @e2e
    Scenario: Changing any value makes the views stale
      Given views generated from the model
      When one element's description is changed
      And "rdm-spec check" is run
      Then it fails and names every view as stale
