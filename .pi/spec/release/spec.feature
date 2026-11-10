Feature: Release
  The accepted scenarios of this context: what holds a release back, how a
  requirement's status is traced from executed results, what a release
  retains, and RDM's gates for other repositories' CI. Each is stamped with
  an item id that is never renumbered or reused, and verified by the
  acceptance tests whose Allure results carry that id as a tag label.

  Rule: A release is blocked until the spec is read whole and every requirement has a passing run

    # release.allium, rule ReleaseGate (requires 1 to 6)
    @allium-rule-success.ReleaseGate @allium-rule-failure.ReleaseGate.1 @allium-rule-failure.ReleaseGate.2
    @allium-rule-failure.ReleaseGate.3 @allium-rule-failure.ReleaseGate.4 @allium-rule-failure.ReleaseGate.5
    @allium-rule-failure.ReleaseGate.6
    @item-04-001
    Scenario: The release gate passes only when every requirement has a passing run and none fails
      Given a spec whose feature file stamps item-01-001 and item-01-002, both addressing the need Release
      When the release manager runs the release gate with no Allure results
      Then release is blocked, item-01-001 and item-01-002 untested
      When a passing run tagged item-01-001 is added
      Then release is blocked, naming item-01-002 and not item-01-001
      When a failing run tagged item-01-002 is added
      Then release is blocked: item-01-002 failed
      When item-01-002's run passes and a failing run tagged item-01-001 is added
      Then release is blocked, naming item-01-001: one passing run does not outweigh a failing one
      When the failing run of item-01-001 is removed
      Then release passes
      When an unstamped scenario "an unstamped behaviour" is added to the feature file
      Then release is blocked, naming "an unstamped behaviour"
      When a second scenario is stamped item-01-001 instead
      Then release is blocked: item-01-001 is stamped twice
      When a feature file "broken.feature" that Gherkin cannot parse is added instead
      Then release is blocked, naming broken.feature
      When the feature file holds no stamped scenario
      Then release is blocked: there is nothing to verify
      And a repository with no spec at all is blocked: no spec was found

  Rule: Each requirement's status is reconciled from the tag labels of executed Allure results

    # release.allium, rule Verify
    @allium-rule-success.Verify
    @item-04-002
    Scenario: Verified, failed and untested come from the runs that carry each requirement's id
      Given Allure results: run p passed tagged item-01-001, run f failed tagged item-01-002, run s skipped tagged item-01-003, run b broken tagged item-01-004
      When the requirements item-01-001 to item-01-005 are reconciled against them
      Then item-01-001 is verified
      And item-01-002 and item-01-004 have failed
      And item-01-003 and item-01-005 are untested
      When a failing run names item-01-001 only as its story, feature and epic labels
      Then item-01-001 is still verified: only a tag label claims a requirement
      When a passing run carries the tag label "@item-01-005"
      Then item-01-005 is verified: the "@" before the id is read
      When item-01-001 to item-01-003 address the need Release, item-01-004 and item-01-005 the need Trace, and verify writes the verification data
      Then the traceability matrix rendered from it shows each requirement's status under the need it addresses

  Rule: A release's evidence is retained as one bundle, generated from the record, never mixed with an earlier one

    # release.allium, rule EvidenceBundle (requires 1 to 2)
    @allium-rule-success.EvidenceBundle @allium-rule-failure.EvidenceBundle.1 @allium-rule-failure.EvidenceBundle.2
    @item-04-003
    Scenario: The evidence bundle holds the verification data, the rendered matrix, the report and the results
      Given a record with a spec, a traceability matrix template and Allure results with attachments and a container
      When the release manager writes the evidence bundle to an output directory apart from the results
      Then the bundle holds the verification data computed from the executed results
      And the traceability matrix rendered from it, with no template markers left
      And the executed results with every attachment and container they reference
      And the verification report, or the manifest says why it is not there
      And a manifest listing exactly the files bundled
      And a symbolic link among the results is not followed, and a missing attachment is listed in the manifest
      And an unreadable result is named in the manifest
      When the bundle is written again over the earlier one
      Then its results replace the earlier bundle's, never mixed with them
      When the output directory would hold the results or sit inside them
      Then the bundle is refused and the results are left untouched
      When the record has no traceability matrix template
      Then the bundle is refused, naming the missing template, and no bundle is written

  Rule: RDM's gates can be reused in another repository's CI, pinned to one RDM revision

    # ../specification/allium/scaffolding.allium, surface AdoptRepository, guarantee GatesReusableInCi
    @allium-guarantee.GatesReusableInCi
    @item-04-004
    Scenario: The reusable workflow and the gates action run RDM's gates at the revision the caller pins
      Given the composite gates action, the reusable gates workflow and the documents action
      When the gates action is run with the checklist part11_document_control over a record with results
      Then it runs verify, the release gate, graph validation and the evidence bundle with the verification report, in that order
      And graph validation holds the documents to the checklists named
      And a gate that fails fails the action
      And with the release gate off and no results yet, no gate runs
      And every rdm command the workflow and the actions run is one RDM's command line accepts
      And the action installs RDM from its own revision, the workflow from the revision the caller pins, never from a package index
      And the reusable workflow runs the caller's acceptance tests before the gates
      And the documents action renders with the image of the release it is pinned to, else the latest image
      And the workflow rdm adopt lays down calls the reusable workflow pinned to the installed RDM's version
      And RDM's own CI calls the same workflow, pinned to the commit under test
