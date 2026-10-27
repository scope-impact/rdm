# Component Parser, bounded context spec of the rdm-spec container: dhf/spec/model.edn.

@UN-001 @UN-002
Feature: Parser
  Reads the declared Gherkin subset and refuses anything outside it.

  # The parser is ours. A construct it does not know is refused, never skipped:
  # a scenario the parser silently drops is a requirement nothing checks.
  # The checklist and ratify read the spec at a cut, not the working tree
  # (cycle 00, dhf/cycles/00/proposed-spec-changes.md, C20).
  Rule: The spec is read at a revision

    @spec-0220 @contract
    Scenario: A task that names a cut reads the spec as it was at that cut
      Given dhf/cycles/03/cycle.edn names the cut spec-3 and the scope :project
      And the spec at spec-3 holds 12 stamped scenarios and the working tree's spec holds 13
      When "rdm-spec checklist 03" is run
      Then the checklist holds the 12 scenarios of spec-3

  Rule: The spec is written in a declared subset of Gherkin

    @spec-0051 @e2e
    Scenario: A feature file using every supported construct passes the check
      Given a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables
      When "rdm-spec check" is run
      Then it passes

    @spec-0052 @e2e
    Scenario: An unknown keyword is refused with its line
      Given a feature file containing a line starting with "Scenario Template:"
      When "rdm-spec check" is run
      Then it fails and names the file, the line number and the keyword

    @spec-0053 @e2e
    Scenario: A feature file holds exactly one feature
      Given a feature file with two Feature lines
      When "rdm-spec check" is run
      Then it fails and names the file and the line of the second Feature

  # spec-29, from halla-health's wallet: its SDDs are one folder each (auth,
  # then its infrastructure, then the app's flows), each with its own needs,
  # model, glossary and ids; rdm-spec read one spec folder, so the second could
  # not sit beside the first.
  @UN-001
  Rule: A project keeps one spec, or several, one folder each

    @spec-0385 @contract
    Scenario: Several specs are the configuration's :specs, each a folder of its own
      Given a configuration whose :specs is [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/infra" :id-prefix "infra"}]
      When "rdm-spec check" is run
      Then each spec is read from its folder: its feature files, model.edn, events.edn, needs.edn, glossary.md, ids.edn, and its domain.edn where it keeps one
      And a configuration with no :specs reads one spec, from :spec-dir and the paths it names, as before

    @spec-0386 @e2e
    Scenario Outline: A configuration of several specs rdm-spec cannot read apart is refused before anything runs
      Given a configuration whose :specs <holds>
      When "rdm-spec check" is run
      Then it exits 2 and says <why>

      Examples:
        | holds                                              | why                                                              |
        | two specs named "auth"                             | a spec's name is declared once                                   |
        | two specs with the id prefix "auth"                | an id prefix is declared once, so no two scenarios share an id   |
        | two specs in one folder                            | a folder holds one spec                                          |
        | a spec whose folder does not exist                 | names the spec and its folder                                    |

    @spec-0387 @e2e
    Scenario: Each spec is checked on its own terms, and one spec's finding fails the check
      Given two specs, auth and infra, where infra's feature file names a need its needs.edn does not hold, and auth's names UN-001, which only auth's needs.edn holds
      When "rdm-spec check" is run
      Then it exits 1 with one finding, naming infra's feature file and the need, and none about auth
      And a need, a component and a context resolve within their own spec only

    @spec-0388 @e2e
    Scenario: Each spec stamps from its own ledger, with its own prefix
      Given two specs, auth with 3 stamped scenarios and infra with none, each with one unstamped scenario
      When "rdm-spec stamp" is run
      Then auth's is stamped @auth-0004 from dhf/sdd/auth/ids.edn, and infra's @infra-0001 from dhf/sdd/infra/ids.edn

    @spec-0389 @e2e
    Scenario: A cycle may be scoped to one spec
      Given two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is [{:spec "auth"}]
      When "rdm-spec checklist 03" is run, and verify's tests check reads the cycle's results
      Then the checklist holds one item per stamped scenario of auth and none of infra
      And verify requires a result for every scenario of auth, and none for infra's

    @spec-0390 @e2e
    Scenario Outline: Status and release read every spec, or the one named
      Given two specs, auth with every scenario verified and infra with one unverified
      When "<task>" is run
      Then it <answers>

      Examples:
        | task                                         | answers                                                       |
        | rdm-spec status --results R                  | lists every scenario of both specs, each with its spec's name |
        | rdm-spec release --results R                 | refuses, naming infra's unverified scenario                   |
        | rdm-spec release --results R --spec auth     | passes                                                        |

