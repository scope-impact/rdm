@UN-008
Feature: Oracle
  Runs the oracle the project keeps: the plugin named "oracle", which answers
  :Gate, so fence and verify stop while it fails.

  Rule: The oracle task runs the plugin named "oracle", as it is

    @spec-0342 @e2e
    Scenario: The oracle task runs the configured command and relays its answer
      Given a configuration whose :plugins holds {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}, a command that prints {:oracle {:differences 0}} and exits 0
      When "rdm-spec oracle" is run
      Then it runs the command in the project's root, prints its standard output and error stream unchanged, and exits as it exits

    @spec-0343 @e2e
    Scenario: The oracle task passes its own arguments on
      Given a configuration whose plugin "oracle" names a command
      When "rdm-spec oracle record --reason 'the spec grew'" is run
      Then the command runs with the arguments record, --reason and the spec grew, in that order

    @spec-0439 @e2e
    Scenario: The oracle task runs its plugin with no time limit and nothing on its input
      Given a configuration whose plugin "oracle" has :seconds 1 and a command that writes what its standard input holds to a file in the project, sleeps 3 seconds, prints {:oracle {:differences 0}} and exits 0
      When "rdm-spec oracle" is run
      Then it exits 0 and prints {:oracle {:differences 0}}, and the file is empty

    @spec-0344 @contract
    Scenario: The oracle command is told which rdm-spec it judges
      Given a configuration whose plugin "oracle" names a command
      When "rdm-spec oracle" is run
      Then the command runs with the environment variable RDM_SPEC set to a command line that starts this same rdm-spec, however it was installed (from its sources or as a package), so the oracle judges the instrument that is about to gate
      And "$RDM_SPEC help", run by the oracle, answers as "rdm-spec help" does

    @spec-0345 @e2e
    Scenario: An oracle command that cannot be started cannot run
      Given a configuration whose plugin "oracle" names the command ["no-such-oracle"]
      When "rdm-spec oracle" is run
      Then it exits 2 and names the command

  Rule: Nothing is fenced or verified while the oracle is red

    @spec-0232 @e2e
    Scenario Outline: Fence and verify refuse while the oracle fails
      Given a project whose plugin "oracle", answering :Gate, exits 1, writing a finding on its error stream
      When "rdm-spec <task>" is run
      Then it exits 1, prints the oracle's findings unchanged, and <does-nothing>

      Examples:
        | task                  | does-nothing                       |
        | fence ROOM spec-3     | builds no room                     |
        | verify ROOM 03        | writes no ratified.edn             |

    @spec-0233 @e2e
    Scenario: A project with no oracle is not refused
      Given a project whose :plugins names no plugin "oracle"
      When "rdm-spec oracle" is run
      Then it exits 0 and says there is no oracle to run

    @spec-0440 @contract
    Scenario: The oracle task's result when there is no oracle has one shape
      Given a project whose :plugins names no plugin "oracle"
      When "rdm-spec oracle" is run
      Then its standard output, read as EDN, is {:oracle {:says S}}, S a string saying there is no oracle to run, and its error stream is empty
