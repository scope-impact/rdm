Feature: Test evidence
  The accepted scenarios of this context: how executed Allure results are
  read as test runs, and what an acceptance run records about the version it
  tested and who ran it. Each is stamped with an item id that is never
  renumbered or reused, and verified by the acceptance tests whose Allure
  results carry that id as a tag label.

  Rule: A result RDM cannot read is never counted as a pass

    # ../release/allium/release.allium, surface TraceVerification, guarantee UnreadableResultNeverPasses;
    # test-evidence.allium, rule ReadAllureResult (requires 1 to 4)
    @allium-guarantee.UnreadableResultNeverPasses
    @item-03-001
    Scenario: An unreadable result blocks release and is named, and a failed step fails its run
      Given a spec whose only requirement is item-01-001, addressing the need Release
      And a passing Allure result "a" tagged item-01-001
      Then the release gate passes
      When a result "b" is added that cannot be read, holding any of:
        | content                                                         |
        | JSON cut short after '"value": "item-01-001"'                   |
        | a JSON list, not an object                                      |
        | bytes that are not UTF-8                                        |
      Then release is blocked: the result is unreadable, and it could hold a failed run
      When result "b" is a failed run tagged item-01-001 written with a byte-order mark
      Then it is read, and release is blocked: item-01-001 failed
      When a result "b" tagged item-01-001, beside the passing result "a", has any of:
        | case          | what is wrong                                   |
        | capital-status| the status "Failed", which Allure does not write|
        | no-status     | no status                                       |
        | null-status   | a null status                                   |
        | labels-text   | labels given as the text "tag=item-01-001"      |
        | tag-list      | a tag label whose value is a list               |
        | link          | it is a symbolic link to a result elsewhere     |
        | deep          | JSON nested 100000 levels deep                  |
      Then release is blocked by that unreadable result alone
      When failing runs beside the passing result "a" are tagged "Item-01-001", "item_01_001", "item–01-001" and "item-01-777"
      Then release passes, and the gate warns "Allure result tag <tag> matches no requirement" for each of the four
      When a run marked passed has an inner step that failed, and another an inner step that broke
      Then both runs have failed, and so have the requirements they claim
      When a result "x-result.json" cannot be read and verify is run
      Then verify exits non-zero and the verification data lists x-result.json as unreadable

  Rule: A run that claims a requirement carries the commit it tested

    # ../release/allium/release.allium, surface TraceVerification, guarantee RunLabelledWithTheCommitUnderTest;
    # test-evidence.allium, rule LabelRun
    @allium-guarantee.RunLabelledWithTheCommitUnderTest
    @item-03-002
    Scenario: The pytest plugin labels each run of a claiming test with the commit under test
      Given a committed repository whose spec holds item-01-001 and item-01-002, with its DHF in sw/dhf
      And tests test_alarm tagged item-01-001, test_log tagged item-01-002, test_undeclared tagged item-01-099 and test_untagged with no tag
      When the tests run with rdm.pytest_plugin on a clean working tree
      Then the results of test_alarm and test_log carry the commit label of the repository's HEAD
      And test_alarm carries no worktree label
      And test_undeclared and test_untagged carry no commit label
      When the test file has an edit not yet committed and the tests run again
      Then test_alarm carries the worktree label "dirty" and still the commit label of HEAD

  Rule: The results of an acceptance run record who ran it and where

    # ../release/allium/release.allium, surface TraceVerification, guarantee RunRecordsWhoRanItAndWhere;
    # test-evidence.allium, rule RecordRunEnvironment
    @allium-guarantee.RunRecordsWhoRanItAndWhere
    @item-03-003
    Scenario: executor.json and environment.properties name the executor and the environment
      Given a committed repository whose two tests claim item-01-001
      When the tests run with rdm.pytest_plugin on GitHub Actions, run 7001 attempt 2, run number 42 of the workflow "Design controls" in acme/device, by octocat on a pull_request, on a Linux X64 runner
      Then executor.json names "GitHub Actions" of type github, build order 42, the build name "Design controls #42" and the URL https://github.com/acme/device/actions/runs/7001/attempts/2
      And environment.properties records the operating system, the Python, pytest, allure-pytest and RDM versions
      And the commit under test with the worktree "clean"
      And ci.actor octocat, ci.workflow "Design controls", ci.event pull_request and ci.runner "Linux X64", and no user
      When the tests run locally with an uncommitted file in the repository
      Then executor.json names a local run with the build name "<user>@<host>"
      And environment.properties records the user and no ci. entry
      And the commit under test with the worktree "dirty"
      And executor.json and environment.properties sit beside the two tests' results
      When the tests run from a copy of the repository outside git
      Then the commit and the worktree are both recorded as "unknown"
