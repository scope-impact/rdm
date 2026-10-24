# Component Model, container architecture: dhf/spec/model.edn.

@UN-005
Feature: Model
  Reads model.edn and the event catalogue: the one source every view, every
  lint rule and every component's ownership of the spec is read from.

  Rule: The model is read whole or not at all

    @spec-0071 @e2e
    Scenario: Every element is read with its kind and where it sits
      Given a model with one person, one external system, two containers and three components
      When the model is read
      Then each element is read with its id, its C4 kind and the container a component sits in

    @spec-0072 @e2e
    Scenario: A model that is not EDN is refused with its line
      Given a model file with an unclosed map on line 12
      When the model is read
      Then the reading fails and names the file and line 12

    @spec-0073 @e2e
    Scenario: A key the model's shape does not declare is refused
      Given a container carrying the key :owner, which the model's shape does not declare
      When the model is read
      Then the reading fails and names the container and the key

    @spec-0074 @e2e
    Scenario: An id used twice anywhere in the model is refused
      Given a container and a component both with the id :spec
      When the model is read
      Then the reading fails and names the id and both elements

    @spec-0075 @e2e
    Scenario: A relationship naming an element that does not exist is refused
      Given a relationship from :spec to :archive, and no element :archive
      When the model is read
      Then the reading fails and names the relationship and :archive

  Rule: A component's feature file is found from the model, never guessed

    @spec-0076 @e2e
    Scenario: A component's spec path names its feature file
      Given the component :spec/parser with the spec path "spec/parser"
      When the model is read
      Then the component owns the feature file dhf/spec/spec/parser.feature

  # A hash of the text would mark every view stale after a reformat that
  # changed nothing, and a gate that cries wolf is a gate that gets skipped.
  Rule: The model's hash follows its content, not its formatting

    @spec-0077 @e2e
    Scenario: Reformatting the model leaves its hash unchanged
      Given a model and its hash
      When the model's comments and whitespace are changed and nothing else
      Then the model's hash is unchanged

    @spec-0078 @e2e
    Scenario: Changing any value changes the model's hash
      Given a model and its hash
      When one element's description is changed
      Then the model's hash changes
