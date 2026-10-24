# Component Fence, bounded context regeneration of the rdm-spec container: dhf/spec/model.edn.

@UN-006
Feature: Fence
  Builds a clean room from a cut, for the whole project, one bounded context or
  one component, and proves it holds nothing else.

  # Every prior implementation and every measurement is out of the clean room,
  # so nothing can be copied, only rebuilt (loyly:harness/README.md@17d68d2, "The idea in five lines" 2).
  Rule: A clean room is built only from a cut

    @spec-0037 @e2e
    Scenario: The fence refuses a source that is not a cut
      Given the commit 1a2b3c4 carries no spec-N tag
      When "rdm-spec fence /tmp/room 1a2b3c4" is run
      Then it exits 2 and says regeneration reads only a cut

  # Incident: a weaker fence probe once passed while the previous implementation
  # was still reachable from an older commit (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
  # A clean room is an allow-list, so nothing reaches it by being forgotten.
  Rule: A clean room holds what is allowed, and nothing else

    @spec-0038 @e2e
    Scenario: The clean room holds only the allowed paths, as one root commit
      Given the configuration allows dhf/spec, dhf/harness, dhf/raw, dhf/wiki and dhf/decisions/adr-0002-spec-first-regeneration.md
      When "rdm-spec fence /tmp/room spec-3" is run
      Then /tmp/room is a repository with a single root commit
      And its working tree holds the allowed paths, unchanged, and no other file
      And no object reachable in it belongs to any other path

    @spec-0040 @e2e
    Scenario: An allowed path missing at the cut is refused
      Given the configuration allows dhf/wiki and the cut holds no dhf/wiki
      When "rdm-spec fence /tmp/room spec-3" is run
      Then it exits 2 and names dhf/wiki

  # The configuration's :code-layout places each component's code and unit
  # tests by context and component, and :implementation lists every
  # implementation path. So any part can be rebuilt while the rest stands, which
  # is what makes the contexts composable.
  Rule: A clean room can rebuild the project, one bounded context or one component

    @spec-0182 @e2e
    Scenario: The project scope keeps no implementation
      When "rdm-spec fence /tmp/room spec-3" is run
      Then /tmp/room holds none of the configured implementation paths

    @spec-0183 @e2e
    Scenario: A context scope removes that context's code and keeps the rest
      Given the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/
      When "rdm-spec fence /tmp/room spec-3 --context verification" is run
      Then /tmp/room holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/
      And it holds every other implementation file at the cut, unchanged

    @spec-0184 @e2e
    Scenario: A component scope removes that component's code and keeps the rest
      Given the code layout places component code at src/rdm_spec/<context>/<component>.clj and its unit tests at test/rdm_spec/<context>/<component>_test.clj
      When "rdm-spec fence /tmp/room spec-3 --component spec/parser" is run
      Then /tmp/room holds neither src/rdm_spec/spec/parser.clj nor test/rdm_spec/spec/parser_test.clj
      And it holds every other implementation file at the cut, unchanged

    @spec-0185 @e2e
    Scenario: A scope the model does not name is refused
      When "rdm-spec fence /tmp/room spec-3 --context archive" is run
      Then it exits 2 and names archive and the model's bounded contexts
