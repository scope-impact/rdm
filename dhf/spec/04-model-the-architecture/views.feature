@UN-005
Feature: Views
  Generates every view from the one model and keeps them current.

  Rule: The C4 model is one source and every view is generated from it

    @spec-0017 @e2e
    Scenario: Every view in the model is generated and rendered
      Given a model with a context view, a container view and two component views
      When "rdm-spec views" is run
      Then each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names
      And each output's first line is a comment holding "model-sha256: " and the model's hash

    @spec-0018 @e2e
    Scenario: A view older than the model fails the check
      Given a rendered view whose recorded hash differs from the model's
      When "rdm-spec check" is run
      Then it fails and names the stale view

    @spec-0434 @contract
    Scenario: The views task's result has one shape
      Given a model with a context view keyed "context" and a container view keyed "containers", in that order
      When "rdm-spec views" is run
      Then its standard output, read as EDN, is {:views {:wrote [PATH ...]}}, every file it wrote, relative to the project's root
      And the paths are the views in the model's order, each view's diagram source before its renders

    @spec-0435 @contract
    Scenario: A stale view's finding names its rule
      Given a rendered view whose recorded hash differs from the model's
      When "rdm-spec check" is run
      Then it exits 1, and the finding's line names the rule view-stale

    @spec-0436 @e2e
    Scenario: Each of several specs' views is written to a folder named for its spec
      Given a configuration whose :specs is [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/infra" :id-prefix "infra"}], each spec's model holding a view keyed "context", and :views-dir "dhf/views"
      When "rdm-spec views" is run
      Then auth's view is written to dhf/views/auth/ and infra's to dhf/views/infra/, and nothing to dhf/views/ itself

    @spec-0437 @e2e
    Scenario: A view that cannot be rendered leaves every view as it was
      Given views generated from the model, and a model changed since
      And the renderer dhf/harness/stack.md names cannot be started
      When "rdm-spec views" is run
      Then it exits 2 and says the renderer cannot be started
      And no file under the views directory changes

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
