# Component Render check, container architecture: dhf/spec/model.edn.

@UN-005
Feature: Render check
  Fails a rendered view whose layout cannot be read.

  Rule: The C4 model is one source and every view is generated from it

    @spec-0016 @e2e
    Scenario: A rendered view whose layout overlaps a label with a box fails
      Given a view whose computed layout places a relationship label over an element
      When the render check runs
      Then it fails and names the view, the label and the element
