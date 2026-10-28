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

    @spec-0431 @contract
    Scenario Outline: Each finding of the model's reading names its rule
      Given a spec in which <case>
      When "rdm-spec check" is run
      Then it exits 1, and the finding's line names the rule <rule>

      Examples:
        | case                                                | rule                  |
        | the model is not EDN                                | model-unreadable      |
        | the model carries a key its shape does not declare  | model-key-unknown     |
        | the model uses one id twice                         | model-id-duplicate    |
        | a relationship names an element that does not exist | model-element-unknown |
        | the event catalogue is not EDN                      | events-unreadable     |

    @spec-0432 @e2e
    Scenario Outline: A model read as EDN is linted beside its shape findings, and one that is not is not linted
      Given a model file <model>
      When "rdm-spec check" is run
      Then it exits 1 and <names>

      Examples:
        | model                                                                              | names                                                          |
        | in which a container carries the key :owner, and a relationship has no description | names the key :owner and fails rule c4-arrow-labelled          |
        | with an unclosed map on line 12, and views older than the model                    | names the file and line 12, and no lint rule and no stale view |

  Rule: A component's feature file is found from the model, never guessed

    @spec-0076 @e2e
    Scenario: A component's spec path names its feature file
      Given the component :spec/parser with the spec path "spec/parser"
      When "rdm-spec check" is run
      Then the component owns the feature file dhf/spec/spec/parser.feature, and "rdm-spec check" passes only when that file exists

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

    @spec-0433 @contract
    Scenario: The model's hash is the same for every build
      Given dhf/spec/model.edn holds exactly:
        """
        {:system {:id :sauna :name "Sauna" :description "Heats the löyly room"}
         :people [{:id :bather :name "Bather" :description "Bathes" :actor #{:human :agent}}]
         :containers [{:id :stove :name "Stove" :description "Heats" :technology "iron" :kind :runs
                       :contexts [{:id :heat :name "Heat" :description "Heat"
                                   :components [{:id :heat/coil :name "Coil" :description "Glows" :spec "coil"}]}]}]
         :relationships [{:from :bather :to :stove :description "Throws water on"}]
         :views [{:key "context" :level :context :description "The sauna and who uses it"}]}
        """
      When "rdm-spec views" is run
      Then each output's first line holds "model-sha256: 4b32192010444912554ec9fb27f553264e378f8780856bf50a66f4d2537c80e4"
