# Ticket 06: The scoreboard: better and worse, never netted

Slice: `07-measure` (`plan.md`, Scope In: "The scoreboard: better and
worse, never netted; a moved denominator said aloud"); `scoreboard.feature`,
every Rule; the glossary's "Scoreboard". Builds on ticket 05.

## What it delivers

measurement/scoreboard's `run`, "rdm-spec scoreboard CYCLE CYCLE", reading
each cycle's cycle.edn with the checklist's `read-cycle` and its score.edn
with the scorer's `filed` (ED-07); a cycle with neither, exit 2 naming it
(G-25):

- Two cycles not scoped: compared over the items both scored, an item
  better when it failed in the first and passed in the second, worse the
  other way, an ungraded item a fail; `:scored` per cycle, `:compared`,
  `:better`, `:worse`, `:better-items` and `:worse-items` (sorted by id,
  G-28), `:only-in` per cycle (the items only that cycle scored),
  `:statements`, and never a net (spec-0020, spec-0283); a moved
  denominator stated, saying from what to what, the new items counted
  apart in `:only-in` (spec-0021; G-26).
- A scoped second cycle: `:target` first, `{:scope :cycle :compared
  :better :worse :better-items :worse-items}` over its scope's items, then
  `:rest`, the items both scored outside the scope, with `:not-scored`
  counting the first cycle's items the scoped one did not score, and the
  two statements of spec-0284, worded as written there (spec-0225,
  spec-0284; G-27).
- A bootstrap cycle (`:bootstrap true`) marked as such, and no better or
  worse counted against it (spec-0192; G-24).
- Writes nothing.

## Scenarios it satisfies

- `@spec-0020` Both directions are counted, never the net, `scoreboard.feature`
- `@spec-0021` A moved denominator is said out loud, `scoreboard.feature`
- `@spec-0225` A scoped cycle is compared over its scope only, `scoreboard.feature`
- `@spec-0192` The scoreboard marks a bootstrap cycle and compares nothing against it, `scoreboard.feature`
- `@spec-0283` Two cycles of the whole project give one comparison, `scoreboard.feature`
- `@spec-0284` A scoped cycle gives its target first and the rest apart, `scoreboard.feature`

6 scenarios, verified in cycle 8.

## Components and interfaces it touches

measurement/scoreboard (`run`), measurement/scorer (`filed`),
measurement/checklist (`read-cycle`).

## Done when

measurement/scoreboard's unit tests cover spec-0283's and spec-0284's
output exactly, a moved denominator, a bootstrap first cycle, an ungraded
item, and a missing score; `wasm/test.sh` writes a passing result for each
scenario above; earlier tickets' results unchanged.
