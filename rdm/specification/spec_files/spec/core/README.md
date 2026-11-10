# Core

Slice 01 (`.pi/plan/01-core`). Rename the context, or add one directory per bounded
context with its own slice link in `.pi/plan/`.

- `spec.feature`: the accepted scenarios, each stamped `@item-01-SSS`: the requirements.
- `drafts.feature`: scenarios not yet accepted (not read by RDM).
- `allium/`: entities, rules, actors and surfaces; a surface is a user need's model.
- `event-model/area.edn`: the screens (one per user need, linked to its surface), commands and events.
