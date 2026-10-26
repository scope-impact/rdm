# Component Oracle, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
#
# rdm-spec regenerates its own instrument every cycle, so the instrument needs
# a judge of its own: recorded fixture cycles and the outputs the instrument
# must give on them (cycle 03, dhf/cycles/03/proposed-spec-changes.md, C12;
# loyly:harness/README.md@17d68d2, "The pieces, in pipeline order").

@UN-008
Feature: Oracle
  Runs the checklist, the scorer and the scoreboard on recorded fixture cycles
  and compares what they produce with recorded goldens.

  # The layout was never written down, and the oracle never enters a clean
  # room, so cycle 08's builder invented one and its oracle refused this
  # repository's own goldens (dhf/cycles/08/proposed-spec-changes.md, 1).
  # The goldens now live outside the repository altogether: one EDN file in
  # one artifact, pinned by digest, so no allow-list can leak them and none
  # can change unseen (dhf/wiki/pages/loyly-method.md, departures).
  Rule: The oracle is one EDN file in one artifact, pinned by digest

    @spec-0276 @contract
    Scenario: The oracle reads one oracle.edn, one key per instrument component
      Given the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each
      When "rdm-spec oracle" is run
      Then it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle.edn:
        """
        {:project    {"dhf/spec/model.edn" "..." ...}
         :cycles     {"01" {:cycle {...} :ratified {...} :verdicts [...]} ...}
         :checklist  {"01" {:cycle "01" :cut "spec-1" :items [...] :planned [...]} ...}
         :scorer     {"01" {...score.edn...} ...}
         :scoreboard {"01-02" {...the :scoreboard...} ...}
         :recorded   [{:date "2026-10-25" :reason "..." :changed [:scorer]} ...]}
        """
      And it exits 0 and names each golden it compared, as its component and key

    @spec-0277 @e2e
    Scenario: An oracle that holds no fixture cycle fails
      Given the configured oracle's oracle.edn holds no fixture cycle
      When "rdm-spec oracle" is run
      Then it exits 1 and says the oracle holds no fixture cycle, so it judged nothing

    @spec-0280 @e2e
    Scenario: An oracle the store does not hold cannot run
      Given the configuration's :oracle names a digest the store does not hold
      When "rdm-spec oracle" is run
      Then it exits 2 and names the repository and the digest

  Rule: The instrument is judged against recorded goldens

    @spec-0228 @e2e
    Scenario: The oracle passes when every output matches its golden
      Given the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each
      When "rdm-spec oracle" is run
      Then it exits 0 and names each golden it compared

    @spec-0229 @e2e
    Scenario: An output that differs from its golden fails the oracle, naming the difference
      Given the configured oracle holds a golden score whose second item says pass where the scorer files fail
      When "rdm-spec oracle" is run
      Then it exits 1 and names the golden, the item and both values

    # Re-recording to get a green destroys the only evidence that anything
    # moved (loyly:harness/README.md@17d68d2, "What makes the checks run").
    @spec-0230 @e2e
    Scenario: Recording goldens says which changed, and why
      Given the configured oracle holds goldens, one of which the instrument no longer reproduces
      When "rdm-spec oracle record --reason 'the scoreboard now says the denominator moved'" is run
      Then it stores a new artifact whose oracle.edn rewrites that golden and appends to :recorded the date, the reason and the component changed
      And the configuration's :oracle names the new digest, left for a person to commit, and the old artifact is unchanged

    @spec-0231 @e2e
    Scenario: Recording without a reason is refused
      Given the configured oracle holds two fixture cycles, their verdicts and the golden checklist, score and scoreboard of each
      When "rdm-spec oracle record" is run
      Then it exits 2, says a reason is required, stores nothing and leaves the configuration's :oracle unchanged

  # A room built, or a build verified, by an instrument nobody checked hands
  # the next cycle a judge nobody checked (loyly:harness/README.md@17d68d2,
  # "What makes the checks run").
  # The oracle is a gate stage of fence and verify: the command runs it first
  # and stops the task when it has findings, so neither fence nor verify knows
  # the oracle (dhf/spec/kernel/command.feature, "A gate stops the task").
  Rule: Nothing is fenced or verified while the oracle is red

    @spec-0232 @e2e
    Scenario Outline: Fence and verify refuse while the oracle fails
      Given a project whose configured oracle holds a golden the instrument does not reproduce
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
