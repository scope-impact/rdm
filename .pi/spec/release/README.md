# Release

Slice 04. Whether a commit may be released, each requirement's status from executed
results, and the retained evidence of a release.

- `spec.feature`: the accepted scenarios (`@item-04-001` to `@item-04-004`).
- `allium/release.allium`: rules Verify, ReleaseGate and EvidenceBundle; surfaces
  ReleaseGateDesk, TraceVerification and ReleaseEvidence, the needs this context is home to.
- `event-model/area.edn`: screens `release-blocked`, `trace-verification` and `release-evidence`.
- `event-model/release.edn`: commands `verify`, `release-gate`, `evidence-bundle` and their events.
