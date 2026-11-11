# Specification

Slice 01. How RDM reads the spec it holds a record to, how a project comes under RDM
(`rdm init`, `rdm adopt`), and how validation by a person shows at release.

- `spec.feature`: the accepted scenarios (`@item-01-001` to `@item-01-005`). Two of them
  serve needs that live elsewhere: item-01-001 (publishing's CompileDhf) and item-01-005
  (release's TraceVerification).
- `allium/scaffolding.allium`: rules Init and Adopt; entity ValidationRecord; surfaces
  ScaffoldProject, AdoptRepository and ValidationAtRelease, the needs this context is home to.
- `event-model/area.edn`: screens `scaffold-project`, `adopt-repository` and `validation-visible`.
- `event-model/scaffolding.edn`: commands `init`, `adopt`, `record-validation` and their events.
