@UN-006
Feature: Fence
  Builds a clean room from a cut, for the whole project or the scopes a spec
  change touched, and proves it holds nothing it should not.

  Rule: A clean room is built only from a cut

    @spec-0037 @e2e
    Scenario: A commit that is not a cut is refused
      Given the project's commit HEAD carries no spec-N tag
      When "rdm-spec fence ROOM HEAD" is run
      Then it exits 2 and says regeneration reads only a cut

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

    @spec-0329 @e2e
    Scenario: A component the cut removed is fenced by the files the code layout finds for it
      Given the cut spec-4's model no longer names ratification/ratify, and the cut still holds src/rdm_spec/ratification/ratify.clj and test/rdm_spec/ratification/ratify_test.clj
      When "rdm-spec fence ROOM spec-4 --component ratification/ratify" is run
      Then ROOM holds neither file, and its result names ratification/ratify as a removed component
      And it holds every other implementation file at the cut, unchanged

    @spec-0185 @e2e
    Scenario: A scope the model does not name is refused
      Given a project whose commit tagged spec-3 holds an implementation
      When "rdm-spec fence ROOM spec-3 --context archive" is run
      Then it exits 2 and names archive and the model's bounded contexts and kernel
      And so does a component the model does not name whose files the code layout finds none of

    @spec-0222 @e2e
    Scenario: The kernel is a scope like a bounded context
      Given a project whose commit tagged spec-3 holds an implementation
      When "rdm-spec fence ROOM spec-3 --component kernel/bus" is run
      Then ROOM holds neither src/rdm_spec/kernel/bus.clj nor test/rdm_spec/kernel/bus_test.clj
      And it holds every other implementation file at the cut, unchanged

  Rule: Fence names the room it built, and builds one only where nothing stands

    @contract
    Scenario Outline: Fence prints the room it built
      Given a project whose commit tagged spec-4 holds an implementation, the cut spec-4's model no longer names ratification/ratify and the cut still holds its files, and C is the commit of the room fence makes
      When "rdm-spec fence ROOM spec-4 <args>" is run
      Then it exits 0, and its standard output, read as EDN, is exactly {:fence {:room ROOM :cut "spec-4" :commit C :scope <scope> :kept K :denied D<more>}}, ROOM as given, K the files the room holds and D the committed files of the cut it does not

      Examples:
        | args                                  | scope                                                     | more                                         |
        |                                       | :project                                                  |                                              |
        | --context verification                | [{:context :verification}]                                |                                              |
        | --component ratification/ratify       | [{:component :ratification/ratify}]                       | , and :removed [:ratification/ratify], each scope's component the cut's model no longer names |

    @e2e
    Scenario: A cut holding a path neither denied nor kept builds no room
      Given a project whose configuration at the commit tagged spec-3 keeps dhf/spec and denies the implementation and the measurements, and whose commit tagged spec-3 also holds notes.md
      When "rdm-spec fence ROOM spec-3" is run
      Then it exits 1, names notes.md as neither denied nor kept, and builds no room

    @e2e
    Scenario Outline: The bytes rule compares the kept files outside the implementation that are not empty
      Given a project whose commit tagged spec-3 holds <files>
      When "rdm-spec fence ROOM spec-3 --component spec/parser" is run
      Then it exits 0 and builds the room

      Examples:
        | files                                                                                                  |
        | an empty dhf/wiki/pages/empty.md, and an empty implementation file of spec/parser                      |
        | an implementation file of spec/stamper with the same bytes as an implementation file of spec/parser    |

    @e2e
    Scenario Outline: A room is built only where nothing stands
      Given a project whose commit tagged spec-3 holds an implementation, and ROOM <is>
      When "rdm-spec fence ROOM spec-3" is run
      Then it <outcome>

      Examples:
        | is                                  | outcome                                                                           |
        | is a directory holding a file notes.md | exits 2, names ROOM as not empty, and ROOM holds notes.md and no other file      |
        | is an empty directory               | exits 0 and builds the room in ROOM                                                |

    @e2e
    Scenario: A path staged and not yet committed is classified
      Given a project whose configuration keeps dhf/spec, and whose index holds notes.md, added and not yet committed
      When "rdm-spec check" is run
      Then it exits 1 with a finding naming notes.md as neither denied nor kept

  Rule: A slice is a scope beside contexts and components

    @e2e
    Scenario Outline: A slice joins the other scopes named, and needs the slices layout
      Given a project whose commit tagged spec-3 holds an implementation, and <config>
      When "rdm-spec fence ROOM spec-3 <args>" is run
      Then it <outcome>

      Examples:
        | config                                                                                     | args                                          | outcome                                                                                                            |
        | a configuration with :spec-layout :slices, and the slice 12-several-specs reaching :spec/parser | --slice 12-several-specs --context knowledge | exits 0, and ROOM holds no file of the bounded context knowledge or of any component the slice reaches            |
        | a configuration with no :spec-layout                                                       | --slice 12-several-specs                      | exits 2, names 12-several-specs and says the project's spec is not laid out by slice, and builds no room          |
