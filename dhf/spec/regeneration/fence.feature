# Component Fence, container regeneration: dhf/spec/model.edn.

@UN-006
Feature: Fence
  Builds a clean room from a cut, for the whole project, one container or one
  component, and proves it holds nothing else.

  # Every prior implementation and every measurement is out of the clean room,
  # so nothing can be copied, only rebuilt (skallinen/loyly
  # harness/README.md@17d68d2, "The idea in five lines" 2).
  Rule: A clean room is built only from a cut

    @spec-0037 @e2e
    Scenario: The fence refuses a source that is not a cut
      Given a commit that carries no spec-N tag
      When "rdm-spec fence /tmp/room" is run on it
      Then it exits 2 and says regeneration reads only a cut

  # Incident: a weaker fence probe once passed while the previous implementation
  # was still reachable from an older commit (skallinen/loyly
  # harness/README.md@17d68d2, "The failure modes this shape was learned from").
  # A clean room is an allow-list, so nothing reaches it by being forgotten.
  Rule: A clean room holds what is allowed, and nothing else

    @spec-0038 @e2e
    Scenario: The clean room holds only the allowed paths, as one root commit
      Given the configuration allows dhf/spec, dhf/harness, dhf/raw, dhf/wiki, dhf/steps and dhf/decisions/adr-0002-spec-first-regeneration.md
      When "rdm-spec fence /tmp/room" builds a clean room from cut spec-3
      Then /tmp/room is a repository with a single root commit
      And its working tree holds the allowed paths, unchanged, and no other file
      And no object reachable in it belongs to any other path

    @spec-0040 @e2e
    Scenario: An allowed path missing at the cut is refused
      Given the configuration allows dhf/steps and the cut holds no dhf/steps
      When "rdm-spec fence /tmp/room" is run
      Then it exits 2 and names dhf/steps

    @spec-0041 @e2e
    Scenario: No cycle, score or result reaches the clean room
      Given the cut holds dhf/cycles/03/score.edn and the configured results directory
      When "rdm-spec fence /tmp/room" builds a clean room
      Then neither is in it

    @spec-0141 @e2e
    Scenario: The fence refuses a clean room configured to reach the network
      Given the clean room is configured with network access
      When "rdm-spec fence /tmp/room" is run
      Then it exits 2 and says a clean room with a network can fetch what the fence left out

  # The model places each component's code at src/rdm_spec/<container>/<component>.clj
  # and its unit tests at test/rdm_spec/<container>/<component>_test.clj, and the
  # configuration lists every implementation path. So any part can be rebuilt
  # while the rest stands.
  Rule: A clean room can rebuild the project, one container or one component

    @spec-0182 @e2e
    Scenario: The project scope keeps no implementation
      When "rdm-spec fence /tmp/room" builds a clean room from cut spec-3
      Then /tmp/room holds none of the configured implementation paths

    @spec-0183 @e2e
    Scenario: A container scope removes that container's code and keeps the rest
      When "rdm-spec fence /tmp/room --container verification" builds a clean room from cut spec-3
      Then /tmp/room holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/
      And it holds every other implementation file at the cut, unchanged

    @spec-0184 @e2e
    Scenario: A component scope removes that component's code and keeps the rest
      When "rdm-spec fence /tmp/room --component spec/parser" builds a clean room from cut spec-3
      Then /tmp/room holds neither src/rdm_spec/spec/parser.clj nor test/rdm_spec/spec/parser_test.clj
      And it holds every other implementation file at the cut, unchanged

    @spec-0185 @e2e
    Scenario: A scope the model does not name is refused
      When "rdm-spec fence /tmp/room --container archive" is run
      Then it exits 2 and names archive and the model's containers
