# Component Scoreboard, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.

@UN-008
Feature: Scoreboard
  Compares cycles honestly, for the spec owner, never the builder.

  Rule: The scoreboard compares cycles honestly

    @spec-0020 @e2e
    Scenario: Both directions are counted, never the net
      Given cycle 01 and cycle 02 scored over the same 12 items
      And 3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01
      When "rdm-spec scoreboard 01 02" is run
      Then it reports 3 got better and 1 got worse
      And it reports no net figure

    @spec-0021 @e2e
    Scenario: A moved denominator is said out loud
      Given cycle 01 scored 12 items and cycle 02 scored 15
      When "rdm-spec scoreboard 01 02" is run
      Then it compares only the 12 items both scored
      And it states that the denominator moved from 12 to 15
      And it counts the 3 new items separately

  # A partial regeneration can break what it did not rebuild; the scoreboard
  # shows that apart from what it did. A scoped cycle's checklist holds only
  # its scope, so the rest is compared only where both cycles scored it, as a
  # project cycle does (cycle 02, dhf/cycles/02/proposed-spec-changes.md, A1).
  Rule: A scoped cycle is reported target first

    # spec-0172 (a scoped cycle that scored every scenario) is retired: under
    # the scope rule no such cycle can exist (cycle 03, A1).
    @spec-0225 @e2e
    Scenario: A scoped cycle is compared over its scope only
      Given cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's
      When "rdm-spec scoreboard 01 02" is run
      Then it reports better and worse for verification's scenarios
      And it states that the rest was not scored in cycle 02

  # Before any rdm-spec release exists, the first build is ratified by a reviewed
  # bootstrap cycle (ADR 0002). It says so, and nothing is measured against it.
  Rule: A bootstrap cycle is never a baseline

    @spec-0192 @e2e
    Scenario: The scoreboard marks a bootstrap cycle and compares nothing against it
      Given cycle 00's cycle.edn holds :bootstrap true
      When "rdm-spec scoreboard 00 01" is run
      Then it marks cycle 00 as bootstrap
      And it reports no better or worse count against it

  # The scoreboard's result was pinned only by the oracle's goldens, which no
  # builder sees, and the first rebuild of the scoreboard since they were
  # recorded drifted from them in 25 places (dhf/cycles/11/proposed-spec-changes.md,
  # 1). The shape is the spec's, as the goldens record it.
  Rule: The scoreboard's result has one shape

    @spec-0283 @contract
    Scenario: Two cycles of the whole project give one comparison
      Given cycle 01 and cycle 02 each scored 5 items of the whole project, 1 better and 1 worse
      When "rdm-spec scoreboard 01 02" is run
      Then standard output is exactly:
        """
        {:scoreboard {:cycles ["01" "02"]
                      :scored {"01" 5 "02" 5}
                      :compared 5
                      :better 1 :worse 1
                      :better-items ["spec-0002"] :worse-items ["spec-0001"]
                      :only-in {"01" [] "02" []}
                      :statements []}}
        """

    @spec-0284 @contract
    Scenario: A scoped cycle gives its target first and the rest apart
      Given cycle 02 scored 5 items of the whole project and cycle 03, scoped to the component shop/till, scored 2 of them, 1 better
      When "rdm-spec scoreboard 02 03" is run
      Then standard output is exactly:
        """
        {:scoreboard {:cycles ["02" "03"]
                      :target {:scope {:component :shop/till} :cycle "03"
                               :compared 2 :better 1 :worse 0
                               :better-items ["spec-0005"] :worse-items []}
                      :rest {:compared 0 :better 0 :worse 0
                             :better-items [] :worse-items []
                             :not-scored {"03" 3}}
                      :statements ["cycle 03 is scoped to {:component :shop/till}: its scope is reported first"
                                   "the rest was not scored in cycle 03: 3 items of cycle 02 outside the scope are not compared"]}}
        """
