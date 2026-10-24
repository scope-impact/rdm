# Component Tasks, container project: dhf/spec/model.edn.

@UN-009
Feature: Tasks
  The one task interface everything runs through.

  Rule: Everything runs through one task interface

    @spec-0026 @e2e
    Scenario Outline: Each task is a Babashka task
      Given a project set up by rdm-spec
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
