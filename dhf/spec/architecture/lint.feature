# Component Lint, container architecture: dhf/spec/model.edn.

@UN-005
Feature: Lint
  Holds the model to the C4 skill's checklist and to one container per bounded context.

  # The rules below are the C4 skill's quality checklist where a machine can
  # judge it (scope-impact/agent-skills, skills/engineering/c4-diagrams). The
  # linter records the skill commit it was written against and warns when the
  # skill's guide has changed since, so a new checklist item is noticed.
  Rule: The C4 model passes the C4 lint

    @spec-0001 @e2e
    Scenario: A view with no title or description fails the lint
      Given a view with no description
      When the lint runs
      Then it fails rule c4-scope and names the view

    @spec-0002 @e2e
    Scenario: An element with no description fails the lint
      Given a container with no description
      When the lint runs
      Then it fails rule c4-element-described and names the container

    @spec-0003 @e2e
    Scenario: An unlabelled relationship fails the lint
      Given a relationship with no description
      When the lint runs
      Then it fails rule c4-arrow-labelled and names both ends

    @spec-0004 @e2e
    Scenario: A component view that shows another container's components fails the lint
      Given the component view of one container includes a component of another
      When the lint runs
      Then it fails rule c4-one-level and names the view and the component

    @spec-0005 @e2e
    Scenario: A context view with no person fails the lint
      Given a context view that includes no person
      When the lint runs
      Then it fails rule c4-context-actors and names the view

    @spec-0006 @e2e
    Scenario: A container that neither runs nor stores fails the lint
      Given a container declared with kind library
      When the lint runs
      Then it fails rule c4-container-runs and names the container

    @spec-0007 @e2e
    Scenario: A relationship that publishes an unknown event fails the lint
      Given a relationship that publishes the event SpecCut
      And SpecCut is not in the event catalogue
      When the lint runs
      Then it fails rule c4-events-named and names the relationship and the event

    @spec-0008 @e2e
    Scenario: A view with more elements than the configured limit warns
      Given the configured view limit is 20 elements
      And a view showing 23 elements
      When the lint runs
      Then it warns under rule c4-view-size and names the view and both counts

    # Found by the first event storm's render (dhf/storm/2026-10-24-rdm.md):
    # mmdr draws a person's or system's name over a description that wraps
    # past two lines.
    @spec-0009 @e2e
    Scenario: A person or system description longer than the configured limit fails the lint
      Given the configured description limit is 45 characters
      And a person whose description is 60 characters
      When the lint runs
      Then it fails rule c4-description-length and names the person and both lengths

    @spec-0010 @e2e
    Scenario: The lint prints the checklist items no machine can judge
      When the lint runs
      Then it lists, for a person to answer, whether the view serves its audience, whether the true coupling is visible and whether the render was looked at

  Rule: Each bounded context is one container, and the model owns the spec

    @spec-0011 @e2e
    Scenario: A bounded context with no container, or two, fails the lint
      Given two internal containers that declare the same bounded context
      When the lint runs
      Then it fails rule a-context-container and names both containers

    @spec-0012 @e2e
    Scenario: An internal container with no component view fails the lint
      Given an internal container with components and no component view
      When the lint runs
      Then it fails rule a-component-view and names the container

    @spec-0013 @e2e
    Scenario: A component that owns no feature file fails the lint
      Given a component in the model with no feature file at its path in the spec
      When the lint runs
      Then it fails rule a-scenario-owner and names the component

    @spec-0014 @e2e
    Scenario: A feature file that belongs to no component fails the lint
      Given a feature file in the spec at a path no component of the model names
      When the lint runs
      Then it fails rule a-scenario-owner and names the file

    @spec-0015 @e2e
    Scenario: Components in different containers may not relate directly
      Given a relationship from a component of one container to a component of another
      When the lint runs
      Then it fails rule a-contract-only and names the relationship

  # Checked against the regenerated code, not the model: the model says which
  # containers meet only through events, and the code has to agree.
  Rule: The code keeps the model's boundaries

    @spec-0109 @e2e
    Scenario: A namespace of one container requiring another container's namespace fails
      Given the namespace rdm-spec.measurement.scorer requires rdm-spec.verification.status
      When the lint runs over the code
      Then it fails rule a-code-contract-only and names both namespaces

    @spec-0110 @e2e
    Scenario: Any container may require the kernel
      Given the namespace rdm-spec.spec.gate requires rdm-spec.kernel.bus
      When the lint runs over the code
      Then the requirement passes

    @spec-0111 @e2e
    Scenario: A kernel namespace requiring a container's namespace fails
      Given the namespace rdm-spec.kernel.bus requires rdm-spec.spec.parser
      When the lint runs over the code
      Then it fails rule a-kernel-leaf and names both namespaces
