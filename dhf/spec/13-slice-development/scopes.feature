# Component Scopes, bounded context regeneration of the rdm-spec container: dhf/spec/model.edn.
# Slice 13-slice-development (ADR 0004, ADR 0005).

@UN-006
Feature: Scopes
  Makes a touched slice a scope, whole.

  # spec-30, from cycle 23: spec-29's several spec folders were one rule in
  # parser.feature but reached eight components more; the scopes named six
  # and the integrator widened them by hand (dhf/cycles/23/cycle.edn). The
  # spec owner chose Löyly's slice (loyly:README.md@17d68d2, "How to read the
  # repo", layer 4): a piece of behaviour, planned, and rebuilt whole.
  Rule: A change rebuilds every slice it touches, whole

    @spec-0398 @e2e
    Scenario: A change to any file of a slice makes the slice a scope, with every component it reaches
      Given a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which only dhf/spec/12-several-specs/plan.md changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists the scope {:slice "12-several-specs" :components [:spec/parser :spec/stamper :measurement/checklist]}, its components in the order a builder builds them

    @spec-0399 @e2e
    Scenario: Two slices touched are two scopes, a component they share listed in the first
      Given a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which a feature file of 11-project-in-a-folder and one of 12-several-specs changed, both slices reaching :verification/verify
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists both slices as scopes, slices in the order of their folders' names, and :verification/verify in the first only

    # spec-33, from spec-32's scopes under cycle 24's release: the slices
    # 11-project-in-a-folder and 12-several-specs were listed reaching no
    # component, and 08-plugins without verify: a component was found by its
    # one spec path in the model, never by the other feature files named for
    # it. Cycle 24's graders passed spec-0398 on fixtures whose components had
    # one feature file each.
    @spec-0416 @e2e
    Scenario: A component with feature files in two slices is reached by both
      Given a configuration with :spec-layout :slices; :verification/verify, whose spec path in the model is 06-regenerate/verify, with feature files 06-regenerate/verify.feature and 12-several-specs/verify.feature; and the cuts spec-3 and spec-4, between which only 12-several-specs/verify.feature changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists the scope {:slice "12-several-specs" :components [...]} whose components hold :verification/verify
      And a slice holding feature files for components the model's spec paths place elsewhere reaches every one of them
