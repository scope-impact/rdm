# Component Model, bounded context architecture of the rdm-spec container: dhf/spec/model.edn.
# Slice 13-slice-development (ADR 0004, ADR 0005).

@UN-005
Feature: Model
  Reads a spec grouped by slice, then by component.

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
