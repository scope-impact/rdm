# Risk

Slice 02. The risk register kept in the record, each risk scored only against the risk
policy the project declares, and the residual decision the release gate holds a release to.

- `spec.feature`: the accepted scenarios (`@item-02-001` to `@item-02-003`).
- `allium/risk.allium`: entities Risk and RiskPolicy, rule ReadRiskPolicy; surface
  RiskRegisterDesk, the need this context is home to, with its four guarantees
  (RisksProjectedWithShapes is the graph's item-06-008).
- `event-model/area.edn`: screen `risk-register`.
- `event-model/register.edn`: the register as a read model and its query.
