# Component Observe, bounded context agency of the rdm-spec container: dhf/spec/model.edn.
#
# Every run of a cycle, a builder's, a grader's or the integrator's, has two
# halves: its capability, what it was for, proven by its result (ratify, the
# score), and its defence, what it did outside its brief. "Done" is not the
# builder's claim (loyly:harness/README.md@17d68d2, "The idea in five lines"
# 3), and neither is "stayed in my room". Builders and graders wrote at the
# filesystem's root, beside their rooms and into /tmp in cycles 06 to 11, and
# each was found only because someone looked (dhf/cycles/retrospective-01-12.md).
#
# This component observes: it records, it never prevents, and it never stops
# a run. Reads cannot be observed, and the record says so.
#
# The configuration's :agency {:watch [DIR ...] :tmp DIR} names the
# directories whose entries are compared before and after a run ("~" is the
# home directory) and the temporary directory whose entries are counted. W1,
# T and ROOM are directories the scenario makes.

@UN-014
Feature: Observe
  Records each run's time, cost and what it touched outside its room, beside what it achieved, never netted.

  Rule: A run is observed from outside, begin to end

    @spec-0286 @contract
    Scenario: A run's record has one shape
      Given a project whose configuration watches W1 and whose temporary directory is T
      And "rdm-spec observe begin 13 builder ROOM" has been run
      When "rdm-spec observe end 13 builder --tokens 1200" is run
      Then it exits 0
      And dhf/cycles/13/runs.edn holds one more entry, read as EDN, with exactly the keys :role, :room, :began, :ended, :minutes, :tokens and :defence
      And its :defence holds exactly the keys :writes-outside, :tmp-left, :repo-touched, :results-in-room and :unobserved

    @spec-0287 @e2e
    Scenario: A write outside the room is recorded, and the run is not stopped
      Given a project whose configuration watches W1 and whose temporary directory is T
      And "rdm-spec observe begin 13 builder ROOM" has been run
      And a file W1/stray.txt is written
      When "rdm-spec observe end 13 builder" is run
      Then it exits 0 with a warning naming W1/stray.txt
      And the run's :defence :writes-outside holds W1/stray.txt

    @spec-0288 @e2e
    Scenario: Entries left in the temporary directory are counted
      Given a project whose configuration watches W1 and whose temporary directory is T
      And "rdm-spec observe begin 13 grader ROOM" has been run
      And three entries are made in T
      When "rdm-spec observe end 13 grader" is run
      Then the run's :defence :tmp-left is 3

    @spec-0289 @e2e
    Scenario: A change to the project outside its cycle is recorded
      Given a project whose configuration watches W1 and whose temporary directory is T
      And "rdm-spec observe begin 13 builder ROOM" has been run
      And an untracked file dhf/spec/stray.feature is written in the project
      When "rdm-spec observe end 13 builder" is run
      Then the run's :defence :repo-touched holds dhf/spec/stray.feature

    @spec-0290 @e2e
    Scenario: Results written into the room are recorded
      Given a project whose configuration watches W1 and whose temporary directory is T
      And "rdm-spec observe begin 13 builder ROOM" has been run
      And the configured results directory is written inside ROOM
      When "rdm-spec observe end 13 builder" is run
      Then the run's :defence :results-in-room is true

    @spec-0291 @e2e
    Scenario: What cannot be observed is said, never assumed clean
      Given a project whose configuration watches W1 and whose temporary directory is T
      And "rdm-spec observe begin 13 grader ROOM" has been run
      When "rdm-spec observe end 13 grader" is run
      Then the run's :defence :unobserved names reads outside the room

    @spec-0292 @e2e
    Scenario: Ending a run that never began cannot run
      Given a project whose configuration watches W1 and whose temporary directory is T
      When "rdm-spec observe end 13 builder" is run
      Then it exits 2 and says no run of the role builder began in cycle 13

  # Löyly's scoreboard counts both directions and never the net
  # (loyly:harness/README.md@17d68d2, "The pieces, in pipeline order"); so is
  # defence, and it is never folded into the score.
  Rule: Two cycles' runs are compared side by side, never netted

    @spec-0293 @contract
    Scenario: The report sets two cycles' runs side by side
      Given cycle 12 recorded two runs, one of which wrote outside its room, and cycle 13 recorded one clean run
      When "rdm-spec observe report 12 13" is run
      Then standard output, read as EDN, is exactly:
        """
        {:observe {:cycles ["12" "13"]
                   :runs {"12" 2 "13" 1}
                   :minutes {"12" M12 "13" M13}
                   :tokens {"12" T12 "13" T13}
                   :breaches {"12" 1 "13" 0}}}
        """
      And M12, M13, T12 and T13 are the sums of the cycles' runs
