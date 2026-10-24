# Kernel component Gherkin runner: dhf/spec/model.edn, :kernel.
#
# Decided by the event storm's hotspot H10 (dhf/storm/2026-10-24-rdm.md): the
# runner is kernel code, so any project whose step definitions are written for
# it can use it. A project in another stack uses its own runner and only has
# to write the same Allure results.

@UN-003
Feature: Gherkin runner
  Runs the scenarios' steps against their step definitions and writes Allure results.

  # rdm-spec run is offered to any project whose step definitions are written
  # for it, so the way a step is defined is a public contract.
  Rule: Step definitions are written against one small API

    @spec-0168 @contract
    Scenario: A step is defined by a pattern and a function
      Given a step definition file in the project's configured step directory that calls (defstep #"the spec holds (\d+) features" (fn [ctx n] ...)) from rdm-spec.kernel.runner
      When the runner runs a scenario with the step "Given the spec holds 20 features"
      Then the function is called with the scenario's context and "20"

    @spec-0169 @contract
    Scenario: Each step's return is the next step's context, and each scenario starts empty
      Given two steps whose functions each add a key to the context they are given
      When the runner runs a scenario with both steps
      Then the second function receives the key the first added
      And the next scenario's first step receives an empty context

    @spec-0170 @contract
    Scenario: A step two definitions match makes the result broken, naming both
      Given two step definitions whose patterns both match "Given a spec"
      When the runner runs a scenario with that step
      Then its result is broken and names both definitions

  Rule: Every scenario run leaves one result, labelled with what it verifies

    @spec-0151 @contract
    Scenario: A scenario's result is labelled with its id, component and context
      Given the scenario @spec-0012 in the component Lint of the bounded context architecture
      When the runner runs it
      Then it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture
      And a kernel scenario's result is labelled context=kernel
      And each step is a step of the result, with its own status

    @spec-0152 @contract
    Scenario: A scenario outline writes one result per example row, all with the outline's id
      Given a stamped scenario outline with three example rows
      When the runner runs it
      Then it writes three Allure results, each labelled with the outline's id and naming its row

    @spec-0153 @e2e
    Scenario: A failing step fails the result and names the step
      Given a scenario whose second step's definition throws
      When the runner runs it
      Then the result is failed and names the second step and the error
      And the steps after it are reported as not run

    @spec-0032 @e2e
    Scenario: A step with no definition makes the result broken
      Given a stamped scenario with a step no step definition matches
      When the runner runs it
      Then the scenario's Allure result is broken
      And the result names the unmatched step

    @spec-0154 @e2e
    Scenario: A planned scenario is not run, and its result says it is planned
      Given a stamped scenario tagged @wip
      When "rdm-spec run" is run
      Then the scenario's steps are not run
      And its result is skipped and labelled planned

  # Incident: a test runner named its suites twice, once in imports and once in
  # a run list, so a forgotten suite was silently green (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").
  Rule: The runner discovers, and refuses to find nothing

    @spec-0155 @e2e
    Scenario: The runner runs every feature file the spec holds, found by looking, never listed
      Given a spec holding twenty feature files
      When "rdm-spec run" is run
      Then it runs the scenarios of all twenty

    @spec-0156 @e2e
    Scenario: A run that finds no feature file is refused
      Given a spec directory holding no feature file
      When "rdm-spec run" is run
      Then it refuses and says no scenario was found, and writes no result
