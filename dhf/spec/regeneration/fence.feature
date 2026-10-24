# Component Fence, container regeneration: dhf/spec/model.edn.

@UN-006
Feature: Fence
  Builds a clean room from a cut and proves every fenced path gone.

  # Incident: a builder that can read last cycle's scores can aim at the
  # checklist instead of the spec. Regeneration reads a cut, nothing else.
  Rule: A cut is a tagged merge, and regeneration reads only a cut

    @spec-0037 @e2e
    Scenario: The fence refuses a source that is not a cut
      Given a commit that carries no spec-N tag
      When the fence is asked to build a clean room from it
      Then it refuses and says regeneration reads only a cut

  # Incident: a weaker fence probe once passed while the previous implementation
  # was still reachable from an older commit.
  Rule: The fence is physical and proved

    @spec-0038 @e2e
    Scenario: The clean room holds no fenced path, in the tree or in history
      Given the configuration fences the implementation and every measurement
      When the fence builds a clean room from cut spec-3
      Then the clean room has a single root commit
      And no fenced path exists in its working tree
      And no object reachable in its repository belongs to a fenced path

    @spec-0039 @e2e
    Scenario: A fenced path that matches nothing is a hard failure
      Given the configuration fences a path that does not exist at the cut
      When the fence builds a clean room
      Then it fails and names the path

    @spec-0040 @e2e
    Scenario: The clean room keeps the spec, the step definitions and the harness configuration
      When the fence builds a clean room from a cut
      Then the spec, the step definitions and the harness configuration are present and unchanged

  Rule: The scoreboard compares cycles honestly

    @spec-0041 @e2e
    Scenario: The scoreboard is for the spec owner, never for the builder
      When the fence builds a clean room from a cut
      Then no scoreboard and no cycle score is present in it

  # Decided by the event storm's hotspot H14 (dhf/storm/2026-10-24-rdm.md). The
  # fence proves files; it cannot see a network. So the clean room carries what
  # the builder may read of each source, and refuses to be handed out where the
  # builder could fetch the rest.
  Rule: The fence covers the sources too

    @spec-0139 @e2e
    Scenario: The clean room carries each source without its fenced paths, in tree or history
      Given the sources index fences "harness/fence.sh" in loyly
      And a local checkout of skallinen/loyly at 17d68d2
      When the fence builds a clean room from a cut
      Then the clean room holds loyly at 17d68d2 as a single root commit
      And harness/fence.sh is in neither its working tree nor any object reachable in it
      And loyly's README.md is present and unchanged

    @spec-0140 @e2e
    Scenario: A source with no local checkout is refused, never fetched
      Given no local checkout of innolitics/rdm is configured
      When the fence builds a clean room from a cut
      Then it refuses and names innolitics-rdm and the missing checkout

    @spec-0141 @e2e
    Scenario: The fence refuses a clean room configured to reach the network
      Given the clean room is configured with network access
      When the fence builds a clean room from a cut
      Then it refuses and says a clean room with a network can fetch what the fence removed
