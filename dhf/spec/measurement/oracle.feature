# Component Oracle, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
#
# rdm-spec regenerates its own instrument every cycle, so the instrument needs
# a judge of its own (cycle 03, dhf/cycles/03/proposed-spec-changes.md, C12;
# loyly:harness/README.md@17d68d2, "The pieces, in pipeline order"). That judge
# must not be regenerated with what it judges: an oracle rebuilt from the same
# spec agrees with whatever the spec got wrong, and its goldens broke at each
# rebuild (cycles 16 to 18; dhf/cycles/18/proposed-spec-changes.md). So, as
# Löyly holds its harness still, the oracle is not this component: it is a
# command the project keeps by hand, outside its implementation (for rdm-spec,
# dhf/harness/oracle/oracle.clj, which says what it judges and how). This
# component only runs that command as the gate before fence and verify
# (spec owner, spec-23). The scenarios that described the oracle's own
# workings (spec-0228 to spec-0231, spec-0276, spec-0277, spec-0280,
# spec-0325 to spec-0328) are removed with it.

@UN-008
Feature: Oracle
  Runs the oracle the project keeps, a command its configuration names, and
  stops fence and verify while it fails.

  Rule: The oracle is a command the project keeps, run as it is

    @spec-0342 @e2e
    Scenario: The oracle task runs the configured command and relays its answer
      Given a configuration whose :oracle is {:command ["bb" "dhf/harness/oracle/oracle.clj"]}, a command that prints {:oracle {:differences 0}} and exits 0
      When "rdm-spec oracle" is run
      Then it runs the command in the project's root, prints its standard output and error stream unchanged, and exits as it exits

    @spec-0343 @e2e
    Scenario: The oracle task passes its own arguments on
      Given a configuration whose :oracle names a command
      When "rdm-spec oracle record --reason 'the spec grew'" is run
      Then the command runs with the arguments record, --reason and the spec grew, in that order

    @spec-0344 @contract
    Scenario: The oracle command is told which rdm-spec it judges
      Given a configuration whose :oracle names a command
      When "rdm-spec oracle" is run
      Then the command runs with the environment variable RDM_SPEC set to a command line that runs this same rdm-spec, so the oracle judges the instrument that is about to gate

    @spec-0345 @e2e
    Scenario: An oracle command that cannot be started cannot run
      Given a configuration whose :oracle names the command ["no-such-oracle"]
      When "rdm-spec oracle" is run
      Then it exits 2 and names the command

  # A room built, or a build verified, by an instrument nobody checked hands
  # the next cycle a judge nobody checked (loyly:harness/README.md@17d68d2,
  # "What makes the checks run").
  # The oracle is a gate stage of fence and verify: the command runs it first
  # and stops the task when it fails, so neither fence nor verify knows the
  # oracle (dhf/spec/kernel/command.feature, "A gate stops the task").
  Rule: Nothing is fenced or verified while the oracle is red

    @spec-0232 @e2e
    Scenario Outline: Fence and verify refuse while the oracle fails
      Given a project whose configured oracle command exits 1, printing a finding on its error stream
      When "rdm-spec <task>" is run
      Then it exits 1, prints the oracle's findings unchanged, and <does-nothing>

      Examples:
        | task                  | does-nothing                       |
        | fence ROOM spec-3     | builds no room                     |
        | verify ROOM 03        | writes no ratified.edn             |

    @spec-0233 @e2e
    Scenario: A project with no oracle is not refused
      Given a project whose configuration names no :oracle
      When "rdm-spec oracle" is run
      Then it exits 0 and says there is no oracle to run
