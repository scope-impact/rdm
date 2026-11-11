Feature: Specification
  The accepted scenarios of this context: how RDM reads the spec it holds a
  record to (its requirements and the user needs they address), how a project
  is scaffolded or an existing repository adopted, and how validation by a
  person shows at release. Each is stamped with an item id that is never
  renumbered or reused, and verified by the acceptance tests whose Allure
  results carry that id as a tag label.

  Rule: The spec's requirements are its Gherkin scenarios tagged with an id, read whole and never dropped quietly

    # ../publishing/allium/publishing.allium, surface CompileDhf, guarantee SpecReadAsTaggedGherkin
    @allium-guarantee.SpecReadAsTaggedGherkin
    @item-01-001
    Scenario: Every tagged scenario is a requirement, wherever the spec's layout keeps it
      Given a Löyly plan whose slices are 03-gate, 04-stamps, 05-later with status planned and 06-someday with status backlog
      And 03-gate's spec.feature holds, under the Rule "Booting", a scenario "boots fresh" tagged @allium-rule-success.BootFresh @allium-guarantee.Fresh @item-03-001, a scenario "  refuses a busy seam" tagged @item-03-009 @item-03-002, and an untagged scenario "has no stamp"
      And 03-gate has a drafts.feature, and an entry "notes" that is no slice holds a spec.feature
      When the spec is read
      Then its requirements are item-03-001, item-03-002 and item-04-001, in plan order, in the contexts gate and stamps
      And 05-later and 06-someday are skipped and recorded as planned and backlog, and the drafts and "notes" are never read
      And item-03-001 keeps its Rule "Booting", its title and its source line ".pi/plan/03-gate/spec.feature:6"
      And the second scenario's id is item-03-002, the last of its item tags, and its title is trimmed to "refuses a busy seam"
      And item-04-001 has no Rule: a Rule never carries over from another file
      And item-03-001's Allium obligations are rule-success BootFresh and guarantee Fresh
      And "has no stamp" is listed as unstamped at ".pi/plan/03-gate/spec.feature:14", and no id is stamped twice
      And reading the spec imports no planning tool
      And Löyly's own spec, when present, reads as its checklist golden: 201 stamped items, per context
      Given instead a repository with no spec configuration and feature files under features/
      And features/alarms/sound.feature holds, under the Rule "Thresholds", a scenario "a high heart rate alarms" tagged @smoke @REQ-1, and an untagged scenario "not yet tagged"
      And features/top.feature holds a scenario "at the root" tagged @REQ-2
      When the spec is read
      Then features/ is found, and REQ-1 and REQ-2 are its requirements: any id-shaped tag is an id
      And REQ-1 is in the context alarms, under the Feature "Alarms sound" and the Rule "Thresholds", sourced at features/alarms/sound.feature:7, keeping the tag @smoke
      And REQ-2, at the root, has no context
      And "not yet tagged" is listed as unstamped at features/alarms/sound.feature:10
      When the configuration names the globs "../features/**/*.feature", excludes "top.feature" and sets the id pattern "REQ-1"
      Then the only requirement is REQ-1

  Rule: A project starts from one command, and an existing repository is adopted without disturbing it

    # scaffolding.allium, rule Init (requires 1)
    @allium-rule-success.Init @allium-rule-failure.Init.1
    @item-01-002
    Scenario: rdm init scaffolds a project that builds offline, and refuses a directory that exists
      Given the directory "regulatory" does not exist
      When the regulatory author runs rdm init into "regulatory"
      Then it names "regulatory", the next steps and rdm gap among them
      And it lays down the Makefile, the render configuration config.yml and the documents directory
      And the document templates, the software design specification, the design review and the traceability matrix among them
      And no document fetches an image from the network, the Makefile rebuilds Word output when reference.docx changes, and the Dockerfile installs RDM from its git repository rather than a copied wheel
      And a spec skeleton written the Löyly way: .pi/plan/01-core linking .pi/spec/core, with spec.feature, drafts.feature, allium/core.allium, event-model/area.edn and .pi/spec/event-storm.edn
      And RDM reads it as one user need, "core/desk", linked to the surface Desk, with no requirement yet
      When rdm init is run into "regulatory" again
      Then it is refused, saying the directory exists

    # scaffolding.allium, rule Adopt
    @allium-rule-success.Adopt
    @item-01-003
    Scenario: rdm adopt lays down the DHF skeleton and the CI gates, skipping what exists
      Given a repository with src/app.py and its own dhf/README.md
      When the regulatory author runs rdm adopt in it
      Then the V&V plan and the traceability matrix template are laid down under dhf/documents
      And the CI workflow calls RDM's reusable gates workflow pinned to the RDM version that adopted it
      And dhf/README.md and src/app.py are left as they were, and dhf/README.md is reported as skipped
      And a .gitignore of what RDM generates (allure-results/, dhf/data/, .rdm/, __pycache__/) and dhf/config.yml are laid down
      And the repository, having no spec, gets a spec skeleton written the Löyly way (.pi/plan/01-core linking .pi/spec/core), which RDM reads as the user need "core/desk"
      When rdm adopt is run again
      Then nothing is laid down: everything exists
      Given another repository whose .gitignore holds "node_modules/" and whose dhf/config.yml is a symbolic link to a file outside it that does not exist
      When rdm adopt is run in it
      Then its .gitignore is kept as it was, and the lines to add to it are named
      And nothing is written through the symbolic link
      Given a third repository that already keeps its spec as Gherkin under spec/
      When rdm adopt is run in it
      Then no spec skeleton is laid down beside it

  Rule: Validation is made by a person, and a user need without it shows at release

    # scaffolding.allium, surface ValidationAtRelease, guarantee UnvalidatedNeedsNamedAtRelease
    @allium-guarantee.UnvalidatedNeedsNamedAtRelease
    @item-01-004
    Scenario: The release gate warns of each user need without an approved validation record
      Given a verified record whose spec has the user needs Alarm and Log, and no validation records
      When the release gate runs
      Then it warns that Alarm and Log have no approved validation record, and release passes
      When dhf/validation holds a record for Alarm, disposition approved, reviewer "maintainer (summative)", and one for Log, disposition pending
      Then the release gate warns of Log only, and release passes
      And the records are read keyed by user need, with their disposition and reviewer, and Log is the one need unvalidated
      When Alarm's approved record names no reviewer, an empty one, or a reviewer that is not text
      Then Alarm and Log are both unvalidated: a person makes a validation

  Rule: The user needs come from the source the spec's configuration names

    # ../release/allium/release.allium, surface TraceVerification, guarantee SpecNeedsReadFromTheirSource
    @allium-guarantee.SpecNeedsReadFromTheirSource
    @item-01-005
    Scenario: A need is a Feature by default, or an event-model screen with its Allium surfaces and storm events
      Given plain Gherkin under spec/alarms: the Feature "Alarms sound" described "A nurse is alerted to a dangerous vital." holding REQ-1, and the Feature "Quiet hours" holding REQ-2
      When the spec is read
      Then its needs are "Alarms sound" and "Quiet hours", in the context alarms, "Alarms sound" with its description as its text
      And REQ-1 addresses "Alarms sound", and "Quiet hours" is addressed by REQ-2
      Given instead a Löyly plan with an event storm, a context desk whose screen operator-desk ("The operator's view: derive a checklist.") links the surface Desk, facing an Operator, providing Derive, guaranteeing NeverWritten, with the command derive that produces the storm event "Checklist derived" an Operator causes
      And desk's Allium also has a surface Seam that no screen links, and a context report with no screens whose surface Report ("An actor reads the report.") faces a Reader
      And item-01-001 is tagged @allium-rule-success.Derive, item-01-002 @allium-rule-failure.Unrelated, item-02-001 @allium-guarantee.Readable
      When the spec is read
      Then desk/operator-desk is a need with that text, the surface Desk, the actor Operator, the rule Derive, the guarantee NeverWritten, the command desk/derive and the storm event "Checklist derived (Operator)"
      And Seam is no need: a context with screens takes its needs from them
      And Report is a need, its doc comment its text, its actor Reader
      And item-01-001 addresses desk/operator-desk directly, item-01-002 through its context, and item-02-001 addresses Report
      And Löyly's own spec, when present, has six needs, one screen per context, each directly addressed
