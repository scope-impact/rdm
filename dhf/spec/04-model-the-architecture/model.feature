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
  # spec-30, from cycle 23: spec-29's several spec folders were one rule in
  # parser.feature but reached eight components more; the scopes named six
  # and the integrator widened them by hand (dhf/cycles/23/cycle.edn). The
  # spec owner chose Löyly's slice (loyly:README.md@17d68d2, "How to read the
  # repo", layer 4): a piece of behaviour, planned, and rebuilt whole.
  Rule: Under the slices layout, the spec is grouped by slice, then by component

    @spec-0392 @e2e
    Scenario: A slice is a folder of the spec, holding its plan and one feature file per component it reaches
      Given a configuration with :spec-layout :slices, and dhf/spec/12-several-specs/ holding plan.md, parser.feature, stamper.feature and checklist.feature
      When "rdm-spec check" is run
      Then the slice 12-several-specs reaches the components :spec/parser, :spec/stamper and :measurement/checklist, each found by its name in the model
      And a component owns every scenario of the feature files named for it, in every slice

    @spec-0393 @e2e
    Scenario Outline: A slice the model cannot read apart is a finding
      Given a configuration with :spec-layout :slices, and <holds>
      When "rdm-spec check" is run
      Then it exits 1 with the finding <rule>, naming <names>

      Examples:
        | holds                                                                  | rule                    | names                        |
        | dhf/spec/12-several-specs/parsr.feature, a name no component carries   | slice-component-unknown | the file                     |
        | dhf/spec/parser.feature, a feature file outside every slice            | slice-missing           | the file                     |
        | two components of the model named "check", in two contexts             | component-name-shared   | both components              |
        | a component of the model that no slice's feature file is named for     | component-unspecified   | the component                |

    @spec-0394 @e2e
    Scenario: A configuration with no :spec-layout reads the spec by context, as before
      Given a configuration with no :spec-layout, and dhf/spec/spec/parser.feature named by the component's spec path
      When "rdm-spec check" is run
      Then the component :spec/parser owns that file, as the model's spec path says
