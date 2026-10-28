@UN-006
Feature: Command
  Runs every task in the project's folder, wherever the repository's root is.

  @UN-006
  Rule: A project may be one folder of a larger repository

    @spec-0368 @e2e
    Scenario Outline: A task that reads a cut reads the project's folder at the cut
      Given a repository whose project is its folder wallet/, holding wallet/dhf/harness/config.edn, whose commits tagged spec-2 and spec-3 change one component's scenarios, and whose dhf/cycles/03/cycle.edn names the cut spec-3
      And files outside wallet/ at the same paths a project at the root would use: dhf/harness/config.edn and dhf/spec/model.edn
      When "rdm-spec <task>" is run in wallet/
      Then it exits as it would for the same project at a repository's root, and <reads>
      And it reads nothing outside wallet/

      Examples:
        | task                   | reads                                                                                |
        | fence ROOM spec-3      | ROOM holds wallet/'s committed files at its own root, minus the denied ones           |
        | verify ROOM 03         | its checkout is wallet/ exported at spec-3, at the checkout's root                    |
        | checklist 03           | the checklist holds one item per stamped scenario of wallet/'s spec at spec-3         |
        | scopes spec-2 spec-3   | it names the one component whose scenarios changed in wallet/                         |
