# Measurement component Grading: dhf/spec/model.edn, :measurement/grading.
#
# Graders grade a verified build black box: they run it and never read it
# (docs/wow/harness.md, "The grader"). Every cycle so far the integrator built
# their rooms by hand: the build packaged outside every room, the cut's spec
# without its code, a share of the checklist, and the grader's brief; the
# wallet of halla-health did the same for its auth (its cycles 01 to 08). This
# is that step, the same for every project (spec owner, spec-28).

@UN-008 @UN-012
Feature: Grading
  Builds the grading rooms of a verified cycle: the build packaged outside
  them, and in each the cut's spec without its code, a share of the
  checklist, and the grader's brief.

  Rule: Only a verified cycle is graded

    @spec-0371 @e2e
    Scenario Outline: A cycle that was not verified, or has no checklist, gets no grading room
      Given a cycle 03 <state>
      When "rdm-spec grade 03 DIR" is run
      Then it exits 1, names what is missing, and writes nothing under DIR

      Examples:
        | state                                         |
        | whose dhf/cycles/03/ratified.edn says :failed |
        | with no dhf/cycles/03/ratified.edn            |
        | verified, with no dhf/cycles/03/checklist.json |

  # The configuration says how the project's build is packaged and run:
  # :grading {:package [command] :run [command] :rooms N :leave-out [path]}.
  Rule: The build is packaged once, outside every room

    @spec-0372 @contract
    Scenario: The package is the verified checkout, packaged by the project's own command
      Given a verified cycle 03 and a configuration whose :grading is {:package ["sh" "package.sh"] :run ["sh" "run.sh"] :rooms 2 :leave-out ["dhf/harness/plugins"]}
      When "rdm-spec grade 03 DIR" is run
      Then DIR/package is a copy of the verified checkout dhf/cycles/03/build in which the package command ran, in that copy
      And no grading room holds DIR/package or any file of it

    @spec-0373 @e2e
    Scenario: A package command that fails stops the grading
      Given a verified cycle 03 whose :grading's package command exits 1, writing "tsc: error" on its error stream
      When "rdm-spec grade 03 DIR" is run
      Then it exits 1, relays "tsc: error", and builds no room

  Rule: A grading room holds the spec, a share of the checklist and the build's runner, never the build

    @spec-0374 @e2e
    Scenario: Each room holds the cut's spec without its code and without what the project leaves out
      Given a verified cycle 03 whose cut holds an implementation, dhf/harness/plugins/oracle/run.sh and dhf/cycles/02/score.edn, and whose :grading leaves out dhf/harness/plugins
      When "rdm-spec grade 03 DIR" is run
      Then each room's project/ holds every file the fence keeps at spec-3, unchanged, except the paths :leave-out names and dhf/cycles
      And it holds no implementation file, no file under dhf/harness/plugins and nothing under dhf/cycles

    @spec-0375 @e2e
    Scenario: The checklist is shared out in order, each item to one room
      Given a verified cycle 03 whose checklist holds 5 items and a configuration whose :grading has :rooms 2
      When "rdm-spec grade 03 DIR" is run
      Then DIR/a/checklist.json holds the first 3 items and DIR/b/checklist.json the last 2, each in checklist.json's shape and order
      And every item is in exactly one room

    @spec-0376 @e2e
    Scenario: Each room's runner runs the package from outside the room
      Given a verified cycle 03 whose :grading's run command is ["node" "dist/main.js"]
      When "rdm-spec grade 03 DIR" is run
      Then each room holds bin/run, executable, which runs "node dist/main.js" in DIR/package with the arguments and environment it is given
      And the process bin/run starts is the run command's own, so a grader can stop it by the PID it started

  Rule: Grading is observed, and the grader is briefed

    @spec-0377 @e2e
    Scenario: The graders' run is observed from the first room
      Given a verified cycle 03
      When "rdm-spec grade 03 DIR" is run
      Then the cycle's observing record holds a run of the role grader begun in DIR/a, as "rdm-spec observe begin 03 grader DIR/a" begins one

    # Briefs: a project keeps the brief a role is given as a template, with
    # {{cycle}}, {{room}}, {{cut}}, {{items}} and {{release}} to fill.
    @spec-0378 @e2e
    Scenario: A project that keeps a grader's brief gets it filled in each room
      Given a project that keeps dhf/harness/briefs/grader.md holding "Grade {{items}} of cycle {{cycle}} in {{room}}, cut {{cut}}; the build is {{release}}"
      When "rdm-spec grade 03 DIR" is run on a checklist of 5 items in 2 rooms
      Then DIR/a/brief.md is "Grade spec-0001 to spec-0003 (3 items) of cycle 03 in DIR/a, cut spec-3; the build is DIR/package" with DIR absolute
      And a project that keeps no brief gets no brief.md, and no finding
