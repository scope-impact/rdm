# Component Fence, bounded context regeneration of the rdm-spec container: dhf/spec/model.edn.

@UN-006
Feature: Fence
  Builds a clean room from a cut, for the whole project or the scopes a spec
  change touched, and proves it holds nothing it should not.

  # Every prior implementation and every measurement is out of the clean room,
  # so nothing can be copied, only rebuilt (loyly:harness/README.md@17d68d2, "The idea in five lines" 2).
  Rule: A clean room is built only from a cut

    @spec-0037 @e2e
    Scenario: A commit that is not a cut is refused
      Given the project's commit HEAD carries no spec-N tag
      When "rdm-spec fence ROOM HEAD" is run
      Then it exits 2 and says regeneration reads only a cut

  # Incident: a weaker fence probe once passed while the previous implementation
  # was still reachable from an older commit (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
  # The clean room is the cut minus what is denied, as Löyly's fence is the
  # repository minus every implementation and measurement (spec owner, cycle
  # 13). A deny-list leaks what nobody remembered to deny; so every committed
  # path is classified, denied or kept, and an unclassified one stops the
  # check, and no kept file may carry a removed implementation file's bytes.
  # spec-0040 (an allowed path missing at the cut) is retired with the
  # allow-list.
  Rule: A clean room is the cut minus what is denied

    @spec-0038 @e2e
    Scenario: The clean room is the cut's committed files minus the denied ones, as one root commit
      Given the configuration denies the implementation, the measurements dhf/cycles and the generated site, and keeps every other path
      And the cut also holds dhf/cycles/01/score.edn
      When "rdm-spec fence ROOM spec-3" is run
      Then ROOM is a repository with a single root commit
      And its working tree holds every file committed at the cut that is not denied, unchanged, and no other file
      And no object reachable in it belongs to a denied path

    @spec-0294 @e2e
    Scenario: Uncommitted and ignored files never reach a clean room
      Given a project whose working tree holds an untracked file notes.md and a file git ignores, beside its commit tagged spec-3
      When "rdm-spec fence ROOM spec-3" is run
      Then ROOM holds neither file

    @spec-0295 @e2e
    Scenario: A kept file carrying a removed implementation file's bytes stops the fence
      Given a project whose commit tagged spec-3 holds dhf/wiki/pages/copy.md with the same bytes as an implementation file
      When "rdm-spec fence ROOM spec-3" is run
      Then it exits 1, names dhf/wiki/pages/copy.md and the implementation file, and builds no room

  Rule: Every committed path is denied or kept

    @spec-0296 @e2e
    Scenario: A committed path neither denied nor kept fails the check
      Given a project whose configuration denies the implementation and the measurements and keeps dhf/spec, and a committed top-level file notes.md
      When "rdm-spec check" is run
      Then it exits 1 with a finding naming notes.md as neither denied nor kept

    @spec-0297 @e2e
    Scenario: A path under dhf/ is classified on its own
      Given a project whose configuration keeps dhf/spec, and a committed directory dhf/scratch
      When "rdm-spec check" is run
      Then it exits 1 with a finding naming dhf/scratch as neither denied nor kept

  # The configuration's :code-layout places each component's code and unit
  # tests by context and component, and :implementation lists every
  # implementation path. So any part can be rebuilt while the rest stands, which
  # is what makes the contexts composable.
  Rule: A clean room can rebuild the project, one bounded context or one component

    @spec-0182 @e2e
    Scenario: The project scope keeps no implementation
      Given a project whose commit tagged spec-3 holds an implementation
      When "rdm-spec fence ROOM spec-3" is run
      Then ROOM holds none of the configured implementation paths

    @spec-0183 @e2e
    Scenario: A context scope removes that context's code and keeps the rest
      Given the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/
      When "rdm-spec fence ROOM spec-3 --context verification" is run
      Then ROOM holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/
      And it holds every other implementation file at the cut, unchanged

    @spec-0184 @e2e
    Scenario: A component scope removes that component's code and keeps the rest
      Given the code layout places component code at src/rdm_spec/<context>/<component>.clj and its unit tests at test/rdm_spec/<context>/<component>_test.clj
      When "rdm-spec fence ROOM spec-3 --component spec/parser" is run
      Then ROOM holds neither src/rdm_spec/spec/parser.clj nor test/rdm_spec/spec/parser_test.clj
      And it holds every other implementation file at the cut, unchanged

    @spec-0226 @e2e
    Scenario: A list of scopes removes the code of each, and keeps the rest
      Given a project whose commit tagged spec-3 holds an implementation
      When "rdm-spec fence ROOM spec-3 --context knowledge,publishing --component spec/parser" is run
      Then ROOM holds no file of the bounded contexts knowledge and publishing or of the component spec/parser
      And it holds every other implementation file at the cut, unchanged

    @spec-0185 @e2e
    Scenario: A scope the model does not name is refused
      Given a project whose commit tagged spec-3 holds an implementation
      When "rdm-spec fence ROOM spec-3 --context archive" is run
      Then it exits 2 and names archive and the model's bounded contexts and kernel

    # The kernel is shared code in no bounded context, but it changes with the
    # spec like any other (cycle 01, D).
    @spec-0222 @e2e
    Scenario: The kernel is a scope like a bounded context
      Given a project whose commit tagged spec-3 holds an implementation
      When "rdm-spec fence ROOM spec-3 --component kernel/bus" is run
      Then ROOM holds neither src/rdm_spec/kernel/bus.clj nor test/rdm_spec/kernel/bus_test.clj
      And it holds every other implementation file at the cut, unchanged
