# The harness's own contract. Rules only, until the spec owner accepts them;
# scenarios follow, one per pinned output (docs/decisions/0001, "every output
# something reads is pinned"). Every rule cites README.md or an invariant.
#
# Every task prints one EDN map on stdout and exits 0 (passed), 1 (refused: a
# finding about the project) or 2 (cannot run: the harness's own defect or a
# missing input). Nothing else goes to stdout.

Feature: The harness: storm, model, cut, fence, regenerate, gate, measure

  # --- the front of the chain (README, "the storm is forced") ---

  Rule: A project keeps its storm as data, and every element cites a source
    # spec/storm.edn: actors, commands, events, policies, aggregates, read
    # models, hotspots. A source is <name>:<path>@<commit> under raw/.

  Rule: No storm, no cut (INV-1)
    # check refuses a missing storm, an unsourced element, an open hotspot;
    # cut refuses whatever check refuses.

  Rule: The model is derived from the storm, never drawn (INV-1)
    # model.edn: containers, components, relationships. Every component holds
    # at least one aggregate or read model of the storm; every relationship
    # between contexts names a storm event; a component in no container is
    # refused.

  Rule: The spec never names the stack (INV-2)

  # --- the spec and its ids (INV-6) ---

  Rule: Every scenario belongs to one component, and every component owns at least one
    # <component>.feature; a feature file naming no component of the model is
    # refused.

  Rule: The stamper gives every scenario a stable id, once, never re-derived
    # @item-NNN; stamping twice changes nothing; rewording changes nothing; a
    # deleted id is never reused.

  Rule: The checklist is derived, one item per stamped scenario, grouped by component
    # An unstamped scenario stops the checklist rather than shrinking it. An
    # empty spec stops it rather than producing an empty one.

  # --- the cut and its scope (INV-4) ---

  Rule: A cut is a tag on a merged spec that check passed
    # spec-N; a regeneration reads the cut and nothing else.

  Rule: A scope is computed from two cuts, never typed
    # The components whose scenarios changed, closed over the events they
    # publish and consume, up to their container. The output is the only
    # input fence accepts.

  Rule: Every Nth cycle is a whole-container cycle, and the scoreboard says when one is due

  # --- the clean room (README, "regenerate, not copy") ---

  Rule: The fence is physical and proved
    # A clone of the cut with every implementation of the scope and every
    # measurement removed from the tree and the history; the proof is a
    # listing of what remains, and a grep that finds nothing of what was
    # removed.

  Rule: One builder per cycle, in the room, reading the cut's plan first

  # --- the gate (INV-5) ---

  Rule: "Done" is ratified by verify, which the builder does not control
    # Boots the product in the room, runs its suite twice, inside a named
    # budget, reports the margin; writes verified.edn with the room's tree
    # hash. A suite that did not run is a refusal, never a pass.

  # --- the instrument (INV-6) ---

  Rule: Score files verdicts against the checklist and refuses a cycle verify did not ratify
    # An unreached item is a fail with the reason; zero verdicts is "not
    # graded"; a verdict for an unknown id is fatal; a verdict that is neither
    # pass nor fail is fatal.

  Rule: The scoreboard shows both directions on the shared items, never a net
    # A moved denominator is restated out loud; new items are counted apart.

  Rule: The spec owner's decisions are a person's (INV-10)
    # cycle.edn names who built, who graded, who accepted; score refuses a
    # record whose spec owner is not a human.

  # --- the occasion (INV-9) and the held-still list (INV-8) ---

  Rule: Check runs every judge and refuses on the first red; its silence is never a pass

  Rule: Every file under harness/ is declared shared, per-project or local

  # --- pluggable, in one way (README, "pluggable") ---

  Rule: A plugin is a command declared in the configuration, answering check or verify
    # It receives the project's paths as arguments and speaks the same EDN and
    # exit codes. The harness never reads a plugin's code.

  Rule: An opinion is a refusal, not a flag (INV-7)
    # The configuration has a closed shape; an unknown key is refused.
