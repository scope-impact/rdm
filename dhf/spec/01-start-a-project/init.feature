@UN-009
Feature: Init
  Writes a new project's skeleton into a path that does not exist yet.

  Rule: Init starts a project from nothing

    @spec-0086 @contract
    Scenario: Init writes the skeleton and names what it wrote
      Given no directory at the path "sauna"
      When "rdm-spec init sauna" is run
      Then "sauna" is a git repository with no commit yet, whose working tree holds exactly:
        | path                              | holds                                                                    |
        | dhf/spec/model.edn                | a model with the system and nothing else                                 |
        | dhf/spec/events.edn               | an empty event catalogue                                                 |
        | dhf/spec/needs.edn                | no needs                                                                 |
        | dhf/spec/ids.edn                  | the ledger at 0                                                          |
        | dhf/spec/glossary.md              | an empty glossary                                                        |
        | dhf/spec/README.md                | the spec's conventions, and the results contract: every Allure element a result carries and what it is filled from, as the glossary's "Allure result" states it, and rdm-spec's default Allure mapping, which a configuration's :allure may replace |
        | dhf/harness/config.edn            | the id prefix, every directory the tasks read, the clean room's deny-list (the implementation, the measurements dhf/cycles, the generated site) and kept paths (.gitignore, .githooks, .github, dhf/spec, dhf/harness, dhf/raw, dhf/wiki, dhf/views, dhf/decisions), the watched directories ("/" and "~") and temporary directory ("/tmp"), a :verify holding one :tests check whose command is not yet set, and no implementation paths |
        | dhf/harness/stack.md              | a stack table with no rows yet                                           |
        | dhf/raw/README.md                 | the rules for raw sources                                                |
        | dhf/raw/sources.edn               | no sources                                                               |
        | dhf/wiki/index.md                 | the wiki's rules and an empty list of pages                              |
        | dhf/decisions/README.md           | how decisions are recorded                                               |
        | .githooks/pre-commit              | a hook that runs "rdm-spec check", with core.hooksPath set to .githooks  |
        | .github/workflows/cut.yml         | a workflow that runs "rdm-spec cut" on every push to the default branch  |
        | .gitignore                        | dhf/allure-results/, dhf/cycles/*/results/, dhf/cycles/*/build/, site-src/ and site/ |
      And init lists every file it wrote and the next steps

    @spec-0422 @contract
    Scenario: Init's result has one shape
      Given no directory at the path "sauna"
      When "rdm-spec init sauna" is run
      Then its standard output, read as EDN, is {:init {:path "sauna" :wrote [PATH ...] :next [STEP ...]}}, :path the path as it was given
      And :wrote names every file of the skeleton once, relative to "sauna", in the order of the table of "Init writes the skeleton and names what it wrote"
      And :next is exactly ["commit the skeleton" "describe the system in dhf/spec/model.edn and its needs in dhf/spec/needs.edn" "write scenarios under dhf/spec/, then run rdm-spec stamp and rdm-spec check" "set the :tests check's command in dhf/harness/config.edn"]

    @spec-0272 @e2e
    Scenario: Init takes an absolute path as well as a relative one
      Given no directory at DIR/sauna
      When "rdm-spec init DIR/sauna" is run
      Then DIR/sauna is a git repository holding the skeleton

    @spec-0087 @e2e
    Scenario: Init refuses a path that already exists
      Given a directory at the path "sauna"
      When "rdm-spec init sauna" is run
      Then it exits 2 and names the path
      And nothing in "sauna" changes

    @spec-0089 @e2e
    Scenario: The id prefix is chosen at init
      When "rdm-spec init sauna --id-prefix sauna" is run
      Then the harness configuration sets the id prefix to "sauna"

    @spec-0223 @e2e
    Scenario Outline: The id prefix defaults to spec and must start a tag
      When "rdm-spec init sauna <flag>" is run
      Then it <outcome>

      Examples:
        | flag                | outcome                                         |
        |                     | sets the id prefix to "spec"                    |
        | --id-prefix 9lives  | exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens |
        | --id-prefix UN      | exits 2 and says the prefix UN is taken by need tags                     |

  Rule: A new project is green before anything is written in it

    @spec-0090 @e2e
    Scenario: The check passes on a skeleton nobody has touched
      Given a project just initialised at "sauna"
      When "rdm-spec check" is run in "sauna"
      Then it passes
