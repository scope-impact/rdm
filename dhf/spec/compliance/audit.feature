# Component Audit copy, bounded context compliance of the rdm-spec container: dhf/spec/model.edn.
#
# What a team hands an auditor (dhf/wiki/pages/regulated-profile.md): every
# controlled document twice, the auditor's copy with its audit tags and the
# engineer's copy without, the clauses with where each is met, the
# traceability, and the evidence, with a manifest that pins it all. Every file
# is generated from the record; none is edited by hand.
#
# "A regulated project" is as in dhf/spec/compliance/gap.feature: :regulated
# with the clause list dhf/clauses/std.txt and the documents directory
# dhf/documents. OUT and OUT2 are directories the scenario names.

@UN-013
Feature: Audit copy
  The auditor's copy and the engineer's copy of every controlled document, with the evidence they rest on.

  # An audit copy of a record that is not releasable shows an auditor
  # something nobody may ship. The release check, gaps included, is the gate
  # before the audit copy, as the oracle is before fence
  # (dhf/spec/kernel/command.feature, "A gate stops the task"). The audit copy
  # reads the scenarios (SpecParsed), their statuses (VerificationDerived) and
  # the clauses (ClausesIndexed, within its own context) from the bus.
  Rule: Only a releasable record has an audit copy

    @spec-0242 @e2e
    Scenario: A record the release check refuses has no audit copy
      Given a regulated project whose every clause is named, and a stamped scenario whose result failed
      When "rdm-spec audit OUT" is run
      Then it exits 1, prints the release check's findings unchanged, and creates no OUT

    @spec-0243 @e2e
    Scenario: A clause no audit tag names refuses the audit copy
      Given a regulated project whose every scenario is verified, and a clause no audit tag names
      When "rdm-spec audit OUT" is run
      Then it exits 1, names the clause, and creates no OUT

    @spec-0244 @e2e
    Scenario: A project without :regulated has no audit copy
      Given a project set up by rdm-spec whose configuration holds no :regulated
      When "rdm-spec audit OUT" is run
      Then it exits 2 and says the configuration holds no :regulated

    @spec-0245 @e2e
    Scenario: An existing directory is never written into
      Given a releasable regulated project and an existing directory OUT holding one file
      When "rdm-spec audit OUT" is run
      Then it exits 2, names OUT, and the file in it is unchanged

  # The two copies are identical but for the notes
  # (innolitics-rdm:README.md@f21b508, "Easy auditablility").
  Rule: Every document has an auditor's copy and an engineer's copy

    @spec-0246 @e2e
    Scenario: The auditor's copy keeps the audit tags, the engineer's copy drops them
      Given a releasable regulated project whose dhf/documents/plan.md holds the line "We plan the work [[STD:5.1]]."
      When "rdm-spec audit OUT" is run
      Then it exits 0
      And OUT/auditor/plan.md holds the line "We plan the work [[STD:5.1]]."
      And OUT/engineer/plan.md holds the line "We plan the work."
      And apart from that line the two copies are identical

    @spec-0247 @e2e
    Scenario Outline: What the engineer's copy drops
      Given a releasable regulated project whose dhf/documents/plan.md holds <written>
      When "rdm-spec audit OUT" is run
      Then OUT/engineer/plan.md holds <kept>

      Examples:
        | written                                                        | kept                                    |
        | the line "We plan [[STD:5.1]]."                                | the line "We plan."                     |
        | the line "We plan [[STD:5.1]] and test [[STD:5.2]]."           | the line "We plan and test."            |
        | a tag that opens on one line and closes on the next            | both lines without the tag              |
        | the line "We plan `[[STD:5.1]]`."                              | that line unchanged                     |
        | a "[[" with no "]]" before the paragraph's blank line          | the paragraph unchanged                 |
        | a fenced code block holding [[STD:5.1]]                        | the code block unchanged                |
        | an indented code block holding [[STD:5.1]]                     | the code block unchanged                |
        | the title "Development plan [[STD:5.1]]" in its frontmatter    | the title "Development plan"            |

    @spec-0248 @e2e
    Scenario: The documents directory's layout is kept
      Given a releasable regulated project with dhf/documents/plan.md and dhf/documents/risk/register.md
      When "rdm-spec audit OUT" is run
      Then OUT/auditor and OUT/engineer each hold plan.md and risk/register.md

    @spec-0249 @e2e
    Scenario: Each copy is also a PDF whose first page names the document
      Given a releasable regulated project whose dhf/documents/plan.md has the id PLAN-001, the revision 3 and the title "Development plan"
      When "rdm-spec audit OUT" is run
      Then OUT/auditor/plan.pdf and OUT/engineer/plan.pdf exist
      And the text of each one's first page holds PLAN-001, the revision 3 and "Development plan"
      And no text of OUT/engineer/plan.pdf holds "[["

  Rule: The audit copy carries the evidence it rests on

    @spec-0250 @e2e
    Scenario: The clauses page names where each clause is met
      Given a releasable regulated project whose dhf/clauses/std.txt holds "include base.txt" then "STD:5.1 Development plan", and whose dhf/clauses/base.txt holds "STD:4.1 Quality system"
      And STD:5.1 is named on line 8 of dhf/documents/plan.md and line 9 of dhf/documents/risk/register.md
      When "rdm-spec audit OUT" is run
      Then OUT/clauses.md has one section per declared clause list, dhf/clauses/std.txt, and no section for dhf/clauses/base.txt
      And under it STD:4.1 comes first, where its include stands, then a row for STD:5.1 with "Development plan", dhf/documents/plan.md:8 and dhf/documents/risk/register.md:9

    @spec-0251 @e2e
    Scenario: The traceability page links each need to its scenarios and their status
      Given a releasable regulated project whose need UN-001 is traced to by @spec-0001 and @spec-0002
      When "rdm-spec audit OUT" is run
      Then OUT/traceability.md has, under UN-001 and its text, @spec-0001 and @spec-0002, each with its title, component and status verified

    @spec-0252 @e2e
    Scenario: The results the record was verified by are kept
      Given a releasable regulated project and its results directory
      When "rdm-spec audit OUT" is run
      Then OUT/results holds a copy of every file of the results directory

    @spec-0253 @contract
    Scenario: The manifest pins every file of the audit copy
      Given a releasable regulated project
      When "rdm-spec audit OUT" is run
      Then OUT/manifest.edn holds exactly {:commit :clean :files}: the commit HEAD resolves to, :clean true when no file under the clause lists' and documents' directories differs from it, untracked files included, and every other file of OUT as {:path :sha256}, its path relative to OUT, sorted by path

    # An untracked document closed a gap and entered the audit copy while the
    # manifest said :clean true at a commit that did not hold it (cycle 08,
    # grading).
    @spec-0274 @e2e
    Scenario: The audit copy is made from what is committed at HEAD
      Given a releasable regulated project whose every clause is named in committed documents
      And an untracked document dhf/documents/draft.md
      When "rdm-spec audit OUT" is run
      Then OUT holds no copy of draft.md, and OUT/manifest.edn says :clean false

    @spec-0254 @e2e
    Scenario: Same commit, same results, same audit copy
      Given a releasable regulated project
      When "rdm-spec audit OUT" is run, then "rdm-spec audit OUT2"
      Then every file, the PDFs and the manifest included, is byte for byte the same in OUT and OUT2
