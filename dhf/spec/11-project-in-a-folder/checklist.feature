@UN-008
Feature: Checklist
  Derives a cycle's checklist in the project's folder.

  Rule: A cycle of a project in a folder is the folder's

    @spec-0406 @e2e
    Scenario: The checklist of a project in a folder is written in the folder's cycles
      Given a repository whose project is its folder wallet/, with wallet/dhf/cycles/03/cycle.edn naming the cut spec-3, and a dhf/cycles/03/ at the repository's root
      When "rdm-spec checklist 03" is run in wallet/
      Then it writes wallet/dhf/cycles/03/checklist.json, one item per stamped scenario of wallet/'s spec at spec-3
      And nothing at the repository's root changes
