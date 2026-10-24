# Component Views, bounded context architecture of the rdm-spec container: dhf/spec/model.edn.

@UN-005
Feature: Views
  Generates every view from the one model and keeps them current.

  Rule: The C4 model is one source and every view is generated from it

    @spec-0017 @e2e
    Scenario: Every view in the model is generated and rendered
      Given a model with a context view, a container view and two component views
      When "rdm-spec views" is run
      Then each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names
      And each output carries the hash of the model it came from

    @spec-0018 @e2e
    Scenario: A view older than the model fails the check
      Given a rendered view whose recorded hash differs from the model's
      When "rdm-spec check" is run
      Then it fails and names the stale view

  Rule: Each level of view holds what C4 says it holds

    @spec-0198 @contract
    Scenario Outline: A view of a level holds exactly its elements
      Given a model with a view of level <level>
      When "rdm-spec views" is run
      Then the view holds <elements>

      Examples:
        | level     | elements                                                                                              |
        | context   | the system, every person and every external system, and the relationships between them               |
        | container | every container, every person and every external system, and the relationships between them        |
        | component | one context's components inside its boundary, and the elements outside it they relate to, drawn as external |
        | dynamic   | the elements its steps name, and its steps in order                                                   |
