# Publishing

Slice 07. The design history file compiled from the record, RDM's own user manual, and
the generated release documents.

- `spec.feature`: the accepted scenarios (`@item-07-001` to `@item-07-009`).
- `allium/publishing.allium`: rule Render; surfaces CompileDhf and UserManual, the needs
  this context is home to. `@item-07-008` and `@item-07-009` cover guarantees of the release
  context's ReleaseEvidence surface; `@item-01-001` (specification) covers CompileDhf's
  SpecReadAsTaggedGherkin.
- `event-model/area.edn`: screens `compile-dhf` and `user-manual`.
- `event-model/publishing.edn`: command `render` and its event.
