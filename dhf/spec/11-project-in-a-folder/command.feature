# Component Command, bounded context kernel of the rdm-spec container: dhf/spec/model.edn.
# Slice 11-project-in-a-folder (ADR 0004).

@UN-006
Feature: Command
  Runs every task in the project's folder, wherever the repository's root is.

  # A task runs its stages in order and every stage reports, so one run names
  # every finding. A gate is the exception: a stage the task table marks as a
  # gate stops the task when it has findings, and the stages after it never
  # run. The plugins answering :Gate are the gate before fence and verify, the
  # oracle among them (dhf/spec/08-plugins/plugins.feature; dhf/spec/measurement/
  # oracle.feature, spec-0232). A gate replaces an event the gated stages would
  # each have had to interpret (cycle 04).
  # Cycles 01 and 02 of halla-health's wallet (spec-27): the wallet is one
  # folder of a monorepo, and fence and verify read a cut by paths from the
  # repository's root, so they found no configuration at the cut; the cycles
  # ran on a copy of the folder as a repository of its own.
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
