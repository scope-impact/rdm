# Component Views, container architecture: dhf/spec/model.edn.

@UN-005
Feature: Views
  Generates every view from the one model and keeps them current.

  Rule: The C4 model is one source and every view is generated from it

    @spec-0017 @e2e
    Scenario: Every view in the model is generated and rendered
      Given a model with a context view, a container view and two component views
      When the views are generated
      Then each view is written as Mermaid C4 and rendered to SVG
      And each output carries the hash of the model it came from

    @spec-0018 @e2e
    Scenario: A view older than the model fails the gate
      Given a rendered view whose recorded hash differs from the model's
      When the gate runs
      Then it fails and names the stale view
