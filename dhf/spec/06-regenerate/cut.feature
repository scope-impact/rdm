@UN-006
Feature: Cut
  Tags HEAD spec-N when it is origin/HEAD and pushes the tag; a regeneration
  reads nothing else.

  Rule: A cut is a pushed tag on the default branch, and regeneration reads only a cut

    @spec-0036 @e2e
    Scenario: HEAD on origin/HEAD is tagged spec-N and the tag is pushed
      Given HEAD is the commit origin/HEAD names, and the last cut is spec-6
      When "rdm-spec cut" is run
      Then HEAD is tagged spec-7
      And the tag is pushed to origin

  Rule: Every merge to the default branch is cut, once

    @spec-0162 @e2e
    Scenario: Cutting a commit already cut changes nothing
      Given the commit tagged spec-7
      When "rdm-spec cut" is run again on it
      Then no tag is added and the cut says the commit is already spec-7

    @spec-0224 @e2e
    Scenario: A HEAD whose spec fails the check is not cut
      Given HEAD is the commit origin/HEAD names, and "rdm-spec check" exits 1 on it
      When "rdm-spec cut" is run
      Then it exits 1, tags nothing, and prints each of the check's finding lines unchanged

    @spec-0163 @e2e
    Scenario: A HEAD that is not origin/HEAD is refused
      Given HEAD is not the commit origin/HEAD names
      When "rdm-spec cut" is run on it
      Then it exits 2 and names the branch

  Rule: Cut names the cut it made, and runs nothing it need not

    @spec-0478 @contract
    Scenario Outline: Cut prints the cut it made, or the one the commit already is
      Given HEAD is the commit origin/HEAD names, C, <state>
      When "rdm-spec cut" is run
      Then it exits 0, and its standard output, read as EDN, is exactly <result>, S saying the commit is already spec-7

      Examples:
        | state                                | result                                                         |
        | and the last cut is spec-6           | {:cut {:tag "spec-7" :commit C :tagged true :pushed "origin"}} |
        | and C is tagged spec-7               | {:cut {:tag "spec-7" :commit C :tagged false :says S}}         |

    @spec-0479 @e2e
    Scenario Outline: The next cut is one more than the highest spec-N tag of the repository
      Given HEAD is the commit origin/HEAD names, and the repository's tags are <tags>
      When "rdm-spec cut" is run
      Then HEAD is tagged <next>

      Examples:
        | tags                                   | next    |
        | none                                   | spec-1  |
        | spec-9, spec-2, spec-10a and release-11 | spec-10 |

    @spec-0480 @e2e
    Scenario Outline: Cut runs the check in the project's root and relays what it says
      Given HEAD is the commit origin/HEAD names, and "rdm-spec check" run in the project's root <does>
      When "rdm-spec cut" is run
      Then it <outcome>

      Examples:
        | does                                         | outcome                                                                                   |
        | exits 2, saying cannot-run: why               | exits 2, tags nothing, and its error stream holds the check's cannot-run line unchanged    |
        | exits 0, writing a warning line on its error stream | tags HEAD, exits 0, and its error stream holds the check's warning line unchanged     |

    @spec-0481 @e2e
    Scenario Outline: A repository cut cannot reach is refused, and a tag that cannot be pushed is removed
      Given HEAD is a commit of the default branch, and <state>
      When "rdm-spec cut" is run
      Then it exits 2, <says>, and the repository holds no tag it did not hold before

      Examples:
        | state                                    | says                                                          |
        | the repository has no remote origin       | names origin as missing                                       |
        | origin names no HEAD                      | names origin/HEAD as missing                                  |
        | pushing to origin fails                   | names the tag it could not push and relays the push's message, as one line |

    @spec-0482 @e2e
    Scenario Outline: Cut answers its first refusal, and runs the check last
      Given <state>, and "rdm-spec check" exits 1 on HEAD
      When "rdm-spec cut" is run
      Then it <outcome>, and runs no check

      Examples:
        | state                                                     | outcome                                                  |
        | HEAD is not the commit origin/HEAD names, and is tagged spec-7 | exits 2 and names the branch                         |
        | HEAD is the commit origin/HEAD names, and is tagged spec-7     | exits 0 and says the commit is already spec-7        |
