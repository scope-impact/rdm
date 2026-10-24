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
# - Commands are named as the harness's own command line names them
#   ("harness stamp"). Nothing here names how the harness, or RDM, is built or
#   run; the harness's design document does.
#
# COMMANDS AND EVENTS, after the PensionBee DDD workshop (CONTEXT.md, "Domain
# model"; plan.md, "Commands and events"): each Then names the domain event the
# command produced. A success event is named in the past tense; a fail event
# names the business rule broken after a slash ("Checklist Not Derived /
# Unstamped Scenario"); a warning has "Warned" before the slash and never fails
# a command.
#
# EXIT AND STREAM DISCIPLINE, uniform across every command in this slice:
# - A fail event prints its message to standard error and exits non-zero.
# - Nothing is written to disk on a fail event.
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
      Then the event is "Design Controls Not Approved / Duplicate Id"
      And it names both the spec and the design document

    @item-01-003
    Scenario: A Rule in a slice's spec with no design-input id is refused
      Given a slice's spec with a Rule tagged with no design-input id
      When the design gate runs
      Then the event is "Design Controls Not Approved / Untagged Rule"
      And it names the spec and the Rule's line

    @item-01-004
    Scenario: A Rule with no owner or no traced need is refused
      Given a slice's spec with a Rule tagged "@DI-500" and no context tag
      When the design gate runs
      Then the event is "Design Controls Not Approved / Rule Without Owner"
      And it names DI-500
      # A Rule with no user-need tag gives "Design Controls Not Approved / Rule
      # Without Need", naming the design input the same way.

    @item-01-005
    Scenario: A spec that cannot be read is refused at its line
      Given a slice's spec whose third line is not Gherkin
      When the design gate runs
      Then the event is "Design Controls Not Approved / Unreadable Spec"
      And it names the spec and line 3
      And no design input is read from that spec

  # -------------------------------------------------------------------------------
  # The stamper
  # -------------------------------------------------------------------------------

  @DI-91 @UN-020 @context-harness
  Rule: RDM shall give every scenario in a slice's spec an item id that is stamped once and never renumbered

    @item-01-006
    Scenario: An unstamped scenario is given the next free id in its slice
      Given a slice "dhf/plan/07-example/spec.feature" whose only scenario carries no item id
      When "harness stamp" runs
      Then the event is "Scenarios Stamped"
      And the line above that scenario is "@item-07-001", indented as the scenario is
      # The slice number is the slice directory's name up to its first "-", verbatim.
      # The running number has at least three digits, zero padded.

    @item-01-007
    Scenario: The next free id is one past the highest the spec carries
      Given a slice's spec whose scenarios carry @item-07-001 and @item-07-004
      And a third scenario carries no item id
      When "harness stamp" runs
      Then the third scenario is stamped @item-07-005
      And the ids already there are unchanged

    @item-01-008
    Scenario: Stamping again changes nothing
      Given a slice's spec in which every scenario carries an item id
      When "harness stamp" runs twice
      Then the spec is byte for byte what it was before the first run

    @item-01-009
    Scenario: Rewording or reordering scenarios keeps their ids
      Given a stamped spec whose scenarios are reworded and reordered by hand
      When "harness stamp" runs
      Then every scenario keeps the item id it carried

    @item-01-010
    Scenario: The stamper says where it looked when it finds no spec
      Given a record with no slice's spec under "dhf/plan/"
      When "harness stamp" runs
      Then the event is "Scenarios Not Stamped / No Spec"
      And it names the directory it searched

  # -------------------------------------------------------------------------------
  # The checklist
  # -------------------------------------------------------------------------------

  @DI-92 @UN-020 @context-harness
  Rule: RDM shall derive the checklist from the slices' specs alone, one item per stamped scenario, each carrying its slice, its Rule and its design input

    @item-01-011
    Scenario: The checklist has one item per stamped scenario
      Given a slice's spec with two Rules over three stamped scenarios
      When "harness checklist" runs
      Then the event is "Checklist Derived"
      And the checklist has three items, in the order of the spec
      And each item carries its item id, slice, scenario title, Rule title and design-input id
      And the counts of slices and items are reported on standard error

    @item-01-012
    Scenario: An unstamped scenario stops the checklist rather than shrinking it
      Given a slice's spec with a scenario that carries no item id
      When "harness checklist" runs
      Then the event is "Checklist Not Derived / Unstamped Scenario"
      And it names the scenario and its line
      And it says to run "harness stamp"
      And no checklist is written

    @item-01-013
    Scenario: A spec with no scenario stops the checklist rather than producing an empty one
      Given a slice's spec with a Feature and no scenario
      When "harness checklist" runs
      Then the event is "Checklist Not Derived / Empty Spec"
      And it names the spec
      And no checklist is written

    @item-01-014
    Scenario: A slice still planned is scope, and is skipped out loud
      Given a slice whose plan's status is "planned"
      And a slice whose plan's status is "ready-for-development"
      When "harness checklist" runs
      Then the event is "Checklist Derived"
      And only the ready slice's scenarios are items
      And the warning "Checklist Warned / Planned Slice Skipped" names the planned slice

    @item-01-015
    Scenario: The checklist is the same bytes from the same spec
      Given an unchanged set of slices' specs
      When "harness checklist" runs twice
      Then the two checklists are byte for byte the same

  # -------------------------------------------------------------------------------
  # Scoring
  # -------------------------------------------------------------------------------

  @DI-93 @UN-020 @context-harness
  Rule: RDM shall score a cycle by filing a verdict for every checklist item, recording an item no verdict reached as a failure

    @item-01-016
    Scenario: Verdicts are filed against the checklist as the cycle's record
      Given a checklist of three items and a verdict of pass or fail for each
      When "harness score 03" runs with those verdicts
      Then the event is "Cycle Scored"
      And the record of cycle 03 holds each item's verdict, with the checklist it was scored against

    @item-01-017
    Scenario: Zero verdicts means the grading did not happen
      Given a checklist of three items and no verdicts at all
      When "harness score 03" runs
      Then the event is "Cycle Not Scored / No Verdicts"
      And no record of cycle 03 is written

    @item-01-018
    Scenario: An item no verdict reached is a failure, never a smaller denominator
      Given a checklist of three items and verdicts for two of them
      When "harness score 03" runs
      Then the event is "Cycle Scored"
      And the third item is recorded as a failure marked unreached
      And the cycle's pass rate is over three items

    @item-01-019
    Scenario: A verdict for an item not on the checklist is refused
      Given a checklist that does not carry @item-07-099
      And a verdict for @item-07-099
      When "harness score 03" runs
      Then the event is "Cycle Not Scored / Unknown Item"
      And it names @item-07-099
      And no record of cycle 03 is written

    @item-01-020
    Scenario: An acceptance run is a grader
      Given an acceptance run's results in which one scenario passed, one failed and one did not run
      When "harness score 03" runs with that run's results as its verdicts
      Then the passed scenario is a pass, the failed one a failure, and the one that did not run a failure marked unreached

  # -------------------------------------------------------------------------------
  # The scoreboard
  # -------------------------------------------------------------------------------

  @DI-94 @UN-020 @context-harness
  Rule: RDM shall compare two cycles on the items both scored, reporting what got better and what got worse apart and never as a net, and stating it when the denominator moved

    @item-01-021
    Scenario: The pass rate is over every item the cycle's checklist carried
      Given cycle 03 scored 2 passes and 1 failure
      When "harness scoreboard 03" runs
      Then it reports 2 of 3 passed

    @item-01-022
    Scenario: Better and worse are counted apart
      Given cycle 02 and cycle 03 scored the same three items
      And one item went from failure to pass and another from pass to failure
      When "harness scoreboard 03" runs
      Then it reports 1 got better and 1 got worse
      And it reports no net change

    @item-01-023
    Scenario: A moved denominator is said out loud, and new items are counted apart
      Given cycle 02 scored three items and cycle 03 scored those three and one more
      When "harness scoreboard 03" runs
      Then it states that the denominator moved from 3 to 4
      And the new item is counted separately from the comparison

  # -------------------------------------------------------------------------------
  # The gate
  # -------------------------------------------------------------------------------

  # THE GATE IS A CHAIN (decisions.md D9). "harness verify" first admits the
  # round (configuration and spec-lock, every rule concluded together), then each
  # step is a command a reaction issues on the previous step's success event:
  #   Round Admitted -> Check Instrument; Instrument Checked -> Run Suite;
  #   Suite Passed -> Start Served Graph; Served Graph Answered -> Prove Identity.
  # No reaction listens to a fail event, so a step that fails starts nothing after
  # it. The verdict is "Build Ratified" when "Identity Proven" happens, and
  # "Build Not Ratified" naming the fail event otherwise.

  # GUARD. A gate that grades with an instrument nobody checked ratifies a number,
  # not a build (Löyly: harness/verify.sh, step [0]).
  @DI-95 @UN-020 @context-harness
  Rule: RDM shall check the harness against its own known answers before the gate grades anything, and grade nothing when the check fails

    @item-01-024
    Scenario: The instrument is checked before the suite runs
      Given the harness's known answers: a record that must pass the gates and records that must fail them, each with the verdict it must get
      When "harness verify" runs
      Then the event "Instrument Checked" happens before any other step's event
      And the suite runs only because the instrument was checked

    @item-01-025
    Scenario: A wrong known answer stops the gate
      Given a known record that must fail the design gate passes it
      When "harness verify" runs
      Then the event is "Instrument Not Checked / Wrong Known Answer"
      And it names the known record and the verdict it got
      And no suite is run and nothing is started
      And the verdict is "Build Not Ratified"

  @DI-96 @UN-020 @context-harness
  Rule: RDM shall run the project's suite through the one command the project declares for it, and trust only that command's exit status

    @item-01-026
    Scenario: A green suite passes this step
      Given the project declares its suite command in its harness configuration
      And that command exits 0
      When "harness verify" runs
      Then the event "Suite Passed" happens

    @item-01-027
    Scenario: A red suite fails the gate, whatever it prints
      Given the suite command prints "all passed" and exits 1
      When "harness verify" runs
      Then the event is "Suite Not Passed / Suite Red"
      And nothing is started
      And the verdict is "Build Not Ratified"

    @item-01-028
    Scenario: No declared suite means nothing to ratify
      Given the harness configuration declares no suite command
      When "harness verify" runs
      Then the event is "Round Not Admitted / No Suite Declared"
      And it names the missing setting

  # GUARD. An HTTP probe cannot tell this run's server from any other process on
  # the port (Löyly: harness/verify.sh, step [1]).
  @DI-97 @UN-020 @context-harness
  Rule: RDM shall start the record's served graph on a free port and require it to answer within the boot budget declared once, reporting the margin left

    @item-01-029
    Scenario: The served graph answers inside the budget, and the margin is reported
      Given a boot budget of 10 seconds polled every half second
      And the served graph answers on the third poll
      When "harness verify" runs
      Then the event "Served Graph Answered" happens
      And it reports that it answered on poll 3 of 20, leaving 17 unused

    @item-01-030
    Scenario: A port already taken is refused, not reused
      Given another process is listening on the configured port
      When "harness verify" runs
      Then the event is "Served Graph Not Started / Port Taken"
      And it names the port and the process holding it
      And it starts nothing and stops nothing

    @item-01-031
    Scenario: No answer inside the budget fails the gate
      Given the served graph does not answer within the boot budget
      When "harness verify" runs
      Then the event is "Served Graph Not Started / Boot Budget Exceeded"
      And it names the budget
      And the process it started is stopped

  # GUARD. A name is not evidence: a starter page carrying the product's name passes
  # a name check (Löyly: harness/verify.sh, step [4]; harness/probe.sh).
  @DI-98 @UN-020 @context-harness
  Rule: RDM shall prove the served graph is this record by asking it for a design input's text and comparing the answer with the text the record holds

    @item-01-032
    Scenario: The served graph gives the record's own text
      Given the record declares DI-90 with its text
      When "harness verify" asks the served graph for DI-90's text
      Then the answer is the record's text for DI-90
      And the event "Identity Proven" happens, naming the design input it asked about
      And the verdict is "Build Ratified"

    @item-01-033
    Scenario: A served graph of another record fails the gate
      Given the served graph answers with a different text for DI-90
      When "harness verify" runs
      Then the event is "Identity Not Proven / Not This Record"
      And it shows the text served and the text the record holds

  # GUARD. Specs change in planning rounds, on purpose, never during a build
  # (Löyly: docs/wow/harness.md, spec-lock).
  @DI-99 @UN-020 @context-harness
  Rule: RDM shall refuse to ratify a build round whose changes since its declared base touch a slice's plan or spec

    @item-01-034
    Scenario: A build round that changed a spec is refused
      Given a build round based on a commit
      And a slice's spec has changed since that commit
      When "harness verify --base" that commit runs
      Then the event is "Round Not Admitted / Spec Changed"
      And it names each plan or spec file changed since the base
      And nothing else is run

    @item-01-035
    Scenario: A build round that left the plan alone passes this step
      Given a build round whose changes since its base touch only code and tests
      When "harness verify --base" that commit runs
      Then the event "Round Admitted" happens

  # -------------------------------------------------------------------------------
  # Configuration
  # -------------------------------------------------------------------------------

  @DI-100 @UN-020 @context-harness
  Rule: RDM shall read everything the harness knows about a project from one declared configuration, and refuse a missing or unknown setting by name

    @item-01-036
    Scenario: The configuration is printed on every run
      Given a harness configuration declaring the suite command, the port, the boot budget and the known answers
      When "harness verify" runs
      Then it prints each setting and its value before the first step

    @item-01-037
    Scenario: An unknown setting is refused
      Given a harness configuration with a setting the harness does not know
      When any "harness" command runs
      Then the event is "Round Not Admitted / Unknown Setting"
      And it names the setting
