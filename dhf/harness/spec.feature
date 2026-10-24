# The harness spec: the measuring instrument, specified.
#
# This file describes the observable behaviour of RDM's harness: how scenarios
# are written and tagged, how ids are stamped, how Allure results verify them,
# what a step definition may touch, how a cut is fenced and regenerated, how a
# cycle is scored, how the C4 model is linted, and the task interface that runs
# all of it. It is the regeneration source for the harness. Decided in
# dhf/decisions/adr-0002-spec-first-regeneration.md.
#
# CONVENTIONS, decided, not open:
#
# - Ids here are `@harness-NNN`, stamped by hand, running forever, never
#   renumbered. The stamper does not stamp this file: it reads only dhf/spec/.
# - This file lives OUTSIDE dhf/spec/ deliberately. The harness is not a
#   product component, and its scenarios must never land in the product
#   checklist.
# - GUARD scenarios cite the incident they guard against in a comment above
#   their Rule. CONTRACT scenarios, which say what a tool does when nothing is
#   wrong, cite nothing. The incidents are Löyly's, from skallinen/loyly
#   harness/README.md, "The failure modes this shape was learned from".
# - "the spec" means dhf/spec/, "a project" means any repository laid out the
#   way RDM sets one up, RDM itself included.
# - The level, need and @wip rules below govern the spec. This file's own
#   scenarios carry only their id until the harness that checks them exists.

Feature: The harness

  Rule: A scenario is the unit of requirement and carries exactly one id

    @harness-001
    Scenario: The stamper gives every unstamped scenario the next id from the ledger
      Given the ledger records 41 as the highest id issued
      And a scenario in the spec carries no id tag
      When the stamper runs
      Then the scenario is tagged @spec-0042
      And the ledger records 42 as the highest id issued

    @harness-002
    Scenario: Stamping twice changes nothing
      Given every scenario in the spec carries an id
      When the stamper runs
      Then no file in the spec changes
      And the ledger does not change

    @harness-003
    Scenario: A deleted scenario's id is never issued again
      Given the ledger records 42 as the highest id issued
      And the scenario tagged @spec-0042 has been deleted
      And a new scenario carries no id tag
      When the stamper runs
      Then the new scenario is tagged @spec-0043

    @harness-004
    Scenario: The id does not depend on the wording or the location
      Given a scenario tagged @spec-0007 in one component's feature file
      When its title is reworded and it is moved to another component's feature file
      Then the stamper leaves it tagged @spec-0007

    @harness-005
    Scenario: A scenario with two ids fails the gate
      Given a scenario tagged both @spec-0007 and @spec-0008
      When the gate runs
      Then it fails and names the scenario and both ids

    @harness-006
    Scenario: An id used by two scenarios fails the gate
      Given two scenarios both tagged @spec-0007
      When the gate runs
      Then it fails and names both files and the id

    @harness-007
    Scenario: The id prefix is configuration
      Given the project's configuration sets the id prefix to "loyly"
      And a scenario in the spec carries no id tag
      When the stamper runs
      Then the scenario is tagged with an id starting @loyly-

  Rule: A scenario carries exactly one verification level

    @harness-008
    Scenario Outline: Each level is accepted
      Given a stamped scenario tagged <level>
      When the gate runs
      Then the scenario passes the level check

      Examples:
        | level     |
        | @e2e      |
        | @contract |
        | @example  |
        | @property |
        | @persona  |

    @harness-009
    Scenario: A scenario with no level fails the gate
      Given a stamped scenario with no level tag
      When the gate runs
      Then it fails and names the scenario's id and the five levels

    @harness-010
    Scenario: A scenario with two levels fails the gate
      Given a stamped scenario tagged both @e2e and @contract
      When the gate runs
      Then it fails and names the scenario's id and both levels

  Rule: Every scenario traces to a user need

    @harness-011
    Scenario: A need tag on the feature is inherited by every scenario under it
      Given a feature tagged @UN-003
      And a scenario under it carries no need tag of its own
      When the gate runs
      Then the scenario traces to UN-003

    @harness-012
    Scenario: A scenario that traces to no need fails the gate
      Given a stamped scenario with no need tag on it, its rule or its feature
      When the gate runs
      Then it fails and names the scenario's id

    @harness-013
    Scenario: A need tag naming no declared need fails the gate
      Given the declared needs are UN-001 to UN-005
      And a scenario tagged @UN-009
      When the gate runs
      Then it fails and names UN-009 and the scenario's id

  Rule: Planned work is a scenario tagged @wip

    @harness-014
    Scenario: The gate accepts a scenario tagged @wip
      Given a stamped scenario tagged @wip with no passing result
      When the gate runs
      Then the gate passes
      And the status report lists the scenario as planned

    @harness-015
    Scenario: The release check refuses while any scenario is tagged @wip
      Given a stamped scenario tagged @wip
      When the release check runs
      Then it fails and names the scenario's id

    @harness-016
    Scenario: Status is derived from the results, never written
      Given three stamped scenarios: one passing, one failing, one with no result
      When the status report runs
      Then it lists one as verified, one as failed and one as unverified
      And no file in the spec changes

  # The parser is ours. A construct it does not know is refused, never skipped:
  # a scenario the parser silently drops is a requirement nothing checks.
  Rule: The spec is written in a declared subset of Gherkin

    @harness-017
    Scenario: The supported constructs parse
      Given a feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables
      When the parser reads it
      Then every scenario is read with its tags, steps and arguments

    @harness-018
    Scenario: An unknown keyword is refused with its line
      Given a feature file containing a line starting with "Scenario Template:"
      When the parser reads it
      Then it refuses and names the file, the line number and the keyword

    @harness-019
    Scenario: A feature file holds exactly one feature
      Given a feature file with two Feature lines
      When the parser reads it
      Then it refuses and names the file and the second Feature line

    @harness-020
    Scenario: A scenario outline is one requirement, verified by all its rows
      Given a stamped scenario outline with three example rows
      And Allure results for two rows passed and one failed
      When the results are read
      Then the scenario is failed

  # Incident: a test runner named its suites twice, once in imports and once in
  # a run list, so a forgotten suite was silently green.
  Rule: Allure results are the oracle, whoever wrote them

    @harness-021
    Scenario: A passed result with the scenario's id verifies it
      Given a stamped scenario @spec-0007
      And an Allure result labelled spec=spec-0007 with status passed
      When the results are read
      Then the scenario is verified

    @harness-022
    Scenario Outline: Any status but passed does not verify
      Given a stamped scenario @spec-0007
      And an Allure result labelled spec=spec-0007 with status <status>
      When the results are read
      Then the scenario is failed

      Examples:
        | status  |
        | failed  |
        | broken  |
        | skipped |
        | unknown |

    @harness-023
    Scenario: One failing result among several fails the scenario
      Given a stamped scenario @spec-0007
      And two Allure results labelled spec=spec-0007, one passed and one failed
      When the results are read
      Then the scenario is failed

    @harness-024
    Scenario: A scenario with no result is unverified, never left out
      Given ten stamped scenarios
      And Allure results for nine of them
      When the results are read
      Then the report counts ten scenarios
      And the tenth is unverified

    @harness-025
    Scenario: A result naming no scenario is reported, not counted
      Given an Allure result labelled spec=spec-9999
      And no scenario in the spec is tagged @spec-9999
      When the results are read
      Then the report lists spec-9999 as an unknown result
      And it counts toward no scenario

    @harness-026
    Scenario: An empty results directory is refused
      Given the results directory exists and holds no Allure result
      When the results are read
      Then it refuses and says no test run wrote results there

    @harness-027
    Scenario: A results file that cannot be read is named, never skipped
      Given a results directory holding one result file that is not valid JSON
      When the results are read
      Then the report names that file as unreadable

    @harness-028
    Scenario: The stack that wrote the results does not matter
      Given Allure results labelled with spec ids written by a JavaScript test run
      When the results are read
      Then the scenarios they name are verified exactly as if Clojure had written them

  # A step definition that loads implementation code pins the implementation's
  # internal design in place, and a regeneration graded by it measures nothing.
  Rule: Step definitions drive the public surface only

    @harness-029
    Scenario: A step definition that requires an implementation namespace fails the gate
      Given the configuration declares the implementation namespace prefix "rdm."
      And a step definition namespace requires rdm.spec.reader
      When the gate runs
      Then it fails and names the step file and the required namespace

    @harness-030
    Scenario: A step definition may run the command and touch files
      Given a step definition namespace that requires only babashka.process, babashka.fs and the harness support namespace
      When the gate runs
      Then the step file passes the black-box check

    @harness-031
    Scenario: A step with no definition fails the run, not the scenario's absence
      Given a stamped scenario with a step no step definition matches
      When the acceptance run executes
      Then the scenario's Allure result is broken
      And the result names the unmatched step

  # Incident: an agent that can read last cycle's scores can aim at the
  # checklist instead of the spec. Regeneration reads a cut, nothing else.
  Rule: A cut is a tagged merge, and regeneration reads only a cut

    @harness-032
    Scenario: The cut is a tag on a commit of the default branch
      Given the merge commit of a spec change on the default branch
      When the cut is made
      Then the commit is tagged spec-N, with N one more than the last cut

    @harness-033
    Scenario: The fence refuses a source that is not a cut
      Given a commit that carries no spec-N tag
      When the fence is asked to build a clean room from it
      Then it refuses and says regeneration reads only a cut

  # Incident: a weaker fence probe once passed while the previous implementation
  # was still reachable from an older commit.
  Rule: The fence is physical and proved

    @harness-034
    Scenario: The clean room holds no fenced path, in the tree or in history
      Given the configuration fences the implementation and every measurement
      When the fence builds a clean room from cut spec-3
      Then the clean room has a single root commit
      And no fenced path exists in its working tree
      And no object reachable in its repository belongs to a fenced path

    @harness-035
    Scenario: A fenced path that matches nothing is a hard failure
      Given the configuration fences a path that does not exist at the cut
      When the fence builds a clean room
      Then it fails and names the path

    @harness-036
    Scenario: The clean room keeps the spec, the harness spec and the step definitions
      When the fence builds a clean room from a cut
      Then the spec, the harness spec, the step definitions and the configuration are present and unchanged

  # Incident: an orphaned process from a dead run once answered a health probe,
  # so a scoring run graded the wrong process. Incident: a gate once reported
  # success over a test command that had become a no-op exiting 0.
  Rule: Done is ratified by a gate the builder does not control

    @harness-037
    Scenario: The gate runs the declared test command and requires results
      Given the configuration declares the test command
      When the gate runs it and it exits 0 without writing any Allure result
      Then the gate fails and says a green exit with no results is not a run

    @harness-038
    Scenario: The gate refuses when the port is already held
      Given another process holds the configured port
      When the gate is asked to start a container that serves on it
      Then it refuses before starting anything and names the port

    @harness-039
    Scenario: A container that does not answer within the boot budget fails
      Given the configuration declares a boot budget of 10 seconds
      And a container that does not answer within it
      When the gate starts the container
      Then it fails and names the budget in seconds

    @harness-040
    Scenario: A green gate reports the margin left in its boot budget
      Given a container that answers after 1 second of a 10 second budget
      When the gate starts the container
      Then the gate reports how much of the budget the boot used

    @harness-041
    Scenario: The identity probe proves the thing answering is this project
      Given the project's identity probe
      And a different program answering on the configured port
      When the gate runs the probe
      Then the gate fails with what the probe printed

  # Incident: a scoring run whose every batch failed on a network outage once
  # committed "scored" over an empty record.
  Rule: The instrument refuses to record what it did not measure

    @harness-042
    Scenario: Scoring with no results records nothing
      Given a cycle with no Allure results
      When the cycle is scored
      Then nothing is written
      And the scorer exits non-zero and says no measurement was made

    @harness-043
    Scenario: The checklist is derived from the spec, one item per stamped scenario
      Given a spec with 12 stamped scenarios, one of them tagged @wip
      When the checklist is derived
      Then it holds 12 items, each with the scenario's id, title, component and level

    @harness-044
    Scenario: An unverified item is scored as a fail
      Given a checklist of 12 items
      And results for 11 of them, all passed
      When the cycle is scored
      Then the score is 11 of 12

  Rule: The scoreboard compares cycles honestly

    @harness-045
    Scenario: Both directions are counted, never the net
      Given cycle 01 and cycle 02 scored over the same 12 items
      And 3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01
      When the scoreboard compares them
      Then it reports 3 got better and 1 got worse
      And it reports no net figure

    @harness-046
    Scenario: A moved denominator is said out loud
      Given cycle 01 scored 12 items and cycle 02 scored 15
      When the scoreboard compares them
      Then it compares only the 12 items both scored
      And it states that the denominator moved from 12 to 15
      And it counts the 3 new items separately

    @harness-047
    Scenario: The scoreboard is for a person, never for the regenerating agent
      When the fence builds a clean room from a cut
      Then no scoreboard and no cycle score is present in it

  Rule: The previous release judges the next

    @harness-048
    Scenario: Regenerated RDM is gated by the pinned release
      Given the configuration pins RDM release 0.4.0 as the judge
      And a regenerated RDM in the clean room
      When the regeneration is gated and scored
      Then release 0.4.0 runs the gate and the scorer
      And the regenerated build runs only as the thing under test

  Rule: The C4 model is one source and every view is generated from it

    @harness-049
    Scenario: Every view in the model is generated and rendered
      Given a model with a context view, a container view and two component views
      When the views are generated
      Then each view is written as Mermaid C4 and rendered to SVG
      And each output carries the hash of the model it came from

    @harness-050
    Scenario: A view older than the model fails the gate
      Given a rendered view whose recorded hash differs from the model's
      When the gate runs
      Then it fails and names the stale view

    @harness-051
    Scenario: A rendered view whose layout overlaps a label with a box fails
      Given a view whose computed layout places a relationship label over an element
      When the render check runs
      Then it fails and names the view, the label and the element

  # The rules below are the C4 skill's quality checklist where a machine can
  # judge it (scope-impact/agent-skills, skills/engineering/c4-diagrams). The
  # linter records the skill commit it was written against and warns when the
  # skill's guide has changed since, so a new checklist item is noticed.
  Rule: The C4 model passes the C4 lint

    @harness-052
    Scenario: A view with no title or description fails the lint
      Given a view with no description
      When the lint runs
      Then it fails rule c4-scope and names the view

    @harness-053
    Scenario: An element with no description fails the lint
      Given a container with no description
      When the lint runs
      Then it fails rule c4-element-described and names the container

    @harness-054
    Scenario: An unlabelled relationship fails the lint
      Given a relationship with no description
      When the lint runs
      Then it fails rule c4-arrow-labelled and names both ends

    @harness-055
    Scenario: A component view that shows another container's components fails the lint
      Given the component view of one container includes a component of another
      When the lint runs
      Then it fails rule c4-one-level and names the view and the component

    @harness-056
    Scenario: A context view with no person fails the lint
      Given a context view that includes no person
      When the lint runs
      Then it fails rule c4-context-actors and names the view

    @harness-057
    Scenario: A container that neither runs nor stores fails the lint
      Given a container declared with kind library
      When the lint runs
      Then it fails rule c4-container-runs and names the container

    @harness-058
    Scenario: A relationship that publishes an unknown event fails the lint
      Given a relationship that publishes the event SpecCut
      And SpecCut is not in the event catalogue
      When the lint runs
      Then it fails rule c4-events-named and names the relationship and the event

    @harness-059
    Scenario: A view with more elements than the configured limit warns
      Given the configured view limit is 20 elements
      And a view showing 23 elements
      When the lint runs
      Then it warns under rule c4-view-size and names the view and both counts

    @harness-060
    Scenario: The lint prints the checklist items no machine can judge
      When the lint runs
      Then it lists, for a person to answer, whether the view serves its audience, whether the true coupling is visible and whether the render was looked at

  Rule: Each bounded context is one container, and the model owns the spec

    @harness-061
    Scenario: A bounded context with no container, or two, fails the lint
      Given two internal containers that declare the same bounded context
      When the lint runs
      Then it fails rule a-context-container and names both containers

    @harness-062
    Scenario: An internal container with no component view fails the lint
      Given an internal container with components and no component view
      When the lint runs
      Then it fails rule a-component-view and names the container

    @harness-063
    Scenario: A component that owns no feature file fails the lint
      Given a component in the model with no feature file at its path in the spec
      When the lint runs
      Then it fails rule a-scenario-owner and names the component

    @harness-064
    Scenario: A feature file that belongs to no component fails the lint
      Given a feature file in the spec at a path no component of the model names
      When the lint runs
      Then it fails rule a-scenario-owner and names the file

    @harness-065
    Scenario: Components in different containers may not relate directly
      Given a relationship from a component of one container to a component of another
      When the lint runs
      Then it fails rule a-contract-only and names the relationship

  Rule: Everything runs through one task interface

    @harness-066
    Scenario Outline: Each task is a Babashka task
      Given a project set up by RDM
      When the task list is printed
      Then it includes <task>

      Examples:
        | task       |
        | stamp      |
        | gate       |
        | c4         |
        | fence      |
        | verify     |
        | score      |
        | scoreboard |
        | check      |

    @harness-067
    Scenario: The pre-commit hook runs the check, and needs no shell
      Given the pre-commit hook is installed
      When a commit is made
      Then the hook runs the check task through Babashka
      And the commit is refused when the check fails
