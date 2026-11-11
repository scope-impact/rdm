Feature: Core
  The accepted scenarios of this context. Each is stamped with an item id
  (item-NN-SSS, NN this context's slice in .pi/plan) that is never renumbered
  or reused; each is a requirement, verified by the acceptance tests whose
  Allure results carry its id as a tag label. A scenario not yet accepted
  stays in drafts.feature, which RDM does not read.

  # Accept a draft by moving it here under a Rule, with the Allium rule or
  # guarantee it covers as a tag and its id last, for example:
  #
  #   Rule: <the rule, as a statement>
  #
  #     # core.allium, rule <Rule>
  #     @allium-rule-success.<Rule>
  #     @item-01-001
  #     Scenario: <a short, concrete title>
  #       Given ...
  #       When ...
  #       Then ...
