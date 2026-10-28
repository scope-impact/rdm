@UN-013
Feature: Gap analysis
  Every clause of every declared clause list is named by an audit tag in a controlled document.

  Rule: Without :regulated, nothing of the profile runs

    @spec-0255 @e2e
    Scenario: A project without :regulated has no gap stage
      Given a project set up by rdm-spec whose configuration holds no :regulated
      And a file dhf/documents/plan.md that names no clause
      When "rdm-spec check" is run
      Then it exits 0 and no finding or warning names a clause, a clause list or a document

  Rule: A clause list is read as clause lines, comments and includes

    @spec-0256 @e2e
    Scenario: Each line is a clause key and its description, an include, a comment or blank
      Given a regulated project
      And dhf/clauses/std.txt holds:
        """
        include base.txt

        STD:5.1 Development plan
        STD:5.2 Requirements analysis
        """
      And dhf/clauses/base.txt holds "STD:4.1 Quality system"
      And no document names any clause
      When "rdm-spec check" is run
      Then it exits 1 with one clause-unreferenced finding for each of STD:4.1, STD:5.1 and STD:5.2, and none for any other key

    @spec-0257 @e2e
    Scenario: A clause list that holds no clause stops the check
      Given a regulated project
      And dhf/clauses/std.txt holds only comments
      When "rdm-spec check" is run
      Then it exits 2 and says the clause list dhf/clauses/std.txt holds no clause

    @spec-0258 @e2e
    Scenario: An include that names no readable file stops the check
      Given a regulated project
      And dhf/clauses/std.txt holds "include missing.txt"
      When "rdm-spec check" is run
      Then it exits 2 and names dhf/clauses/missing.txt

    @spec-0282 @e2e
    Scenario: A line of prose in a clause list is malformed, never a clause
      Given a regulated project
      And dhf/clauses/std.txt holds "STD:5.1 Development plan" and the line "Quality system"
      When "rdm-spec check" is run
      Then it exits 1 with a finding naming dhf/clauses/std.txt and the line of "Quality system", and STD:5.1 is the only clause

  Rule: A clause is met only by an audit tag naming it

    @spec-0259 @e2e
    Scenario: A clause no audit tag names is a finding
      Given a regulated project
      And dhf/clauses/std.txt holds the clauses STD:5.1 and STD:5.2
      And dhf/documents/plan.md names STD:5.1 in an audit tag and nothing else
      When "rdm-spec check" is run
      Then it exits 1
      And it prints a clause-unreferenced finding naming dhf/clauses/std.txt, the line of STD:5.2, the key and its description

    @spec-0260 @e2e
    Scenario Outline: What names a clause
      Given a regulated project
      And dhf/clauses/std.txt holds the one clause STD:5.1
      And dhf/documents/plan.md holds the line <line>
      When "rdm-spec check" is run
      Then the clause STD:5.1 is <named>

      Examples:
        | line                                        | named     |
        | We plan [[STD:5.1]].                        | named     |
        | [[This section meets STD:5.1]]              | named     |
        | [[STD:5.1: the plan is this document]]      | named     |
        | We plan [[STD:5.1.a]].                      | named     |
        | We meet STD:5.1 here.                       | not named |
        | We plan [[STD:5.12]].                       | not named |
        | We plan [[STD:5]].                          | not named |
        | `[[STD:5.1]]`                               | not named |

    @spec-0281 @e2e
    Scenario: An audit tag in a list item's continuation paragraph names its clause
      Given a regulated project
      And dhf/clauses/std.txt holds the one clause STD:5.1
      And dhf/documents/plan.md holds a list item, a blank line, and the item's continuation paragraph, indented four spaces, holding "[[STD:5.1]]"
      When "rdm-spec check" is run
      Then the clause STD:5.1 is named

    @spec-0275 @e2e
    Scenario: An audit tag in an indented code block names nothing
      Given a regulated project
      And dhf/clauses/std.txt holds the one clause STD:5.1
      And dhf/documents/plan.md holds, after a blank line, a line of four spaces then "[[STD:5.1]]"
      When "rdm-spec check" is run
      Then the clause STD:5.1 is not named

    @spec-0261 @e2e
    Scenario: An audit tag never runs past a blank line
      Given a regulated project
      And dhf/clauses/std.txt holds the one clause STD:5.1
      And dhf/documents/plan.md holds "An open [[ tag", a blank line, and "STD:5.1 ]]"
      When "rdm-spec check" is run
      Then the clause STD:5.1 is not named

    @spec-0262 @e2e
    Scenario: An audit tag that is one key no clause list holds is a warning
      Given a regulated project
      And dhf/clauses/std.txt holds the one clause STD:5.1
      And dhf/documents/plan.md names STD:5.1 and STD:9.9 in audit tags
      When "rdm-spec check" is run
      Then it exits 0
      And it prints a clause-unknown warning naming dhf/documents/plan.md, the line of the tag and STD:9.9

  Rule: Frontmatter names no clause

    @spec-0268 @e2e
    Scenario: An audit tag in a document's frontmatter names nothing
      Given a regulated project
      And dhf/clauses/std.txt holds the one clause STD:5.1
      And dhf/documents/plan.md has the title "Development plan [[STD:5.1]]" and a body that names no clause
      When "rdm-spec check" is run
      Then the clause STD:5.1 is not named

  Rule: A placeholder is a warning

    @spec-0263 @e2e
    Scenario: A document still holding TODO is named in a warning
      Given a regulated project
      And dhf/clauses/std.txt holds the one clause STD:5.1
      And dhf/documents/plan.md names STD:5.1 in an audit tag and holds a line "TODO: write the plan"
      When "rdm-spec check" is run
      Then it exits 0
      And it prints a document-placeholder warning naming dhf/documents/plan.md and the line holding TODO

  Rule: A controlled document carries its identity

    @spec-0264 @e2e
    Scenario: A document with no id, revision or title is a finding
      Given a regulated project
      And dhf/documents/plan.md has frontmatter with an id and a title but no revision
      When "rdm-spec check" is run
      Then it exits 1
      And it prints a document-unidentified finding naming dhf/documents/plan.md and the missing revision

    @spec-0265 @e2e
    Scenario: Two documents with one id is a finding
      Given a regulated project
      And dhf/documents/plan.md and dhf/documents/old-plan.md both have the id PLAN-001
      When "rdm-spec check" is run
      Then it exits 1 and names the id PLAN-001 and both files
