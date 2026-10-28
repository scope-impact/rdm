@UN-005
Feature: Lint
  Holds the model to the C4 skill's checklist and to the bounded-context rules.

  Rule: The C4 model passes the C4 lint

    @spec-0001 @e2e
    Scenario: A view with no description fails the lint
      Given a view with no description
      When "rdm-spec check" is run
      Then it fails rule c4-scope and names the view

    @spec-0002 @e2e
    Scenario: An element with no description fails the lint
      Given a container with no description
      When "rdm-spec check" is run
      Then it fails rule c4-element-described and names the container

    @spec-0003 @e2e
    Scenario: An unlabelled relationship fails the lint
      Given a relationship with no description
      When "rdm-spec check" is run
      Then it fails rule c4-arrow-labelled and names both ends

    @spec-0005 @e2e
    Scenario: A context view with no person fails the lint
      Given a context view that includes no person
      When "rdm-spec check" is run
      Then it fails rule c4-context-actors and names the view

    @spec-0006 @e2e
    Scenario: A container that neither runs nor stores fails the lint
      Given a container declared with kind library
      When "rdm-spec check" is run
      Then it fails rule c4-container-runs and names the container

    @spec-0007 @e2e
    Scenario: A relationship that publishes an unknown event fails the lint
      Given a relationship that publishes the event SpecCut
      And SpecCut is not in the event catalogue
      When "rdm-spec check" is run
      Then it fails rule c4-events-named and names the relationship and the event

    @spec-0008 @e2e
    Scenario: A view with more elements than the configured limit warns
      Given the configured view limit is 20 elements
      And a view showing 23 elements
      When "rdm-spec check" is run
      Then it warns under rule c4-view-size and names the view and both counts

    @spec-0009 @e2e
    Scenario: A person or system description longer than the configured limit fails the lint
      Given the configured description limit is 45 characters
      And a person whose description is 60 characters
      When "rdm-spec check" is run
      Then it fails rule c4-description-length and names the person and both lengths

    @spec-0010 @e2e
    Scenario: The lint prints the checklist items no machine can judge
      When "rdm-spec check" is run
      Then its result's :lint :for-a-person is exactly ["Does each view serve its audience: can its reader answer the question the view is for?" "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?" "Was each render looked at by a person, and did nothing overlap or hide a name?"]

    @spec-0430 @contract
    Scenario: The lint's result counts its findings and its warnings
      Given the configured view limit is 20 elements
      And a relationship with no description, and a view showing 23 elements
      When "rdm-spec check" is run
      Then its result's :lint is {:for-a-person [ITEM ...] :findings 1 :warnings 1}, :findings counting every finding of the model's reading and its lint, :warnings every warning

  Rule: Bounded contexts are modules of one container, and the model owns the spec

    @spec-0012 @e2e
    Scenario: A bounded context with no component view fails the lint
      Given a bounded context with components and no component view
      When "rdm-spec check" is run
      Then it fails rule a-component-view and names the context

    @spec-0013 @e2e
    Scenario: A component that owns no feature file fails the lint
      Given a component in the model with no feature file at its path in the spec
      When "rdm-spec check" is run
      Then it fails rule a-scenario-owner and names the component

    @spec-0014 @e2e
    Scenario: A feature file that belongs to no component fails the lint
      Given a feature file in the spec at a path no component of the model names
      When "rdm-spec check" is run
      Then it fails rule a-scenario-owner and names the file

    @spec-0015 @e2e
    Scenario: Components of different contexts relate only through an event
      Given a relationship from a component of one bounded context to a component of another, naming no event
      When "rdm-spec check" is run
      Then it fails rule a-contract-only and names the relationship

    @spec-0339 @e2e
    Scenario: A project that lets its contexts relate by call is not held to events only
      Given a configuration whose :c4 holds :contexts-relate-by [:events :calls], and a relationship from a component of one bounded context to a component of another, naming no event
      When "rdm-spec check" is run
      Then it passes rule a-contract-only
