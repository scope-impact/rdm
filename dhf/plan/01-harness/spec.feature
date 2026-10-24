# Slice 01: the harness, specified.
#
# The regeneration source for RDM's harness: complete enough to build it without
# reading any existing implementation (there is none yet). Cites: plan.md in this
# directory, which names the sources each Rule rests on.
#
# CONVENTIONS, decided (decisions.md D2, D3):
#
# - A Rule is one design input. It carries the design input's id (@DI-n), the
#   user need it traces to (@UN-n) and the bounded context that owns it
#   (@context-<name>). Its title is the design input's text.
# - A Scenario is one example that accepts its Rule. It carries an item id
#   (@item-01-NNN), stamped once and never renumbered. A design input is
#   verified when every one of its scenarios passes.
# - "The harness" is the machinery that ratifies a build and measures the spec:
#   the stamper, the checklist, the scorer, the scoreboard and the gate. An
#   "agent harness" (the program an agent runs in) is a different thing.
# - Commands are named as RDM's command line names them. Nothing here names how
#   RDM is built.
#
# EXIT AND STREAM DISCIPLINE, uniform across every command in this slice:
# - A refusal prints its message to standard error and exits non-zero.
# - Nothing is written to disk on a refusal.
# - A command's product goes to the file it is told to write, or to standard
#   output when it is told none; its report goes to standard error.

Feature: RDM's harness: the spec is read, stamped, derived, scored and ratified

  # -------------------------------------------------------------------------------
  # The record reads the spec
  # -------------------------------------------------------------------------------

  @DI-90 @UN-020 @context-specification
  Rule: RDM shall read each Rule tagged with a design-input id in a slice's spec as a design input, with the Rule's title as its text, the user needs tagged on it as its traces and the context tagged on it as its owner

    @item-01-001
    Scenario: A tagged Rule is a design input of the record
      Given a slice "dhf/plan/07-example/spec.feature" containing:
        """
        Feature: F

          @DI-500 @UN-001 @context-specification
          Rule: RDM shall do one thing

            @item-07-001
            Scenario: it does it
              Given a
        """
      When the record is read
      Then the design inputs include DI-500 with the text "RDM shall do one thing"
      And DI-500 traces to UN-001 and is owned by the specification context
      And DI-500 is declared in "dhf/plan/07-example/spec.feature"

    @item-01-002
    Scenario: A design input declared in a spec and in a design document is a duplicate
      Given DI-500 is declared by a Rule in a slice's spec
      And DI-500 is also declared in a design document's design inputs
      When the design gate runs
      Then it fails on a duplicate declaration of DI-500
      And it names both the spec and the design document

    @item-01-003
    Scenario: A Rule in a slice's spec with no design-input id is refused
      Given a slice's spec with a Rule tagged with no design-input id
      When the design gate runs
      Then it fails, naming the spec, the Rule's line and the missing design-input id

    @item-01-004
    Scenario: A Rule with no owner or no traced need is refused
      Given a slice's spec with a Rule tagged "@DI-500" and no context tag
      When the design gate runs
      Then it fails, naming DI-500 and the missing owning context
      # The same holds for a Rule with no user-need tag: it names the missing need.

    @item-01-005
    Scenario: A spec that cannot be read is refused at its line
      Given a slice's spec whose third line is not Gherkin
      When the design gate runs
      Then it fails, naming the spec and line 3
      And no design input is read from that spec

  # -------------------------------------------------------------------------------
  # The stamper
  # -------------------------------------------------------------------------------

  @DI-91 @UN-020 @context-harness
  Rule: RDM shall give every scenario in a slice's spec an item id that is stamped once and never renumbered

    @item-01-006
    Scenario: An unstamped scenario is given the next free id in its slice
      Given a slice "dhf/plan/07-example/spec.feature" whose only scenario carries no item id
      When "rdm harness stamp" runs
      Then the line above that scenario is "@item-07-001", indented as the scenario is
      # The slice number is the slice directory's name up to its first "-", verbatim.
      # The running number has at least three digits, zero padded.

    @item-01-007
    Scenario: The next free id is one past the highest the spec carries
      Given a slice's spec whose scenarios carry @item-07-001 and @item-07-004
      And a third scenario carries no item id
      When "rdm harness stamp" runs
      Then the third scenario is stamped @item-07-005
      And the ids already there are unchanged

    @item-01-008
    Scenario: Stamping again changes nothing
      Given a slice's spec in which every scenario carries an item id
      When "rdm harness stamp" runs twice
      Then the spec is byte for byte what it was before the first run

    @item-01-009
    Scenario: Rewording or reordering scenarios keeps their ids
      Given a stamped spec whose scenarios are reworded and reordered by hand
      When "rdm harness stamp" runs
      Then every scenario keeps the item id it carried

    @item-01-010
    Scenario: The stamper says where it looked when it finds no spec
      Given a record with no slice's spec under "dhf/plan/"
      When "rdm harness stamp" runs
      Then it refuses, naming the directory it searched

  # -------------------------------------------------------------------------------
  # The checklist
  # -------------------------------------------------------------------------------

  @DI-92 @UN-020 @context-harness
  Rule: RDM shall derive the checklist from the slices' specs alone, one item per stamped scenario, each carrying its slice, its Rule and its design input

    @item-01-011
    Scenario: The checklist has one item per stamped scenario
      Given a slice's spec with two Rules over three stamped scenarios
      When "rdm harness checklist" runs
      Then the checklist has three items, in the order of the spec
      And each item carries its item id, slice, scenario title, Rule title and design-input id
      And the counts of slices and items are reported on standard error

    @item-01-012
    Scenario: An unstamped scenario stops the checklist rather than shrinking it
      Given a slice's spec with a scenario that carries no item id
      When "rdm harness checklist" runs
      Then it refuses, naming the scenario and its line
      And it says to run "rdm harness stamp"
      And no checklist is written

    @item-01-013
    Scenario: A spec with no scenario stops the checklist rather than producing an empty one
      Given a slice's spec with a Feature and no scenario
      When "rdm harness checklist" runs
      Then it refuses, naming the spec
      And no checklist is written

    @item-01-014
    Scenario: A slice still planned is scope, and is skipped out loud
      Given a slice whose plan's status is "planned"
      And a slice whose plan's status is "ready-for-development"
      When "rdm harness checklist" runs
      Then only the ready slice's scenarios are items
      And the planned slice is named on standard error as skipped

    @item-01-015
    Scenario: The checklist is the same bytes from the same spec
      Given an unchanged set of slices' specs
      When "rdm harness checklist" runs twice
      Then the two checklists are byte for byte the same

  # -------------------------------------------------------------------------------
  # Scoring
  # -------------------------------------------------------------------------------

  @DI-93 @UN-020 @context-harness
  Rule: RDM shall score a cycle by filing a verdict for every checklist item, recording an item no verdict reached as a failure

    @item-01-016
    Scenario: Verdicts are filed against the checklist as the cycle's record
      Given a checklist of three items and a verdict of pass or fail for each
      When "rdm harness score 03" runs with those verdicts
      Then the record of cycle 03 holds each item's verdict, with the checklist it was scored against

    @item-01-017
    Scenario: Zero verdicts means the grading did not happen
      Given a checklist of three items and no verdicts at all
      When "rdm harness score 03" runs
      Then it refuses, saying no grading happened
      And no record of cycle 03 is written

    @item-01-018
    Scenario: An item no verdict reached is a failure, never a smaller denominator
      Given a checklist of three items and verdicts for two of them
      When "rdm harness score 03" runs
      Then the third item is recorded as a failure marked unreached
      And the cycle's pass rate is over three items

    @item-01-019
    Scenario: A verdict for an item not on the checklist is refused
      Given a checklist that does not carry @item-07-099
      And a verdict for @item-07-099
      When "rdm harness score 03" runs
      Then it refuses, naming @item-07-099
      And no record of cycle 03 is written

    @item-01-020
    Scenario: An acceptance run is a grader
      Given an acceptance run's results in which one scenario passed, one failed and one did not run
      When "rdm harness score 03" runs with that run's results as its verdicts
      Then the passed scenario is a pass, the failed one a failure, and the one that did not run a failure marked unreached

  # -------------------------------------------------------------------------------
  # The scoreboard
  # -------------------------------------------------------------------------------

  @DI-94 @UN-020 @context-harness
  Rule: RDM shall compare two cycles on the items both scored, reporting what got better and what got worse apart and never as a net, and stating it when the denominator moved

    @item-01-021
    Scenario: The pass rate is over every item the cycle's checklist carried
      Given cycle 03 scored 2 passes and 1 failure
      When "rdm harness scoreboard 03" runs
      Then it reports 2 of 3 passed

    @item-01-022
    Scenario: Better and worse are counted apart
      Given cycle 02 and cycle 03 scored the same three items
      And one item went from failure to pass and another from pass to failure
      When "rdm harness scoreboard 03" runs
      Then it reports 1 got better and 1 got worse
      And it reports no net change

    @item-01-023
    Scenario: A moved denominator is said out loud, and new items are counted apart
      Given cycle 02 scored three items and cycle 03 scored those three and one more
      When "rdm harness scoreboard 03" runs
      Then it states that the denominator moved from 3 to 4
      And the new item is counted separately from the comparison

  # -------------------------------------------------------------------------------
  # The gate
  # -------------------------------------------------------------------------------

  # GUARD. A gate that grades with an instrument nobody checked ratifies a number,
  # not a build (Löyly: harness/verify.sh, step [0]).
  @DI-95 @UN-020 @context-harness
  Rule: RDM shall check the harness against its own known answers before the gate grades anything, and grade nothing when the check fails

    @item-01-024
    Scenario: The instrument is checked before the suite runs
      Given the harness's known answers: a record that must pass the gates and records that must fail them, each with the verdict it must get
      When "rdm harness verify" runs
      Then the gates are run on each known record before anything else
      And the suite runs only after every known record got its verdict

    @item-01-025
    Scenario: A wrong known answer stops the gate
      Given a known record that must fail the design gate passes it
      When "rdm harness verify" runs
      Then it fails, naming the known record and the verdict it got
      And no suite is run and nothing is started

  @DI-96 @UN-020 @context-harness
  Rule: RDM shall run the project's suite through the one command the project declares for it, and trust only that command's exit status

    @item-01-026
    Scenario: A green suite passes this step
      Given the project declares its suite command in its harness configuration
      And that command exits 0
      When "rdm harness verify" runs
      Then the suite step passes

    @item-01-027
    Scenario: A red suite fails the gate, whatever it prints
      Given the suite command prints "all passed" and exits 1
      When "rdm harness verify" runs
      Then it fails with the suite red
      And nothing is started

    @item-01-028
    Scenario: No declared suite means nothing to ratify
      Given the harness configuration declares no suite command
      When "rdm harness verify" runs
      Then it fails, saying nothing is declared to ratify and naming the missing setting

  # GUARD. An HTTP probe cannot tell this run's server from any other process on
  # the port (Löyly: harness/verify.sh, step [1]).
  @DI-97 @UN-020 @context-harness
  Rule: RDM shall start the record's served graph on a free port and require it to answer within the boot budget declared once, reporting the margin left

    @item-01-029
    Scenario: The served graph answers inside the budget, and the margin is reported
      Given a boot budget of 10 seconds polled every half second
      And the served graph answers on the third poll
      When "rdm harness verify" runs
      Then the boot step passes
      And it reports that it answered on poll 3 of 20, leaving 17 unused

    @item-01-030
    Scenario: A port already taken is refused, not reused
      Given another process is listening on the configured port
      When "rdm harness verify" runs
      Then it fails, naming the port and the process holding it
      And it starts nothing and stops nothing

    @item-01-031
    Scenario: No answer inside the budget fails the gate
      Given the served graph does not answer within the boot budget
      When "rdm harness verify" runs
      Then it fails, naming the budget
      And the process it started is stopped

  # GUARD. A name is not evidence: a starter page carrying the product's name passes
  # a name check (Löyly: harness/verify.sh, step [4]; harness/probe.sh).
  @DI-98 @UN-020 @context-harness
  Rule: RDM shall prove the served graph is this record by asking it for a design input's text and comparing the answer with the text the record holds

    @item-01-032
    Scenario: The served graph gives the record's own text
      Given the record declares DI-90 with its text
      When "rdm harness verify" asks the served graph for DI-90's text
      Then the answer is the record's text for DI-90
      And the identity step passes, naming the design input it asked about

    @item-01-033
    Scenario: A served graph of another record fails the gate
      Given the served graph answers with a different text for DI-90
      When "rdm harness verify" runs
      Then it fails, showing the text served and the text the record holds

  # GUARD. Specs change in planning rounds, on purpose, never during a build
  # (Löyly: docs/wow/harness.md, spec-lock).
  @DI-99 @UN-020 @context-harness
  Rule: RDM shall refuse to ratify a build round whose changes since its declared base touch a slice's plan or spec

    @item-01-034
    Scenario: A build round that changed a spec is refused
      Given a build round based on a commit
      And a slice's spec has changed since that commit
      When "rdm harness verify --base" that commit runs
      Then it fails, naming each plan or spec file changed since the base

    @item-01-035
    Scenario: A build round that left the plan alone passes this step
      Given a build round whose changes since its base touch only code and tests
      When "rdm harness verify --base" that commit runs
      Then the spec-lock step passes

  # -------------------------------------------------------------------------------
  # Configuration
  # -------------------------------------------------------------------------------

  @DI-100 @UN-020 @context-harness
  Rule: RDM shall read everything the harness knows about a project from one declared configuration, and refuse a missing or unknown setting by name

    @item-01-036
    Scenario: The configuration is printed on every run
      Given a harness configuration declaring the suite command, the port, the boot budget and the known answers
      When "rdm harness verify" runs
      Then it prints each setting and its value before the first step

    @item-01-037
    Scenario: An unknown setting is refused
      Given a harness configuration with a setting the harness does not know
      When any "rdm harness" command runs
      Then it refuses, naming the setting
