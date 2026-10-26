# Component Oracle, bounded context measurement of the rdm-spec container: dhf/spec/model.edn.
#
# rdm-spec regenerates its own instrument every cycle, so the instrument needs
# a judge of its own: recorded fixture cycles and the outputs the instrument
# must give on them (cycle 03, dhf/cycles/03/proposed-spec-changes.md, C12;
# loyly:harness/README.md@17d68d2, "The pieces, in pipeline order").

@UN-008
Feature: Oracle
  Runs every component that judges (the checklist, the scorer, the scoreboard,
  the oracle itself, verify, status, scopes, fence and the spec check) on
  recorded fixtures, and compares what each produces with its recorded
  goldens.

  # The layout was never written down, and the oracle never enters a clean
  # room, so cycle 08's builder invented one and its oracle refused this
  # repository's own goldens (dhf/cycles/08/proposed-spec-changes.md, 1).
  # The goldens now live outside the repository altogether: one EDN file in
  # one artifact, pinned by digest, so no allow-list can leak them and none
  # can change unseen (dhf/wiki/pages/loyly-method.md, departures).
  Rule: The oracle is one EDN file in one artifact, pinned by digest

    # One file, flat: each golden is keyed by its component's id in the model,
    # the same id scopes, fence and cycle.edn name, so the goldens of a cycle's
    # scopes are found by the scope itself (spec owner, after cycle 16: its
    # scopes regressed and its oracle changed its own output, and no golden
    # judged either). The fixtures many components read are shared, once.
    @spec-0276 @contract
    Scenario: The oracle reads one oracle.edn, flat, one key per judging component
      Given the configured oracle holds fixture cycles, fixture result sets, fixture cuts, and goldens for every judging component
      When "rdm-spec oracle" is run
      Then it reads the artifact the configuration's :oracle names by its digest, holding the one file oracle.edn:
        """
        {:project  {"dhf/spec/model.edn" "..." ...}
         :cycles   {"01" {:cycle {...} :ratified {...} :verdicts [...]} ...}
         :results  {"steps-match" {"<uuid>-result.json" "..." ...} ...}
         :cuts     {"flat-read-by" {"spec-3" {path text ...} "spec-4" {path text ...}} ...}
         :measurement/checklist  {"01" golden ...}
         :measurement/scorer     {"01" golden ...}
         :measurement/scoreboard {"01-02" golden ...}
         :measurement/oracle     {"self" golden}
         :verification/verify    {"steps-match" golden ...}
         :verification/status    {"outline-rows" golden ...}
         :regeneration/scopes    {"flat-read-by" golden ...}
         :regeneration/fence     {"removed-component" golden ...}
         :spec/check             {"refusals" golden}
         :recorded [{:date "2026-10-26" :reason "..." :changed [{:component :regeneration/scopes :case "flat-read-by"}]} ...]}
        """
      And it exits 0 and names each golden it compared, as its component and case

    # The oracle's own result changed shape in cycle 16 (:differences became
    # :differs) and nothing noticed: no scenario pinned it
    # (dhf/cycles/16/proposed-spec-changes.md, 4).
    @spec-0325 @contract
    Scenario: The oracle's result is exactly this shape
      Given the configured oracle holds goldens for the components :measurement/scorer and :regeneration/scopes, one case each, both reproduced
      When "rdm-spec oracle" is run
      Then it exits 0, and its standard output, read as EDN, is exactly {:oracle {:repository R :digest D :compared [{:component :measurement/scorer :case "01"} {:component :regeneration/scopes :case "flat-read-by"}] :differences 0}}, R and D the configuration's, the goldens in the model's order of their components, then by case

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

  # A golden is a component's whole answer on a fixture: its standard output
  # read as data, its exit, and every line it printed on the error stream,
  # word for word, as Löyly's oracle compares every refusal's words
  # (loyly:harness/oracle.sh@17d68d2, CHECK 8). A component that judges
  # nothing in a cycle is still run: the oracle judges the whole instrument.
  Rule: Every component that judges has its goldens, on fixtures the spec states

    @spec-0326 @e2e
    Scenario Outline: Each judging component is run on its fixture cases and compared with their goldens
      Given the configured oracle holds, for <component>, the case <case>: <fixture>
      When "rdm-spec oracle" is run
      Then it runs <component> on that fixture and compares its standard output, exit and error stream with the golden, naming <component> and <case>

      Examples:
        | component              | case              | fixture                                                                 |
        | :verification/verify   | steps-match       | results whose steps are their scenarios' Gherkin lines                  |
        | :verification/verify   | steps-differ      | one result whose second step is reworded                                |
        | :verification/verify   | wrong-story       | one result whose story is not its scenario's rule                       |
        | :verification/verify   | no-need-link      | one result with no issue link to its scenario's need                    |
        | :verification/verify   | no-trace          | an @e2e result with no browser trace, where :evidence requires one      |
        | :verification/status   | outline-rows      | a three-row outline, one row with no result                             |
        | :verification/status   | unknown-result    | a result labelled spec=spec-9999                                        |
        | :regeneration/scopes   | flat-read-by      | two cuts between which a nested key of a key with a flat :read-by changed |
        | :regeneration/scopes   | first-read-by     | two cuts between which an event first gained :read-by                  |
        | :regeneration/scopes   | order             | two cuts between which the kernel, a publisher and its subscriber changed |
        | :regeneration/fence    | removed-component | a cut whose model no longer names a component the earlier cut had       |
        | :spec/check            | refusals          | a spec breaking each rule of the spec check once                        |
        | :measurement/oracle    | self              | the oracle's own run on two reproduced goldens                          |

    # Löyly tells three moves of a golden apart: the behaviour changed (an
    # alarm), the wording was replaced, the spec grew; and the third is
    # tested, never judged: the diff is additions only
    # (loyly:harness/README.md@17d68d2, "The goldens, and the three reasons
    # one can change").
    @spec-0327 @e2e
    Scenario: Recording adds the goldens of new cases freely, and changes an existing one only when named
      Given the configured oracle holds the golden of :regeneration/scopes "order", which the instrument no longer reproduces, and a new case "flat-read-by" with no golden
      When "rdm-spec oracle record --reason 'scopes cases added'" is run
      Then it exits 1, records nothing, and names :regeneration/scopes "order" as an existing golden the record would change

    @spec-0328 @e2e
    Scenario: A named change to an existing golden is recorded with its component and case
      Given the configured oracle holds the golden of :regeneration/scopes "order", which the instrument no longer reproduces
      When "rdm-spec oracle record --reason 'the kernel is listed first' --changes regeneration/scopes:order" is run
      Then it stores a new artifact whose :recorded gains the date, the reason and {:component :regeneration/scopes :case "order"}
      And every other golden is byte for byte what it was
