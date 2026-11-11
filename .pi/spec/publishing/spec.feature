Feature: Publishing
  The accepted scenarios of this context: compiling the design history file
  from the record (templates and data to Markdown, its filters, its
  post-processing, snippets from source), RDM's own user manual, and the
  generated release documents (the device-master-record index and the
  verification report). Each is stamped with an item id that is never
  renumbered or reused, and verified by the acceptance tests whose Allure
  results carry that id as a tag label.

  Rule: A document is a Markdown template rendered against the data files supplied, never against a value they lack

    # publishing.allium, rule Render (requires 1 to 2)
    @allium-rule-success.Render @allium-rule-failure.Render.1 @allium-rule-failure.Render.2
    @item-07-001
    Scenario: rdm render fills a Markdown template from data files, each under its file name
      Given a template doc.md "# {{ device.name }}" then "Version {{ device.version }}, by {{ other.maker }}."
      And the data files empty.yml, device.yml with name "Acme Monitor" and version "1.2", and other.yml with maker "Acme Inc"
      When the regulatory author runs rdm render doc.md with empty.yml, device.yml and other.yml
      Then the output is "# Acme Monitor", then "Version 1.2, by Acme Inc."
      When other.yml is left out
      Then rendering is refused with exit status 2, naming "other" and doc.md
      When the template nope.md does not exist
      Then rendering is refused with exit status 2, naming nope.md

  Rule: Templates have the filters a traceability table needs

    # publishing.allium, surface CompileDhf, guarantee TraceabilityFilters
    @allium-guarantee.TraceabilityFilters
    @item-07-002
    Scenario: invert_dependencies, join_to, md_indent, glob, include and gherkin build traceability tables and final documents
      Given items with id A depending on B
      When a template inverts them with invert_dependencies('id', 'deps')
      Then B is depended on by ['A']
      Given a table with rows r1 (v 1) and r2 (v 2)
      When a template joins ['r2', 'r1'] to it with join_to
      Then it reads the rows in that order: "2;1;"
      When a template indents the snippet "# Title" with md_indent(header_shift=1)
      Then it reads "## Title"
      Given feature files spec/b/spec.feature ("Feature: B") and spec/a/spec.feature, and a document arch.md with frontmatter id SDS-1 and the heading "# Architecture"
      When a template includes every file 'spec/*/spec.feature' | glob gives, and 'arch.md' | include | md_indent(1)
      Then it carries spec/a/spec.feature, then spec/b/spec.feature, each as written, and "## Architecture" without the frontmatter
      Given a feature file with a Rule "Within 2 s", a scenario tagged @smoke @REQ-12 whose steps hold "$140" and "<value>", an outline @REQ-13 with Examples, and a comment
      When a template lays it out with gherkin
      Then the Rule is a bold line "Rule: Within 2 s", the scenario the bold title "Alarm raised" led by `REQ-12` with *@smoke* beneath it, each step's keyword in bold, the Examples a table, "$" and "<" escaped, the comment left out, and nothing a heading
      When rdm render joins the id r9 to a table holding only r1
      Then rendering exits with status 2, naming r9

  Rule: Rendered Markdown is post-processed: numbered, given its own words, and stripped of auditor-only notes

    # publishing.allium, surface CompileDhf, guarantee MarkdownPostProcessed
    @allium-guarantee.MarkdownPostProcessed
    @item-07-003
    Scenario: Sections are numbered, a second render gets the first's words, and auditor-only notes are removed
      When "## hello" is rendered with section numbering
      Then it reads "## 1.1 hello"
      When "Spec [[1234:9.8.7.6]]." is rendered with auditor-note exclusion
      Then it reads "Spec."
      When a template lists first_pass_output.words after the words apple and banana, rendered with the vocabulary extension
      Then its second render reads "[apple][banana]"
      When numbering and note exclusion run together
      Then code in fenced blocks, tildes and inline code is left exactly as written, its "# a comment" unnumbered and its "[[1, 2]]" kept
      And only ATX headings are numbered: "#hashtag" and a lone "#" stay, "# Top" and "## Sub" become "# 1 Top" and "## 1.1 Sub"
      And a note over two lines of a paragraph, or nested in another, is removed whole: "A [[auditor" "only]] B." reads "A B."
      And a code fence inside a blockquote or a list item is code too, its notes kept
      And a note still open at the paragraph's end, "Stray [[ here", is kept as text

  Rule: Code snippets are extracted from source files for inclusion in documents

    # publishing.allium, surface CompileDhf, guarantee SnippetsFromSource
    @allium-guarantee.SnippetsFromSource
    @item-07-004
    Scenario: The lines between RDOC and ENDRDOC are a snippet under its key
      When the lines "RDOC greeting", "hello", "world", "ENDRDOC" are collected
      Then the snippet greeting is "hello" then "world"
      And lines with no markers give no snippets
      And a marker is a whole word: "PRDOC_LIMIT = 3" starts no snippet, and the snippet limit is "LIMIT = PRDOC_LIMIT"
      When a.py and b.py both hold a snippet keyed "key"
      Then collecting them is refused, naming both files
      And rdm collect exits with status 2, naming b.py

  Rule: RDM's user manual is one controlled document, published per release, that discloses every risk

    # publishing.allium, surface UserManual, guarantee ManualInReadingOrder
    @allium-guarantee.ManualInReadingOrder
    @item-07-005
    Scenario: IFU-001 lists the manual's pages in the reading order of instructions for use, behind a cover
      Given the controlled document IFU-001 and the pages it lists
      Then its chapters appear in this order: "RDM documentation", "What RDM is for", "How it works", "Limits and risks", "Install", "Quick start", "Guides", "Troubleshooting", "Reference", "Changelog"
      And every other page sits in the folder of the chapter page before it
      And the site's Docs tab shows exactly these pages, in this order
      And the first page is the cover, naming the product, "{{ rdm.release }}", and "IFU-001, revision" with the manual's revision
      When the docs are built for the release tag v9.1.0
      Then "{{ rdm.release }} @{{ rdm.ref }}" reads "v9.1.0 @v9.1.0"
      When they are built for the development version
      Then it reads "development version" and the install ref "@main"
      And no page pins a release by hand

    # publishing.allium, surface UserManual, guarantee PublishedPerRelease
    @allium-guarantee.PublishedPerRelease
    @item-07-006
    Scenario: The manual is published once per release tag and once for the development version
      Given the docs workflow and the site's configuration
      Then a release tag "v*" and a push to main both publish
      And a tag publishes under its own name and as latest, main as the version dev
      And every published version is kept on one branch, never replaced, and selectable
      And the newest release, latest, is the default

    # publishing.allium, surface UserManual, guarantee RisksDisclosedById
    @allium-guarantee.RisksDisclosedById
    @item-07-007
    Scenario: The manual's "Limits and risks" chapter discloses every risk of the register by its id
      Given RDM's risk register and the manual's "Limits and risks" chapter
      Then the chapter's risk table names every risk of the register by its id, and no other
      And each row says what the user must do about the residual risk

  Rule: The device-master-record index and the verification report are generated from the record

    # ../release/allium/release.allium, surface ReleaseEvidence, guarantee DmrIndexFromFrontmatter
    @allium-guarantee.DmrIndexFromFrontmatter
    @item-07-008
    Scenario: The DMR index has one entry per controlled document, from its frontmatter
      Given documents/sop.md with id SOP-1, revision 2, title "The SOP"
      And documents/plan.md with id PLAN-1, revision 1, title "The Plan"
      And documents/notes.md with no frontmatter
      When the DMR index data is generated
      Then it lists PLAN-1 "The Plan" documents/plan.md revision 1, then SOP-1 "The SOP" documents/sop.md revision 2
      And notes.md is named as skipped
      When the only documents have a blank id or an empty one
      Then nothing is indexed, a.md is named, and generation exits with status 1
      And the output is marked GENERATED, and generating it again gives the same file

    # ../release/allium/release.allium, surface ReleaseEvidence, guarantee VerificationReportForAReviewer
    @allium-guarantee.VerificationReportForAReviewer
    @item-07-009
    Scenario: The verification report shows a reviewer who did not run the tests what was verified
      Given a record with requirements, risks and Allure results of passing, failing and skipped runs with steps and attachments
      When the verification report is written
      Then its header names the repository, the record's commit, the commits tested, the executor and environment, the RDM version and one SHA-256 over the result files
      And the evidence is release-grade only when every requirement passed at the product's commit with a clean worktree; otherwise each reason is named
      And its anomalies list failed and skipped runs, requirements with no run, missing attachments and orphan tags
      And its traceability lists each requirement in spec order with its user needs, the risks it is a control for and its tests
      And each requirement is a baseline or a risk-based acceptance criterion, each risk shows its status and residual decision, and the register's state is summarised
      And each run shows its test's file and function, result, date and duration, failure message and trace
      And it leaves out the labels it already shows, runner internals and Allure's links
      And each step is a verification step, nested, with its own result
      And attachments show text inline up to a limit and images embedded, other files listed, the captured output and the copy of the requirement listed by SHA-256 only
      And the PDF shows all of it in that order, and an appendix lists every result file with its SHA-256
      And text from a test reaches the page as text: markup in it is shown, not run
      And without the typst package a typst executable is used, and with neither the report says so
      And the evidence bundle includes the report, rdm story evidence-report writes it, and its SHA-256 can be checked from the bundle's copy of the results
      And a result it cannot lay out leaves the reason in the bundle, not a crash
      And a symbolic link among the results is never read: its attachment is missing and its file unhashed
      And every run counts, tagged with a declared requirement or not; an unreadable or linked result, and uncommitted changes to the record, are each a reason it is not release-grade
      Given a record whose spec lives in a repository of its own, and a passing run of that repository's commit
      When the verification report is written
      Then the product's commit is the spec repository's, the record's stays its own, and the header names both
      And the run is release-grade evidence
