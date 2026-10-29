# The harness spec: the measuring instrument, specified.
#
# This file describes the observable behaviour of the four harness tools:
# stamp_item_ids, gen_checklist, score, scoreboard. It is the regeneration source for
# them, and it is complete enough to reimplement all four without reading any existing
# implementation. Since 2026-09-07 it also carries one Rule for a fifth tool, modules.sh,
# which is not part of the instrument but is what keeps this file the same file in every
# repository that has it.
#
# CONVENTIONS, decided, not open:
#
# - Item ids here are `@harness-NNN`, with NO slice component, running forever, never
#   renumbered. They are stamped by hand. harness/stamp_item_ids does NOT stamp this
#   file: it only looks under docs/plan/.
# - This file lives OUTSIDE docs/plan/ deliberately. The harness is not a product slice,
#   and its scenarios must never land in the product checklist denominator. Anything that
#   generates a checklist from docs/plan/*/spec.feature will not see this file, which is
#   the point.
# - One file, grouped by `Rule:`: the four tools of the instrument, then the adoption
#   check that keeps every copy of this file the same file.
#
# WHICH SCENARIOS CITE AN INCIDENT. The standing rule for the harness is "no incident, no
# scenario": a guard exists because something lied once. But a spec that is a regeneration
# source must also describe ordinary behaviour that no incident produced, or nothing can
# be built from it. So: GUARD scenarios cite their incident in a comment above the Rule
# (drawn from harness/README.md, "The failure modes this shape was learned from").
# CONTRACT scenarios, the ones that only say what the tool does when nothing is wrong,
# cite nothing. A guard whose incident is not recorded anywhere says so in its comment.
#
# THE THREE REPOSITORIES. These tools exist in three repositories as one implementation,
# copied by hand, and the oracle in each is what shows a copy has drifted. Until 2026-09-03
# the copies differed, and this spec stated the TARGET with a comment marking which
# behaviour was not yet in every copy; those comments are kept as history. The target is
# always the stricter one: a guard that exists in one place and not another is a guard
# that has not finished being learned.
#
# Since 2026-09-07 the copies are held identical by harness/modules.sh rather than by
# hand: the tools, this spec, the oracle, the fence and the gate are byte identical, and
# every difference between the repositories is a documented line in harness/config.sh.
# The last Rule in this file is that check, specified.
#
# FILE NAMES AND INVOCATION. Four scripts, one per tool: harness/stamp_item_ids,
# harness/gen_checklist, harness/score, harness/scoreboard, with the extension of the
# language they are written in (today .clj, run by bb; the Python originals were replaced
# 2026-09-03). They are invoked from the repository root as
# "<runner> harness/<name>.<ext> <args>", for example
# "bb harness/gen_checklist.clj --out harness/cycles/01/checklist.json". Two messages name
# a sibling tool as a command for the operator to run, and those two must name the
# implementation's own files (@harness-023, @harness-040); everywhere else the words are
# fixed. The fifth script, harness/modules.sh, is bash and is invoked directly as
# "harness/modules.sh [--siblings PATH...]". The oracle judges it too, in its CHECK 9,
# over fixture repositories it builds for the purpose: this line said for a while that
# the oracle could not, because what modules.sh compares is other repositories, and
# that stopped being true the day CHECK 9 was written.
#
# HOW THIS SPEC IS JUDGED. harness/oracle.sh is the oracle for this file. It runs the
# tools against golden bytes recorded from the implementation in service, and it
# compares stdout, stderr, exit codes and written files byte for byte. Read it as the
# executable half of this spec: where the two disagree, the oracle is what ships.
#
# INVOCATION. The tools are invoked from the repository root. Each resolves the
# repository root from its own file location, as the parent of the harness/ directory
# that holds it, following symlinks. It never uses the current working directory for
# that. This is load bearing: the oracle exercises the tools inside temporary fixture
# trees by copying them in, and a tool that trusted the working directory would read the
# real repository instead of the fixture.
#
# ENCODING. Every file is read and written as UTF-8. Non-ASCII characters in scenario
# names and in evidence are written through literally, never escaped as \uXXXX.
#
# EXIT AND STREAM DISCIPLINE, uniform across all four tools:
# - A refusal prints its message to stderr, one trailing newline, and exits 1.
# - Nothing is ever written to disk on a refusal.
# - Reports that are not the tool's product go to stdout, except gen_checklist's counts
#   line, which goes to stderr so the checklist can be piped.

Feature: The harness: stamp, derive, score, report

  # -------------------------------------------------------------------------------
  # stamp_item_ids
  # -------------------------------------------------------------------------------

  # CONTRACT. What the stamper does when nothing is wrong.
  Rule: The stamper gives every scenario in the plan a stable id

    @harness-001
    Scenario: An untagged scenario is given the next free id in its file
      Given a repository whose only spec is "docs/plan/03-slug/spec.feature":
        """
        Feature: F

          Rule: R one

            Scenario: a first one
              Given a
        """
      When stamp_item_ids runs
      Then "docs/plan/03-slug/spec.feature" reads:
        """
        Feature: F

          Rule: R one

            @item-03-001
            Scenario: a first one
              Given a
        """
      # The slice number is the directory name up to the first "-", taken verbatim as
      # text: "03-slug" gives "03", "12-a-long-name" gives "12". It is never reformatted
      # or re-padded.
      And the running number is formatted with at least three digits, zero padded

    @harness-002
    Scenario: The tag line copies the scenario's own indentation
      Given a spec whose Scenario line is indented by four spaces
      When stamp_item_ids runs
      Then the inserted "@item-" line is indented by the same four spaces
      And it is inserted immediately above the Scenario line, as its own line

    @harness-003
    Scenario: A Scenario Outline is stamped exactly like a Scenario
      Given a spec containing "    Scenario Outline: an outline"
      When stamp_item_ids runs
      Then that outline carries an "@item-" tag on the line above it
      # The recognised heading is a line matching, from the start of the line:
      # optional whitespace, then "Scenario:" or "Scenario Outline:". Nothing else is a
      # scenario. "Scenario Template:" is not recognised.

    @harness-004
    Scenario: The next free number is one past the highest already in the file
      Given a spec that already carries "@item-03-002" and "@item-03-009"
      And the spec has three untagged scenarios below them
      When stamp_item_ids runs
      Then the new tags are "@item-03-010", "@item-03-011" and "@item-03-012"
      # The highest number is taken from EVERY "@item-<digits>-<digits>" occurrence
      # anywhere in the file, on any line, and only the second group is compared. The
      # slice component of an existing tag is ignored when computing the next number,
      # so a stray "@item-99-050" in a 03 slice pushes the next number to 051. Numbering
      # is per FILE, not per slice directory and not global.
      # A file with no existing tags starts at 001.

    @harness-005
    Scenario: Tags that are not item tags do not count as stamped
      Given a spec containing:
        """
            @wip @slow
            Scenario: has tags but no item tag
              Given a
        """
      When stamp_item_ids runs
      Then a new "@item-" line is inserted between the "@wip @slow" line and the Scenario
      # The check walks upward from the Scenario line over the CONTIGUOUS run of lines
      # whose stripped text starts with "@", and asks whether any of them contains an
      # item tag. A blank line, a comment, or any other text ends the run, so an item tag
      # separated from its scenario by a blank line does not protect it.

    @harness-006
    Scenario: The report names each file it changed, and the total
      Given two specs, "docs/plan/03-slug/spec.feature" with three new scenarios and
        "docs/plan/07-other/spec.feature" with one
      When stamp_item_ids runs
      Then stdout is exactly:
        """
        stamped   3  docs/plan/03-slug/spec.feature
        stamped   1  docs/plan/07-other/spec.feature
        total stamped: 4
        """
      # The count is right aligned in a field of three, between "stamped " and two
      # spaces. Paths are printed relative to the repository root. Files are visited in
      # sorted order of their path. A file that gained nothing prints no line at all.
      And the exit code is 0

    @harness-007
    Scenario: A file that gains no tags is not rewritten
      Given a spec in which every scenario already carries an item tag
      When stamp_item_ids runs
      Then that file is not written to at all
      And stdout is exactly:
        """
        total stamped: 0
        """
      And the exit code is 0

  # GUARD. Incident: ids that are computed from wording lose the cycle to cycle join the
  # moment a rule is reworded, and then two cycles cannot be compared item by item
  # (harness/README.md, the table row for stamp_item_ids). The id is carried in the spec
  # file itself precisely so that nothing about the scenario's text can change it.
  Rule: An id, once assigned, is never renumbered and never re-derived

    @harness-008
    Scenario: Stamping twice changes nothing
      Given a repository whose specs have already been stamped
      When stamp_item_ids runs
      And stamp_item_ids runs again
      Then no file under "docs/plan/" differs from its state before the first run
      # This is the strongest statement in this Rule and the oracle asserts it with
      # `git diff --quiet -- docs/plan/`. Idempotence is not a convenience here: the
      # stamper is run routinely, and a stamper that renumbered on each run would silently
      # destroy every past cycle's comparability.

    @harness-009
    Scenario: Rewording a scenario does not change its id
      Given a scenario tagged "@item-03-002"
      When its title is rewritten and stamp_item_ids runs
      Then it still carries "@item-03-002"

    @harness-010
    Scenario: Deleting a scenario does not free its number for reuse
      Given a spec whose highest item number is 009, carried by a scenario
      When that scenario is deleted and a new scenario is added and stamp_item_ids runs
      Then the new scenario is NOT given 009
      # It is given one past the highest number STILL PRESENT in the file. Deleting the
      # highest-numbered scenario does lower the high-water mark, and that is a known
      # limitation of keeping the counter in the file rather than in a ledger. It is
      # accepted: deletion plus re-add inside one cycle is the only way to hit it, and
      # the id remains stable for every scenario that continues to exist.

  # GUARD. Origin unknown: no incident in harness/README.md produced this one. It was
  # added in one repository as the mirror of gen_checklist's empty-spec refusal, on the
  # reasoning written into that file: stamping nothing records nothing, so it cannot lie,
  # but a bare "total stamped: 0" reads identically whether the spec is fully stamped or
  # absent, and those are very different states to be in. Present in one of the three
  # copies today; the target is all of them.
  Rule: The stamper says where it looked when it finds no spec at all

    @harness-011
    Scenario: No spec files anywhere
      Given a repository with no "docs/plan/NN-slug/spec.feature" at all
      When stamp_item_ids runs
      Then stdout is exactly, with <PLAN> the absolute path of the docs/plan directory:
        """
        stamp: no NN-slug/spec.feature under <PLAN>: nothing to stamp yet.
               (the spec is written first; stamping is idempotent, run it again then)
        """
      And the exit code is 0
      # Exit 0, not 1. An absent spec is a legitimate state early in a project; an
      # absent spec at CHECKLIST time is not, and that is where the refusal lives.
      # The second line is indented by seven spaces so that it hangs under the first.
      # The path is absolute here, unlike every other path the tools print, because the
      # question this message answers is "where did you look".

  # -------------------------------------------------------------------------------
  # gen_checklist
  # -------------------------------------------------------------------------------

  # CONTRACT. The checklist is DERIVED from the spec, one item per stamped scenario. It
  # is never hand written, because a hand-kept list goes stale the day someone adds a
  # scenario and forgets the list.
  Rule: The checklist is one item per stamped scenario, and the spec is its only author

    @harness-012
    Scenario: The shape of a generated checklist
      Given "docs/plan/01-a/spec.feature":
        """
        Feature: F

          Rule: R one

            @item-01-001
            Scenario: a first one
              Given a
        """
      When gen_checklist runs with no --out
      Then stdout is exactly:
        """
        {
         "items": [
          {
           "id": "01-001",
           "name": "a first one",
           "rule": "R one",
           "source": "docs/plan/01-a/spec.feature:6"
          }
         ],
         "counts": {
          "total": 1
         }
        }
        """
      # Every detail above is contract:
      #  - JSON, indent of ONE space per level, not two and not four;
      #  - separators are ", " between a key and its value and a bare "," before the
      #    newline, which is what a one-space-indented pretty printer produces;
      #  - key order within an item is exactly id, name, rule, source, and it is
      #    insertion order, not alphabetical;
      #  - top level key order is items then counts;
      #  - "counts" holds exactly one key, "total", the number of items;
      #  - non-ASCII is written literally, not escaped;
      #  - printed to stdout, one trailing newline.
      And stderr is exactly:
        """
        checklist: {'total': 1}
        """
      # That line is a Python dict repr and it is now the contract: literally
      # "checklist: {'total': N}", with single quotes around the key and one space after
      # the colon. It goes to STDERR so that stdout stays pipeable JSON.
      And the exit code is 0

    @harness-013
    Scenario: Writing the checklist to a file
      Given a spec that yields eight items
      When gen_checklist runs with "--out harness/cycles/01/checklist.json"
      Then "harness/cycles/01/checklist.json" holds the same JSON text
      And it ends with exactly one newline
      And nothing is printed to stdout
      And stderr is exactly:
        """
        checklist: {'total': 8}
        """
      # Missing parent directories of the --out path are created. --out is the only
      # option the tool takes; it is optional and takes one value.

    @harness-014
    Scenario: Items are ordered by file, then by position in the file
      Given specs "docs/plan/01-a/spec.feature" and "docs/plan/02-b/spec.feature"
      When gen_checklist runs
      Then every item from 01-a precedes every item from 02-b
      And within a file, items appear in the order their scenarios appear
      # Files are found with the glob "docs/plan/[0-9]*/spec.feature", relative to the
      # repository root, and sorted by path as text. Note the glob: a slice directory
      # whose name does not begin with a digit is not part of the spec, and nothing
      # warns about it.
      # Items are NOT sorted by id. A file whose ids run 002, 010, 011, 009 produces a
      # checklist in exactly that order, because position in the spec is the ordering the
      # reader of the spec has in their hand.

    @harness-015
    Scenario: An item carries the Rule it sits under
      Given a spec with "Rule: R one" above two scenarios and "Rule: R two" above a third
      When gen_checklist runs
      Then the first two items carry "rule": "R one" and the third carries "rule": "R two"
      # The current rule is the most recent "Rule:" line seen while reading the file top
      # to bottom, with surrounding whitespace stripped from its text. It is reset to
      # nothing at the start of each FILE, and never by a "Feature:" line, so a second
      # Feature in one file inherits the rule above it.

    @harness-016
    Scenario: A scenario under no Rule carries a null rule
      Given a spec with a scenario and no "Rule:" line anywhere above it
      When gen_checklist runs
      Then that item's "rule" is JSON null, not the empty string and not an absent key

    @harness-017
    Scenario: The source is the file and the line of the Scenario line itself
      Given a scenario whose "Scenario:" line is line 6 of "docs/plan/01-a/spec.feature"
      When gen_checklist runs
      Then that item's "source" is "docs/plan/01-a/spec.feature:6"
      # Lines are numbered from 1. The line recorded is the Scenario line, NOT the tag
      # line above it. The path is relative to the repository root, with forward slashes.

    @harness-018
    Scenario: The name is the scenario title with surrounding whitespace stripped
      Given a scenario line "    Scenario:   a padded title   "
      When gen_checklist runs
      Then that item's "name" is "a padded title"

    @harness-019
    Scenario: A tag line is recognised only when the line begins with an at sign
      Given a line whose stripped text starts with "@" and contains "@item-01-004"
      When gen_checklist runs
      Then the next scenario takes 01-004 as its id
      # A line containing "@item-01-004" that does NOT begin with "@" after stripping,
      # such as a comment or a step, is not a tag line and is ignored entirely.
      # When a tag line carries several tags, or several tag lines stack up, the LAST
      # item tag seen before the scenario wins.

    @harness-020
    Scenario: A pending tag survives lines that are neither tags nor scenarios
      Given a spec:
        """
        @item-01-004

          Rule: R one
          # a comment

            Scenario: reached at last
        """
      When gen_checklist runs
      Then the item is "01-004", with rule "R one"
      # This is a WART, specified because it is observable and a reimplementation that
      # "tidied" it would change the product. A pending tag is cleared ONLY by being
      # consumed by a Scenario line. Nothing else clears it: not a blank line, not a
      # Rule, not a Feature, not the end of a scenario's steps. A tag that never reaches
      # a scenario is discarded silently at end of file.

    @harness-021
    Scenario: A Scenario line with an empty title is not an item
      Given a spec containing a line "    Scenario:" with NOTHING after the colon
      When gen_checklist runs
      Then no item is produced for it
      And it is NOT reported as unstamped
      # A second WART, and the sharper one: the stamper WILL stamp such a line, and
      # gen_checklist then neither counts it nor complains, so the item is stamped,
      # invisible, and its pending tag rolls forward onto whatever scenario comes next.

    # Numbered 049 because it was written after the rest of this file: ids take the next
    # free number wherever the scenario sits, exactly as the stamper does, and are never
    # renumbered to restore file order.
    @harness-049
    Scenario: A Scenario line whose title is only whitespace IS an item
      Given a spec containing a line "  Scenario:" followed by three spaces
      When gen_checklist runs
      Then an item is produced whose "name" is a single space
      # Read that twice. The title is matched as "at least one character, then optional
      # trailing whitespace to end of line", and the match is lazy, so a run of spaces
      # yields a name of one space rather than no match. A tab yields one tab.
      # This is the trap in the pair: the natural reimplementation trims the text after
      # the colon and treats empty as no title, which gets the previous scenario right
      # and this one wrong, by one item in the denominator. Match the behaviour, not the
      # intention.

    @harness-022
    Scenario: A spec file with no scenarios at all is not an error
      Given "docs/plan/01-a/spec.feature" containing only a Feature line
      And no other spec files
      When gen_checklist runs
      Then stdout is exactly:
        """
        {
         "items": [],
         "counts": {
          "total": 0
         }
        }
        """
      And the exit code is 0
      # The empty-spec refusal below counts FILES, not items. A checklist of zero items
      # from one empty file is reachable, and it is a hole in the guard. Specified as it
      # stands, and named here so it is at least visible.

  # GUARD. Incident: emitting a checklist that quietly omits the scenarios nobody stamped
  # shrinks the scoring denominator without saying so, and a shrunk denominator makes the
  # week's rate a different measurement wearing the same name
  # (harness/README.md, the table row for gen_checklist).
  Rule: An unstamped scenario stops the checklist rather than shrinking it

    @harness-023
    Scenario: One unstamped scenario refuses the whole run
      Given "docs/plan/01-x/spec.feature" whose line 5 is "    Scenario: unstamped one"
      And that scenario carries no item tag
      When gen_checklist runs
      Then stderr is exactly:
        """
        gen_checklist: some scenarios carry no item id. A checklist built now would quietly score fewer items than the spec contains, so stamp them first with harness/stamp_item_ids.clj:
          docs/plan/01-x/spec.feature:5: UNSTAMPED: unstamped one
        """
      And the exit code is 1
      And no checklist is written, and nothing is printed to stdout
      # The first line is one line, not wrapped. Each offending scenario is listed on its
      # own line, indented by two spaces, as "<path>:<line>: UNSTAMPED: <title>".
      # The message names the stamper as it is actually invoked, so an implementation
      # in another language names its own file. The reason sentence is fixed; the
      # command it tells the operator to run points at the real file. (The Python
      # original said "harness/stamp_item_ids.py"; it was replaced 2026-09-03.)

    @harness-024
    Scenario: The refusal reports only the first file that has an offender
      Given two spec files, each containing an unstamped scenario
      When gen_checklist runs
      Then only the offenders in the first file, in path order, are listed
      # The refusal happens as soon as one file finishes with errors, so later files are
      # never read. Fix the first, run again, meet the second. Specified as it stands.

  # GUARD. Incident: an empty checklist is not an empty week's work, it is a flawless
  # week that checked nothing. A scoring run against zero items has no failures to report
  # (harness/README.md, the table row for gen_checklist).
  Rule: An empty spec stops the checklist rather than producing an empty one

    @harness-025
    Scenario: No spec files anywhere
      Given a repository with no "docs/plan/NN-slug/spec.feature" at all
      When gen_checklist runs
      Then stderr is exactly, with <PLAN> the absolute path of the docs/plan directory:
        """
        gen_checklist: no NN-slug/spec.feature under <PLAN>. An empty checklist lets a scoring run report a flawless cycle in which nothing was ever checked, so none is written.
        """
      And the exit code is 1
      # One line, not wrapped. The path is absolute. The same message covers a missing
      # docs/plan directory, an empty one, and one whose slice directories hold no
      # spec.feature.

    @harness-026
    Scenario: Every slice filtered out is also an empty spec
      Given every slice directory is skipped by the status filter below
      When gen_checklist runs
      Then it refuses with the same message and exit code as the scenario above
      And the skip lines precede the refusal on stderr
      # This matters: the filter cannot be used to reduce the spec to nothing quietly,
      # and the operator sees which slices were skipped on the way to nothing.

  # GUARD. Origin unknown as an incident: no failure in harness/README.md produced it. It
  # was added in one repository with its reasoning written into the file, and the
  # reasoning is a real distinction the instrument otherwise gets wrong: an item the
  # grader could not REACH counts against you, but an item nobody has started is SCOPE,
  # not a gap, and folding it in scores unstarted work as this cycle's failure. Present
  # in one of the three copies today; the target is all of them, because three tools that
  # define the denominator differently are three instruments, not one.
  Rule: A slice still marked planned is scope, and is skipped out loud

    @harness-027
    Scenario: A planned slice is left out of the checklist
      Given "docs/plan/02-b/plan.md" containing a line "status: planned"
      And "docs/plan/02-b/spec.feature" carrying stamped scenarios
      When gen_checklist runs
      Then no item from 02-b appears in the checklist
      And stderr carries the line:
        """
        gen_checklist: skipping 02-b (status: planned)
        """
      # The skipped statuses are exactly "planned" and "backlog". Any other status, and
      # a slice with no plan.md, and a plan.md with no status line, are all INCLUDED.
      # The status is read as the first match in plan.md of a line beginning with
      # "status:", taking the following non-whitespace run, so front matter works and so
      # does a bare line anywhere in the file.
      # The skip is loud on purpose. This filter could obviously be abused to shrink a
      # denominator by demoting a slice, so every skip prints, and status lives in a
      # plan.md a human wrote and review reads.

    @harness-028
    Scenario: The skip lines go to stderr, so stdout is the checklist alone
      Given a run with no --out and one skipped slice
      When gen_checklist runs
      Then stdout holds the checklist JSON and nothing else
      And stderr holds the skip line, then the counts line
      # Until 2026-09-03 the skip lines landed on stdout ahead of the JSON, so
      # `gen_checklist | jq` broke on a repository with a planned slice. That was
      # specified as it stood rather than quietly fixed, because a silent improvement
      # is still a change to the product. It was then moved deliberately, in a commit
      # that says so, with the oracle's expectation moved in the same commit.

  # -------------------------------------------------------------------------------
  # score
  # -------------------------------------------------------------------------------

  # CONTRACT. score files one cycle's verdicts against that cycle's checklist. Who
  # produced the verdicts is not its business; what a measurement is allowed to look like
  # is.
  Rule: Scoring files verdicts against the checklist and writes the cycle's record

    @harness-029
    Scenario: A complete set of verdicts is filed
      Given "harness/cycles/01/checklist.json" holding two items, "01-001" and "01-002"
      And a verdicts file "v.json":
        """
        [
         {"id": "01-001", "verdict": "pass", "evidence": "saw it work"},
         {"id": "01-002", "verdict": "fail", "evidence": "no such screen"}
        ]
        """
      When score runs for cycle "01" with "--verdicts v.json"
      Then "harness/cycles/01/scores.json" holds exactly:
        """
        {
         "items": [
          {
           "id": "01-001",
           "name": "<the checklist name>",
           "rule": "<the checklist rule>",
           "source": "<the checklist source>",
           "verdict": "pass",
           "evidence": "saw it work"
          },
          {
           "id": "01-002",
           "name": "<the checklist name>",
           "rule": "<the checklist rule>",
           "source": "<the checklist source>",
           "verdict": "fail",
           "evidence": "no such screen"
          }
         ]
        }
        """
      # Shape contract:
      #  - each scored item is the CHECKLIST item's keys in the checklist's own order,
      #    with "verdict" then "evidence" appended after them;
      #  - the top level object holds "items" and nothing else: there is no "counts" key
      #    in scores.json, unlike checklist.json;
      #  - same JSON style as the checklist: indent one space, non-ASCII literal, one
      #    trailing newline;
      #  - items appear in CHECKLIST order, not verdicts order. The verdicts file is a
      #    lookup table by id and its order is discarded.
      And stdout is exactly:
        """
        scored cycle 01: 1/2 pass
        filed: harness/cycles/01/scores.json
        """
      And the exit code is 0
      # The cycle argument is used verbatim as a path segment and in the message: it is
      # a string, never parsed as a number, so "01" stays "01".
      # The filed path is printed relative to the repository root.

    @harness-030
    Scenario: Evidence is optional in a verdict and defaults to empty
      Given a verdict with an "id" and a "verdict" and no "evidence" key
      When score runs
      Then that item's "evidence" in scores.json is the empty string

    @harness-031
    Scenario: Duplicate verdicts for one id keep the last
      Given a verdicts file holding two entries for "01-001"
      When score runs
      Then the item is filed with the LAST of the two, and nothing is reported
      # Specified as it stands. It is not a guard and it does not complain.

  # GUARD. Incident: a scoring run whose every batch failed on a network outage once
  # committed "scored" over an empty record. A measurement nobody filed can still be
  # taken again; a measurement that was filed wrong cannot be untaken
  # (harness/README.md).
  Rule: Zero verdicts means the grading did not happen, not that everything failed

    @harness-032
    Scenario: An empty verdicts list
      Given a verdicts file holding "[]"
      When score runs
      Then stderr is exactly:
        """
        score: 0 items scored. A run that graded nothing tells you the grading did not happen, not that every item failed. Nothing recorded.
        """
      And the exit code is 1
      And no scores.json is written, and any existing one is left untouched

    @harness-033
    Scenario: An empty verdicts FILE
      Given a verdicts file of zero bytes, or holding only whitespace
      When score runs
      Then it refuses with the same message and exit code as the scenario above
      # A file whose content is blank is the same failure as an empty list and gets the
      # same clean death, not a parser crash. This is the shape the incident actually
      # took: a grading run whose batches all failed leaves nothing behind, and what it
      # leaves may be an empty file rather than an empty array.
      # Present in one of the three copies today; the target is all of them. The other
      # two currently die with an unhandled JSON parse error, which is the right exit
      # code with the wrong sentence.

    @harness-034
    Scenario: A verdicts file that is not valid JSON
      Given a verdicts file holding "not json{"
      When score runs
      Then stderr is exactly, with <PATH> the --verdicts argument as it was typed and
        <DETAIL> the JSON parser's own description of the failure:
        """
        score: <PATH> is not valid JSON (<DETAIL>). A measurement that cannot be read is not a measurement: nothing was written.
        """
      And the exit code is 1
      # <PATH> is the argument AS GIVEN on the command line, not resolved and not made
      # relative to the repository root: if the operator typed "v.json", the message says
      # "v.json".
      # <DETAIL> is the ONLY part of any message in this file that is not fixed text. It
      # is whatever the JSON parser says, and different parsers say different things.
      # The parenthesised detail is therefore outside the contract; everything around it
      # is inside it. The oracle masks the parentheses and compares the rest.
      # Present in one of the three copies today; the target is all of them.

    @harness-035
    Scenario: A verdicts file holding something other than a list
      Given a verdicts file holding "{"a": 1}"
      When score runs
      Then stderr is exactly, with <PATH> the --verdicts argument as it was typed and
        <FOUND> the name of the type that was found instead:
        """
        score: <PATH> should hold a list of verdicts, found <FOUND>. Nothing was written.
        """
      And the exit code is 1
      # <FOUND> for a JSON object is "dict", for a string "str", for a number "int",
      # for true or false "bool", for null "NoneType". These are the Python type names
      # and they are the contract, ugly as they are: they are what an operator has read
      # in this message before, and renaming them changes the instrument's voice for no
      # gain. A reimplementation maps the JSON kinds onto exactly those five words.
      # Present in one of the three copies today; the target is all of them.

  # GUARD. Incident: the same one. An item the grader never reached is a gap in the
  # measurement, and dropping it from the record shrinks the denominator so that the pass
  # rate rises for the wrong reason (harness/README.md, the table row for score.clj).
  Rule: An item nobody reached is recorded as a failure, never dropped

    @harness-036
    Scenario: An item with no verdict is filed as a fail with the reason
      Given a checklist of three items and verdicts covering only two of them
      When score runs
      Then scores.json holds all THREE items
      And the unreached item is filed with:
        """
        "verdict": "fail",
        "evidence": "not scored: the grader never reached this item"
        """
      And the exit code is 0

    @harness-037
    Scenario: The report says how many were unreached
      Given a checklist of ten items and verdicts covering seven, of which four pass
      When score runs for cycle "01"
      Then stdout is exactly:
        """
        scored cycle 01: 4/10 pass (3 unreached, recorded as fail)
        filed: harness/cycles/01/scores.json
        """
      # The parenthetical appears only when at least one item was unreached. With none,
      # the line ends after the count, as in the complete-verdicts scenario above.
      # The denominator on that line is the CHECKLIST size, always, and the numerator
      # counts filed passes.

  # GUARD. Incident: none recorded as a failure in the wild. The rule follows from the
  # one the whole instrument rests on: the checklist is derived from the spec, and the
  # spec is the only author of the item list (harness/README.md, the table row for
  # gen_checklist). A verdict for an id nobody derived means the grader and the checklist
  # disagree about what was being measured, and then the measurement is meaningless
  # rather than merely incomplete.
  Rule: A verdict for an id that is not on the checklist is fatal

    @harness-038
    Scenario: An unknown id stops the run
      Given a checklist that does not contain "09-999"
      And a verdicts file carrying a verdict for "09-999"
      When score runs
      Then stderr is exactly:
        """
        score: verdicts for items not on the checklist (the spec is the only author of the item list): 09-999
        """
      And the exit code is 1
      And no scores.json is written
      # Several unknown ids are listed on the one line, separated by ", ", in the order
      # they appear in the verdicts file, duplicates included.

    @harness-039
    Scenario: A verdict that is neither pass nor fail stops the run
      Given a verdicts file whose entry for "01-001" has "verdict": "maybe"
      When score runs
      Then stderr is exactly:
        """
        score: verdict must be 'pass' or 'fail' for: 01-001
        """
      And the exit code is 1
      # The same message covers a missing "verdict" key, a null, and any other value.
      # The two checks run in this order: unknown ids first, verdict values second, so a
      # verdicts file with both faults reports the unknown ids and stops there.
      # There is no "skip", no "n/a" and no third verdict. Two values, and the absence of
      # a verdict is a fail with a reason, which is what the unreached rule is for.

    @harness-040
    Scenario: Scoring a cycle whose checklist has not been generated
      Given no file at "harness/cycles/01/checklist.json"
      When score runs for cycle "01"
      Then stderr is exactly:
        """
        score: no harness/cycles/01/checklist.json: generate it first:
          bb harness/gen_checklist.clj --out harness/cycles/01/checklist.json
        """
      And the exit code is 1
      # The checklist path is printed relative to the repository root, twice.
      # The second line is a command for the operator to run, and it must name the
      # checklist generator AS IT IS ACTUALLY INVOKED, with the same --out argument. The
      # first line, the reason, is fixed text. This is the one place where the message
      # deliberately differs between implementations, and the oracle expects the
      # invocation of whichever implementation it is exercising. (The Python original
      # said "python3 harness/gen_checklist.py"; it was replaced 2026-09-03.)

  # -------------------------------------------------------------------------------
  # scoreboard
  # -------------------------------------------------------------------------------

  # CONTRACT. The scoreboard reads one cycle's record and reports the rate, and the
  # comparison with the cycle before it.
  Rule: The scoreboard reports the pass rate over every item the checklist carried

    @harness-041
    Scenario: A baseline cycle, with nothing to compare against
      Given "harness/cycles/01/scores.json" holding 204 items, 56 of them passing
      And no "harness/cycles/00/scores.json"
      When scoreboard runs for cycle "01"
      Then stdout is exactly:
        """
        cycle 01: 56/204 checklist items pass

          no previous cycle to diff against: baseline cycle.
        """
      And stderr is empty
      And the exit code is 0
      # Note the blank line between the headline and the comparison block, printed in
      # both branches. The baseline line is indented by two spaces and ends with a full
      # stop.
      # The denominator is every item in scores.json, which is every item the checklist
      # carried, because score fills the unreached ones in rather than dropping them.

    @harness-042
    Scenario: The cycle to compare against is the numeric predecessor, same width
      Given a cycle argument "01"
      When scoreboard runs
      Then it looks for "harness/cycles/00/scores.json"
      # The previous cycle number is the argument read as an integer, minus one,
      # formatted back to the SAME NUMBER OF CHARACTERS as the argument, zero padded:
      # "01" gives "00", "10" gives "09", "003" gives "002". A cycle argument that is not
      # all digits has no predecessor and takes the baseline branch. A cycle "00" yields
      # "-1", which simply finds no file; that is accepted and untidy.

    @harness-043
    Scenario: The cycle has no record
      Given no "harness/cycles/07/scores.json"
      When scoreboard runs for cycle "07"
      Then stderr is exactly:
        """
        scoreboard: nothing recorded at harness/cycles/07/scores.json
        """
      And the exit code is 1
      # A scores.json that exists but holds zero items takes this same path, with the
      # same message, because a record of nothing and no record are the same amount of
      # measurement.

    @harness-044
    Scenario: The cycle argument is required
      Given no argument on the command line
      When scoreboard runs
      Then the exit code is non-zero
      # The message on this path is NOT contract. Today it is an unhandled index error
      # in one implementation, and any clear refusal is an improvement. Everything else
      # in this file fixes the words; this one line does not.

  # GUARD. Incident: the two numbers a reader wants to compare are the two headline rates,
  # and when the spec grows between cycles those two rates have different denominators, so
  # the rate falls with no regression behind it. Both directions are shown separately
  # because a net movement hides a regression behind an unrelated repair
  # (harness/README.md, the table row for scoreboard.clj).
  Rule: The comparison shows both directions on the shared items, and never a net

    @harness-045
    Scenario: The full comparison against a previous cycle
      Given "harness/cycles/00/scores.json" holding 16 items, 1 of them passing
      And "harness/cycles/01/scores.json" holding those same 16 items plus 2 new ones
      And on the shared 16, five now pass and one that passed before now fails
      When scoreboard runs for cycle "01"
      Then stdout is exactly:
        """
        cycle 01: 5/18 checklist items pass

          against cycle 00, counting only the 16 items both cycles carried:
            cycle 00     1/16 =   6.2%
            cycle 01     5/16 =  31.2%   <- these two are the fair comparison
            now passing     5   <- repaired since then
            now failing     1   <- broken since then

          THE ITEM SET MOVED: 16 items -> 18 (2 added, 0 dropped), so the two headline
          rates count different things and must not be read against each other.
          The added items cover work last cycle's spec never asked for.
        """
      And the exit code is 0
      # FORMATTING, all of it contract:
      #  - the "cycle NN" lines: four spaces, "cycle ", the number, TWO spaces, then the
      #    rate;
      #  - a rate is "<n>/<d>" right aligned in a field of SEVEN characters, then " = ",
      #    then the percentage right aligned in a field of FIVE with one decimal, then
      #    "%". So 1 of 16 renders as "  1/16 =   6.2%";
      #  - when the denominator is zero the rate renders as the fraction, then " =   n/a"
      #    with three spaces, and no percentage is computed;
      #  - the two direction lines: four spaces, "now passing" or "now failing", THREE
      #    spaces, then the count right aligned in a field of THREE, then
      #    "   <- repaired since then" or "   <- broken since then";
      #  - the compare marker is three spaces then "<- these two are the fair comparison".
      # ARITHMETIC:
      #  - "shared" is the ids present in both records, "added" the ids only in this
      #    cycle, "dropped" the ids only in the previous one;
      #  - better counts shared ids that were fail and are now pass, worse counts shared
      #    ids that were pass and are now fail. They are printed separately and a net is
      #    never computed. That is the whole point of the guard.

    @harness-046
    Scenario: A percentage exactly on a half rounds to the EVEN digit
      Given a comparison whose shared rate is exactly 1 of 16
      When scoreboard runs
      Then the percentage renders as "  6.2%", not "  6.3%"
      # 1/16 is 6.25 exactly, and 5/16 is 31.25 exactly. This is not a detail: the
      # current implementation formats through a printer that rounds ties to even, and
      # the obvious formatter in most other languages rounds ties away from zero and
      # would print 6.3 and 31.3. A reimplementation must round half to even. It is the
      # single easiest way to make this instrument disagree with its own history, and the
      # oracle checks exactly this case.

    @harness-047
    Scenario: The moved-item-set block is printed only when the item set moved
      Given two cycles scoring exactly the same set of ids
      When scoreboard runs
      Then the comparison block is printed
      And no blank line and no "THE ITEM SET MOVED" block follow it
      # The block appears when anything was added OR anything was dropped, and it is
      # preceded by a blank line. Its counts are the previous cycle's total, this
      # cycle's total, and the sizes of the added and dropped sets.
      # It is three printed lines and they wrap where shown above, mid sentence. The
      # wrapping is contract because the oracle compares stdout byte for byte.

    @harness-048
    Scenario: A previous cycle with a record but no shared items
      Given a previous cycle whose ids and this cycle's ids do not overlap
      When scoreboard runs
      Then the comparison block is printed with a shared count of 0
      And both rates render as "    0/0 =   n/a"
      # This is the only route to the n/a form, and it is reachable: a spec that renamed
      # every slice would do it. The block still prints, and the denominator block below
      # it then says everything was added and everything was dropped.

  # -------------------------------------------------------------------------------
  # modules.sh, the adoption check
  # -------------------------------------------------------------------------------
  # Added 2026-09-07, when the three copies of this harness stopped being hand-kept and
  # became one text plus a documented configuration. This tool is not part of the
  # instrument: it measures nothing about an application. The oracle judges it all the
  # same, in CHECK 9, over fixture repositories it builds for the purpose.
  #
  # GUARD. Origin: reasoning, not an incident, and it is worth saying which. Nothing has
  # yet been measured wrong because a copy drifted; what happened instead is that keeping
  # three copies identical by hand took a person reading three files side by side, and the
  # only reason it held is that the same week wrote all three. The silent lie it guards
  # against is the instrument reporting a difference that was never in the thing measured,
  # which is exactly the failure the byte-level oracle exists to catch WITHIN a repository
  # and nothing catches BETWEEN them.
  Rule: The shared module files are one text, and every difference is a declared value

    @harness-050
    Scenario: A shared module file that differs in a sibling is a failure
      Given a sibling repository whose "harness/verify.sh" differs by one byte
      When modules.sh runs with that repository named after --siblings
      Then the line for "harness/verify.sh" reads FAIL and names the sibling
      And the exit code is 1
      # The comparison is byte for byte, as the oracle's is, and for the same reason: a
      # copy that behaves the same today for a reason nobody wrote down is a copy that
      # will behave differently tomorrow. A file missing in a sibling fails the same way,
      # with the word "missing" rather than "forked", and so does a file missing here.
      # The shared set is the four tools, hjson, this spec, the oracle, the fence, the gate
      # and modules.sh itself.

    @harness-051
    Scenario: A configuration variable one repository declares alone is a failure
      Given this repository's "harness/config.sh" declares APP_MARKER
      And a sibling's "harness/config.sh" does not
      When modules.sh runs with that sibling named
      Then the line for APP_MARKER reads FAIL and names the sibling
      And the exit code is 1
      # The variable set is read from the config files themselves, not from a list inside
      # this tool, so the check cannot go stale: whatever any repository declares, every
      # repository must declare. A configuration surface that is complete in one place
      # only is not a configuration surface, and the value is what may differ, never the
      # presence of the line.

    @harness-052
    Scenario: A declared variable left empty is a failure, and APP_MARKER is the exception
      Given a repository whose APP_DIR is declared with an empty value
      When modules.sh runs
      Then the line for APP_DIR reads FAIL and names that repository
      And the exit code is 1
      # APP_MARKER alone may be empty, because "this repository has no cheap name check"
      # is a real answer and the gate says so out loud when it reads one. Every other
      # variable empty means the line was added and never filled in.

    @harness-053
    Scenario: The identity probe is required to exist, and is never compared
      Given three repositories whose "harness/probe.sh" files are all different
      When modules.sh runs over all three
      Then the line for "harness/probe.sh" reads ok
      And no comparison of its content is reported
      # This is the one declared exemption, and the reason is in harness/README.md: the
      # probe decides whether the regenerated application is the right application, so its
      # content is domain behaviour, which is code and cannot be a configuration value.
      # Absence is still a failure, in any repository, and so is a probe that is not
      # executable: the gate refuses to run without one.

    @harness-054
    Scenario: PLAN_DIR must agree with the path the tools resolve for themselves
      Given "harness/config.sh" declares PLAN_DIR as "docs/specs"
      And harness/hjson.clj resolves the plan tree as "docs/plan"
      When modules.sh runs
      Then the PLAN_DIR line reads FAIL and shows both values
      And the exit code is 1
      # PLAN_DIR is declared although no tool reads it at runtime, and this scenario is
      # why: a documented surface that quietly disagrees with the code is worse than an
      # undocumented one, because it is believed. The resolved value is obtained by asking
      # the tools, never by reading the same constant twice.

    @harness-055
    Scenario: Configuration values that differ are reported and not judged
      Given three repositories whose APP_DIR values are all different
      When modules.sh runs over all three
      Then every value is printed in a table, one column per repository
      And the rows whose values are not all equal are marked as differing
      And the exit code is 0 if nothing else failed
      # This is the whole point of the exercise: after the extraction, a difference
      # between the repositories is a value in a table rather than a fork in a file. The
      # table is printed on a green run too, because a difference nobody intended should
      # be read out loud rather than discovered.

    @harness-059
    Scenario: The shared list is the tool's own, and asking for it is how anything else learns it
      When modules.sh runs with --list-shared
      Then it prints one shared file path per line
      And "harness/check.sh" is on the list
      And "harness/modules.sh" is on the list
      And the exit code is 0
      # check.sh ASKS for the list rather than keeping one. Two lists of the same files in
      # two scripts is exactly the fork this tool exists to refuse, and it would be a fork
      # inside the checker itself. modules.sh naming its own file is the same argument one
      # level up: the check is checked.

    @harness-060
    Scenario: A sibling missing its oracle golden is a failure
      Given a sibling repository with no "harness/oracle/checklist.golden.json"
      When modules.sh runs with that repository named after --siblings
      Then the line for the golden reads FAIL and names the sibling
      And the exit code is 1
      # The goldens are each repository's own, recorded from its own tools, so their
      # CONTENT is never compared, for @harness-053's reason. Their PRESENCE is checked,
      # because an oracle with no golden to read does not stop: it reports one missing
      # line and passes everything else, which reads like a small gap and is a blind
      # instrument.

    @harness-061
    Scenario: A cycle with scores and no scoreboard golden is a failure
      Given a repository holding "harness/cycles/01/scores.json"
      And no "harness/oracle/scoreboard-01.golden" beside it
      When modules.sh runs
      Then the line reads FAIL and says that nothing judges the scoreboard for that cycle
      And the exit code is 1
      # Only this direction refuses. A golden with no cycle beside it is reported and
      # allowed, because harness/cycles is what harness/fence.sh deletes: a clean room
      # reaches that state legitimately, every time one is built.

    @harness-062
    Scenario: With no sibling named, every shared file is a skip and nothing claims to be identical
      When modules.sh runs with no --siblings argument
      Then every shared file's line reads SKIP and says it was compared with nothing
      And no line says that a file is identical in 1 repository
      And the closing line does not claim that there is no fork
      And the exit code is 0
      # Until 2026-09-08 this run reported each file "identical in 1 repository" and closed
      # by claiming no fork. Both sentences were true and neither meant anything: it is a
      # pass earned by comparing a file with itself. The vacuous case is the dangerous one,
      # because it is the case that runs when somebody forgets the argument.

    @harness-068
    Scenario: A shared module file a repository fences is absent on purpose
      Given a repository whose FENCED_PATHS names "harness/score.clj"
      And that file is absent, as it is inside a clean room
      When modules.sh runs
      Then the line for "harness/score.clj" reads as a note saying fenced, not forked
      And the exit code is 0
      # GUARD, and the incident is a loop this harness built for itself. harness/verify.sh
      # runs inside the room harness/fence.sh builds, and since 2026-09-08 it calls
      # harness/check.sh before it grades anything. Without this exemption the gate refuses
      # inside the one room it exists to grade: saunavuoro fences harness/score.clj and
      # harness/scoreboard.clj, so its room has fewer tools than the repository it was
      # cloned from, and every one of them read as forked. The exemption is exactly as wide
      # as the list the repository wrote down in FENCED_PATHS. An absence nobody declared is
      # still a failure, which is @harness-050 with nothing taken out of it.

    @harness-069
    Scenario: A file in harness/ that is in no list is a failure
      Given a file "harness/whatever.txt" that no list names
      When modules.sh runs
      Then the line reads FAIL and names SHARED, PER_REPO and LOCAL as the three homes it could have
      And the exit code is 1
      # GUARD. Incident: harness/oracle.py sat in kalibra exactly like this, named by no
      # list, for as long as it existed. A file the check holds no opinion about is a file
      # that can drift for a year in silence, which is the failure this whole Rule is
      # downstream of, arriving through the one gap the Rule had left open. Only files
      # directly in harness/ are judged and directories are not walked, because
      # harness/cycles and harness/oracle hold recordings whose rules are their own and are
      # written above.

    @harness-070
    Scenario: A file declared local may exist in one repository alone
      Given "harness/oracle.py" is declared in LOCAL
      And it is present in kalibra and in neither sibling
      When modules.sh runs over all three
      Then the completeness line reads ok in every repository
      And the exit code is 0 if nothing else failed
      # LOCAL is the declaration that says this one belongs to a single repository, and it
      # is the whole difference between a file somebody decided about and a file nobody
      # noticed. Presence is required nowhere and content is compared never, which is what
      # separates it from PER_REPO, where the file must exist in every repository and only
      # its content is each one's own (@harness-053).

  # -------------------------------------------------------------------------------
  # verify.sh, the ratifying gate: the first budget for a quality
  # -------------------------------------------------------------------------------
  # Added 2026-09-08. Everything above this line specifies behaviour, and until today so
  # did every other spec in every repository carrying this harness: not one scenario
  # anywhere asserted a duration, a size, a count or a limit. A scenario that cannot fail
  # is not a spec, it is an intention, and "the app comes up" is an intention.
  #
  # THE NUMBER WAS NOT INVENTED HERE, and that is why this is the first one. The gate
  # already polls twenty times at half a second before it gives up, so ten seconds has
  # been its boot budget since it was written. What was missing was that the budget was
  # only ever a loop bound: nothing named it, nothing reported how much of it was used,
  # and a boot creeping from one second to nine looked exactly like a boot that took one.
  # So the rule states the ten seconds the code already enforces, and adds the half of a
  # budget that was absent, which is the margin.
  #
  # WHAT IT COSTS TODAY, measured on the reference machine (Apple Silicon, native arm64,
  # 2026-09-08), six runs each and read off the gate's own margin. saunavuoro's
  # coldrun/grounded answered on poll 2 of 20, 0.59 to 0.61 seconds, and kalibra's warm web
  # build answered on poll 2 of 20, 0.60 to 0.63 seconds. Eighteen of the twenty polls are
  # margin in both. Driven directly, without the gate's half second between tries, kalibra
  # answers in 0.12 to 0.27 seconds, so what the second poll mostly measures is the poll
  # interval and not the application. loyly carries no implementation, so nothing was
  # measured there and its ten seconds is inherited from the code rather than from a run.
  #
  # The cold path is what the budget is actually for: kalibra's web build from cold took
  # 37.4 seconds on the same machine, which is why its impl/start.sh warms the build inside
  # the untimed suite step and says so in its own header.
  #
  # GUARD, and the near miss it is drawn from is written in kalibra's impl/start.sh. A
  # build that stops being warmed does not fail the way a broken build fails. The suite
  # is green, the application is correct, and the gate reports that the app never
  # answered, which reads as a serving problem and is not one. The budget cannot prevent
  # that, but naming it in the refusal points at the right half of the contract.
  Rule: The gate bounds how long the application may take to answer, and reports the margin

    @harness-056
    Scenario: An application that answers inside the budget is ratified, and the margin is said out loud
      Given an application that serves its first page one second after start.sh is launched
      When the gate boots it
      Then the gate proceeds to the identity probe
      And it reports how much of the boot budget was used
      # The margin is reported on a GREEN run, for the same reason the promotion rate and
      # the configuration table are printed on green runs elsewhere in this harness: a
      # number nobody reads until it fails is a number nobody can see approaching. The
      # gate has no sub-second clock it can rely on across the shells it runs in, so the
      # margin it reports is which poll answered out of the budget's polls, which is exact
      # and needs nothing installed.

    @harness-057
    Scenario: An application that has not answered when the budget runs out is refused
      Given an application that serves nothing for thirty seconds after start.sh is launched
      When the gate boots it
      Then the gate refuses, naming the budget in seconds
      And the refusal says that a boot log is not a served page
      And the exit code is 1
      # Ten seconds, which is twenty polls at half a second. The refusal names the number
      # so that the reader is told what was expected rather than only what happened, and
      # it names the two ways out: move the slow part into the suite, which the gate does
      # not time, or change the budget here and in the gate together. A budget changed in
      # one of the two places is the failure that harness/modules.sh exists to catch.

    @harness-058
    Scenario: The clock covers everything start.sh does, a build included
      Given an application whose start.sh builds for thirty seconds before it serves
      When the gate boots it
      Then the gate refuses on the boot budget
      # This is the contract that decides where slow work belongs, and it is stated
      # because it has already shaped an implementation. The clock starts when start.sh is
      # launched and stops at the first answer, so a build inside start.sh is inside the
      # budget and a build inside test.sh is not. kalibra's suite therefore ends by
      # warming the web build, and its start.sh serves what the suite already built. That
      # is not a workaround, it is the budget being obeyed, and until today the only place
      # it was written down was a comment in one repository's implementation.

  # -------------------------------------------------------------------------------
  # check.sh, the entry point that gives the other judges an occasion
  # -------------------------------------------------------------------------------
  # Added 2026-09-08.
  #
  # GUARD. Incident: nothing ever ran the oracle. It made its assertions only when a person
  # typed the command, there was no CI in any of the three repositories and no Makefile,
  # and the gate and the fence both named the adoption check in a comment while calling
  # neither it nor the oracle. A judge with no occasion reads as coverage, and it is the
  # worst kind of false coverage, because its silence cannot be told from a pass. Nothing
  # was ever measured wrong by it, and nothing could have been: it never spoke.
  Rule: The checks run without a person, and a red one stops something

    @harness-063
    Scenario: A red oracle refuses the commit
      Given a repository whose oracle is red
      When the installed pre-commit hook runs
      Then the commit is refused
      And the message says that re-recording the goldens is the wrong response unless the
        words themselves changed
      And the exit code is 1
      # The message names the escape too, "git commit --no-verify", in as many words. A
      # gate with no stated escape is deleted by the first person it blocks at the wrong
      # moment, and a deleted gate is weaker than an escapable one.

    @harness-064
    Scenario: A forked sibling fails only the commit that is the fork
      Given a sibling repository whose "harness/verify.sh" differs from this one's
      When a commit that stages no shared module file is made
      Then the fork is reported and the commit is allowed
      When a commit that stages a shared module file is made
      Then the commit is refused
      # A sibling's working tree is not a property of the change being committed: an agent
      # editing one repository would otherwise fail commits in another that have nothing to
      # do with it. The exception is the case where the fork IS the change. A commit that
      # edits a shared module file and leaves the copies forked is precisely what the
      # adoption check exists to catch, so the hook escalates to strict for those commits
      # alone.

    @harness-065
    Scenario: The two committed callers also refuse over a red oracle
      Given a repository whose oracle is red
      When harness/verify.sh runs
      Then nothing is booted and nothing is graded
      And the exit code is 1
      When harness/fence.sh runs
      Then no clean room is created
      # The hook is not a committed file, so a fresh clone has none until somebody installs
      # it, and --no-verify skips the one that is installed. These two callers are
      # committed files and travel with the repository, which is what makes "this is
      # ratified" and "this room is clean" claims that cannot be made over a red
      # instrument anywhere.

    @harness-066
    Scenario: A tool the oracle cannot find is a skip, and the run is not failed
      Given a tree with no "harness/score.clj"
      When check.sh runs
      Then the oracle's exit code is 2
      And the run reports a SKIP
      And the run is not failed
      # A fenced clean room produces this on purpose: saunavuoro fences harness/score.clj
      # and harness/scoreboard.clj, so the gate running inside its clean room has fewer
      # tools to judge than the repository it was cloned from. The skip is printed loudly,
      # because a skip nobody sees is a pass.

    @harness-067
    Scenario: Installing the hook over somebody else's hook is refused
      Given ".git/hooks/pre-commit" exists
      And it does not mention "harness/check.sh"
      When check.sh runs with --install-hook
      Then it refuses and asks for a hand merge
      And the exit code is 1
      # An existing hook is somebody's work, in a directory git does not version, so there
      # is nothing to recover it from once it is overwritten. Refusing costs a minute;
      # guessing costs whatever the hook did.
