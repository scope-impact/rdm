# Component Verify, bounded context verification of the rdm-spec container: dhf/spec/model.edn.
# Slice 11-project-in-a-folder (ADR 0004).

@UN-007
Feature: Verify
  Puts the room's implementation into the checkout as git would commit it.

  Rule: The room's implementation reaches the checkout as committed

    # Cycle 01 of halla-health's wallet (spec-27): verify copied the room's
    # node_modules, which .gitignore excludes, into the checkout, its links
    # copied as files, and the install in the checkout broke.
    @spec-0370 @e2e
    Scenario: The room's implementation reaches the checkout as git would commit it
      Given a room whose implementation holds a file its .gitignore excludes, a directory it excludes, and a symbolic link to a file beside it
      When "rdm-spec verify ROOM 03" is run
      Then the checkout holds neither the excluded file nor the excluded directory
      And the symbolic link is a symbolic link in the checkout, to the same target
