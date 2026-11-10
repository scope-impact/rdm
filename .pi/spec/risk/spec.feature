Feature: Risk
  RDM's own requirements in this context: each stamped scenario is one requirement,
  verified by the acceptance tests that claim it with @allure.tag("item-NN-SSS").

  @allium-guarantee.RiskRegister
  @item-02-001
  Scenario: RDM shall read the risk register from the frontmatter of kind: risk documents — for each risk its id, category (safety or security), STRIDE category for a security risk, linked risks, hazard, situation, harm, severity, probability, controls (requirement ids, item-NN-SSS), residual severity and probability (severity defaults to the initial one), acceptance (who and why) and status (proposed or approved; the document's when the risk gives none) — and evaluate each risk only against a risk policy the project declares (severities, probabilities, a level for each pair, and for each level whether it is acceptable, acceptable only with a recorded justification, or unacceptable), refusing a policy that does not give each pair one level and each level one of those, or a second declared policy, and shipping no default policy.

  @allium-guarantee.RiskRegister @allium-guarantee.ReleaseBlocked
  @item-02-002
  Scenario: The release gate shall block when the register has risks and no risk policy is declared or the declared policy is malformed, and on a risk document (other than one that only declares the policy) whose risks are not a list of risks, a risk id declared twice or missing, an empty hazard, situation or harm, a missing or unknown category, a security risk without a STRIDE category, a link to an undeclared risk, a severity or probability the policy does not define, a recorded level other than the policy's, a control that is not a requirement of the spec, or controls with no residual score.

  @allium-guarantee.RiskRegister @allium-guarantee.ReleaseBlocked
  @item-02-003
  Scenario: The release gate shall block a risk whose residual is not evaluated because a control has no passing test, whose residual — the initial risk when nothing controls it — the policy calls unacceptable, or that the policy accepts only with justification and no acceptance saying who accepted it and why; a risk whose status is neither proposed nor approved shall block, and a proposed risk or policy — one with no status — shall be a warning.
