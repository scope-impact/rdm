# The harness's own contract (docs/wow/invariants.md, INV-3). Every rule
# cites README.md or an invariant. Every When runs the command; every Then
# pins what it prints and how it exits. Nothing a scenario does not state is
# a contract.
#
# One format: TOML, for what a person writes (config, storm, model, the cycle
# record) and for what a task prints and writes (checklist, verdicts, score,
# verified). Every task prints exactly one TOML document on stdout, keys in
# the order shown, a blank line before each table header, and exits 0
# (passed), 1 (refused: a finding about the project) or 2 (cannot run: the
# harness's own defect or a missing input). TOML has no null: a value that is
# absent is an absent key. Nothing else is written to stdout; stderr is free.
#
# A scenario is deterministic unless tagged @nondeterministic: its Then can be
# matched byte for byte. A @nondeterministic scenario holds at least one value
# the spec cannot pin (a tree or commit hash, a duration, a uuid, a host, a
# monitor's reading), written as a placeholder <what it is> in a Then step or
# its docstring (a Given or When may describe one without being one); the
# oracle and a grader match a placeholder by its shape, and everything around
# it exactly.

Feature: The harness: storm, model, cut, fence, regenerate, gate, measure

  Background:
    Given a project folder with the configuration spec/config.toml
      """
      spec = "spec"
      storm = "spec/storm.toml"
      model = "spec/model.toml"
      raw = "raw"
      implementation = ["impl"]
      cycles = "cycles"
      whole-container-every = 5
      plugins = []

      [verify]
      command = ["impl/test.sh"]
      results = "impl/results"
      budget-seconds = 60
      """

  # ------------------------------------------------------------------
  # The front of the chain: README, "the storm is forced"; INV-1
  # ------------------------------------------------------------------

  Rule: A project keeps its storm as data, and every element cites a source

    Scenario: The shape of a storm
      Given spec/storm.toml
        """
        [actors.resident]
        text = "lives in the building"
        sources = ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]

        [commands.BookSlot]
        actor = "resident"
        aggregate = "Slot"
        sources = ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]

        [events.SlotBooked]
        command = "BookSlot"
        carries = ["date", "resource", "start"]
        sources = ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]

        [policies.BillOnBooking]
        when = "SlotBooked"
        then = "RecordCharge"
        sources = ["loyly:raw/domain/public/yle-sauna-fees-2022.md@17d68d2"]

        [aggregates.Slot]
        sources = ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]

        [read-models.WeeklySchedule]
        fed-by = ["SlotBooked"]
        sources = ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]

        [hotspots]
        """
      And raw/loyly/raw/domain/elicitation-2026-08-26.md and raw/loyly/raw/domain/public/yle-sauna-fees-2022.md exist
      When I run harness storm
      Then stdout is exactly
        """
        storm = "passed"
        actors = 1
        commands = 1
        events = 1
        policies = 1
        aggregates = 1
        read-models = 1

        [hotspots]
        open = 0
        decided = 0
        """
      And it exits 0

    Scenario: An element with no source is refused, naming it
      Given spec/storm.toml whose event SlotBooked has sources = []
      When I run harness storm
      Then stdout is exactly
        """
        storm = "refused"

        [finding]
        rule = "element-sourced"
        element = ["events", "SlotBooked"]
        """
      And it exits 1

    Scenario: A source that is not under raw/ is refused
      Given spec/storm.toml whose actor resident cites "https://example.org/sauna"
      When I run harness storm
      Then stdout is exactly
        """
        storm = "refused"

        [finding]
        rule = "source-is-raw"
        element = ["actors", "resident"]
        source = "https://example.org/sauna"
        """
      And it exits 1

    Scenario: A source whose file is not in raw/ is refused
      Given spec/storm.toml whose aggregate Slot cites "loyly:raw/domain/missing.md@17d68d2"
      When I run harness storm
      Then stdout is exactly
        """
        storm = "refused"

        [finding]
        rule = "source-exists"
        element = ["aggregates", "Slot"]
        path = "raw/loyly/raw/domain/missing.md"
        """
      And it exits 1

    Scenario: A command whose actor or aggregate the storm does not name is refused
      Given spec/storm.toml whose command BookSlot names the aggregate "Booking", which [aggregates] does not hold
      When I run harness storm
      Then stdout is exactly
        """
        storm = "refused"

        [finding]
        rule = "command-aggregate-named"
        element = ["commands", "BookSlot"]
        aggregate = "Booking"
        """
      And it exits 1

    Scenario: An event with no command is refused
      Given spec/storm.toml whose event SlotBooked has no command
      When I run harness storm
      Then stdout is exactly
        """
        storm = "refused"

        [finding]
        rule = "event-has-command"
        element = ["events", "SlotBooked"]
        """
      And it exits 1

    Scenario: A read model fed by an unknown event is refused
      Given spec/storm.toml whose read model WeeklySchedule is fed-by = ["SlotCancelled"], which [events] does not hold
      When I run harness storm
      Then stdout is exactly
        """
        storm = "refused"

        [finding]
        rule = "read-model-fed-by-event"
        element = ["read-models", "WeeklySchedule"]
        event = "SlotCancelled"
        """
      And it exits 1

    Scenario: The first finding stops the run
      Given spec/storm.toml with an unsourced actor and an unsourced event
      When I run harness storm
      Then stdout holds one [finding], the actor's
      And it exits 1

  Rule: No storm, no cut (INV-1)

    Scenario: A project with no storm is refused by check
      Given no spec/storm.toml
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "storm"

        [finding]
        rule = "storm-exists"
        path = "spec/storm.toml"
        """
      And it exits 1

    Scenario: An open hotspot refuses a cut
      Given spec/storm.toml whose [hotspots.H1] holds text = "who may book a second slot" and status = "open"
      When I run harness cut spec-1
      Then stdout is exactly
        """
        cut = "refused"
        stage = "storm"

        [finding]
        rule = "hotspots-decided"
        hotspot = "H1"
        """
      And it exits 1
      And no tag spec-1 exists

    Scenario: A decided hotspot carries its decision and a source
      Given spec/storm.toml whose [hotspots.H1] holds status = "decided", decision = "when there is room" and sources = ["loyly:docs/wiki/pages/actors-and-fairness.md@17d68d2"]
      When I run harness storm
      Then stdout holds [hotspots] with open = 0 and decided = 1
      And it exits 0

    Scenario: A decided hotspot with no decision text is refused
      Given spec/storm.toml whose hotspot H1 has status = "decided" and no decision
      When I run harness storm
      Then stdout is exactly
        """
        storm = "refused"

        [finding]
        rule = "decision-stated"
        hotspot = "H1"
        """
      And it exits 1

  Rule: The model is derived from the storm, never drawn (INV-1)

    # The model's vocabulary is the C4 model's, with the Structurizr DSL's
    # names for each element and attribute (raw/c4/, decision 0003):
    # softwareSystem, container, component, group (the bounded context),
    # relationship, deploymentEnvironment, deploymentNode, infrastructureNode,
    # containerInstance, and description, technology, tags on each. The
    # harness adds three keys of its own and no more: owns (the storm
    # elements a component owns), spec (its feature file) and event (the
    # storm event a relationship carries). A component is named
    # <container>/<component>.

    Scenario: The shape of a model
      Given spec/model.toml
        """
        [softwareSystem]
        name = "Booking"
        description = "sauna slots for a housing cooperative"

        [containers.booking]
        description = "the booking application"
        technology = "a web application"

        [containers.booking.components.slots]
        group = "booking"
        description = "slots and the weekly schedule"
        owns = ["Slot", "WeeklySchedule"]
        spec = "spec/slots.feature"

        [containers.booking.components.billing]
        group = "billing"
        description = "charges for bookings"
        owns = ["Charge"]
        spec = "spec/billing.feature"

        [[relationships]]
        from = "booking/slots"
        to = "booking/billing"
        description = "a booking is charged"
        event = "SlotBooked"

        [deploymentEnvironment.production]

        [deploymentEnvironment.production.deploymentNode.host]
        description = "the cooperative's server"
        technology = "a virtual machine"
        containerInstance = ["booking"]
        """
      And a storm holding the aggregates Slot and Charge, the read model WeeklySchedule and the event SlotBooked
      When I run harness model
      Then stdout is exactly
        """
        model = "passed"
        containers = 1
        components = 2
        relationships = 1
        deploymentEnvironments = 1
        """
      And it exits 0

    Scenario: A key the C4 vocabulary and the harness's three do not hold is refused
      Given spec/model.toml whose component "booking/slots" carries the key layer
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "model-vocabulary"
        element = "booking/slots"
        key = "layer"
        """
      And it exits 1

    Scenario: A component that owns nothing of the storm is refused
      Given spec/model.toml whose component "booking/audit" has owns = []
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "component-owns-storm-element"
        component = "booking/audit"
        """
      And it exits 1

    Scenario: A component that owns something the storm does not name is refused
      Given spec/model.toml whose component "booking/slots" owns "Waitlist", which the storm does not hold
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "owned-element-in-storm"
        component = "booking/slots"
        element = "Waitlist"
        """
      And it exits 1

    Scenario: An aggregate owned by two components is refused
      Given spec/model.toml where "booking/slots" and "booking/billing" both own "Slot"
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "element-owned-once"
        element = "Slot"
        components = ["booking/billing", "booking/slots"]
        """
      And it exits 1

    Scenario: A storm aggregate or read model no component owns is refused
      Given the storm holds the aggregate "Charge" and no component owns it
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "element-owned"
        element = "Charge"
        """
      And it exits 1

    Scenario: A container with no component is refused
      Given spec/model.toml whose container "ledger" holds no components table
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "container-has-components"
        container = "ledger"
        """
      And it exits 1

    Scenario: Components of different groups relate only through a storm event
      Given spec/model.toml whose relationship from "booking/slots" to "booking/billing" names no event
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "groups-relate-by-event"
        from = "booking/slots"
        to = "booking/billing"
        """
      And it exits 1

    Scenario: A relationship naming an event the storm does not hold is refused
      Given spec/model.toml whose relationship names the event "SlotCancelled", which the storm does not hold
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "event-in-storm"
        from = "booking/slots"
        to = "booking/billing"
        event = "SlotCancelled"
        """
      And it exits 1

    Scenario: A relationship naming a component the model does not hold is refused
      Given spec/model.toml whose relationship is to "booking/waitlist"
      When I run harness model
      Then stdout holds [finding] with rule = "component-named", component = "booking/waitlist"
      And it exits 1

    Scenario: The publisher of an event is the component owning the aggregate of its command
      Given the storm's event SlotBooked is raised by the command BookSlot on the aggregate Slot, owned by "booking/slots"
      When I run harness model --events
      Then stdout holds [events.SlotBooked] with publisher = "booking/slots" and subscribers = ["booking/billing"]

    Scenario: An event the storm holds that no relationship carries is refused, unless its publisher and subscribers share a group
      Given a storm holding the event "SlotCancelled" and a model whose relationships never name it, and no component of another group needs it
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "event-carried"
        event = "SlotCancelled"
        """
      And it exits 1

    Scenario: A container instance must name a container of the model
      Given the deployment node "host" has containerInstance = ["api"], which [containers] does not hold
      When I run harness model
      Then stdout is exactly
        """
        model = "refused"

        [finding]
        rule = "containerInstance-names-container"
        deploymentNode = "production/host"
        container = "api"
        """
      And it exits 1

    Scenario: Deployment nodes nest
      Given [deploymentEnvironment.production.deploymentNode.cloud.deploymentNode.vm] with containerInstance = ["booking"]
      When I run harness model
      Then stdout holds model = "passed"

  Rule: The spec never names the stack, and the stack is declared where C4 puts it (INV-2)

    # The stack lives in the model's technology keys (containers,
    # components, deployment nodes, infrastructure nodes, relationships).
    # The spec's firewall reads those values: a feature file or a storm that
    # names a declared technology is refused. There is no other word list.

    Scenario: A declared technology named in a feature file is refused, naming the line
      Given spec/model.toml declares technology = "a relational database" on the container "store"
      And spec/slots.feature line 12 reads "Then the relational database row is updated"
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "stack-words"

        [finding]
        rule = "no-stack-words"
        path = "spec/slots.feature"
        line = 12
        technology = "a relational database"
        declared = "containers.store"
        """
      And it exits 1

    Scenario: A declared technology named in the storm is refused the same way
      Given spec/model.toml declares technology = "a message broker" on the infrastructure node "bus"
      And spec/storm.toml's policy BillOnBooking has then = "write to the message broker" on line 19
      When I run harness check
      Then stdout holds [finding] with rule = "no-stack-words", path = "spec/storm.toml", line = 19, technology = "a message broker"
      And it exits 1

    Scenario: The match is on the technology's words, whole and regardless of case
      Given spec/model.toml declares technology = "Rust" on the container "booking"
      And spec/slots.feature line 20 reads "Then the resident trusts the schedule"
      When I run harness check
      Then stdout holds no finding with rule = "no-stack-words"

    Scenario: A technology is matched by each of its words that is not a common word
      Given spec/model.toml declares technology = "a web application"
      And spec/slots.feature line 9 reads "Then the application shows the schedule"
      When I run harness check
      Then stdout holds no finding with rule = "no-stack-words"
      # "web", "application" and "a" are common words; the list of common
      # words is the harness's own (harness/common-words.toml), declared
      # shared, and is the one list that is not derived.

    Scenario: raw/ and the model are exempt
      Given raw/loyly/README.md and spec/model.toml name every declared technology
      When I run harness check
      Then stdout holds no finding with rule = "no-stack-words"

  # ------------------------------------------------------------------
  # The spec and its ids: INV-6
  # ------------------------------------------------------------------

  Rule: Every scenario belongs to one component, and every component owns at least one

    Scenario: A feature file the model does not name is refused
      Given spec/waitlist.feature, which no component's spec names
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "spec"

        [finding]
        rule = "feature-owned-by-component"
        path = "spec/waitlist.feature"
        """
      And it exits 1

    Scenario: A component whose feature file has no scenario is refused
      Given spec/billing.feature holding a Feature line and nothing else
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "spec"

        [finding]
        rule = "component-has-scenarios"
        component = "booking/billing"
        path = "spec/billing.feature"
        """
      And it exits 1

  Rule: The stamper gives every scenario a stable id, once, never re-derived

    Scenario: An untagged scenario is given the next free id in the project
      Given spec/slots.feature with two scenarios, the first tagged @item-003 and the second untagged
      And spec/billing.feature with one scenario tagged @item-007
      When I run harness stamp
      Then the second scenario of spec/slots.feature is tagged @item-008 on the line above it, with the scenario's own indentation
      And stdout is exactly
        """
        stamp = "passed"
        stamped = 1
        files = ["spec/slots.feature"]
        next = 9
        """
      And it exits 0

    Scenario: Stamping twice changes nothing
      Given every scenario is stamped
      When I run harness stamp
      Then no file changes
      And stdout is exactly
        """
        stamp = "passed"
        stamped = 0
        files = []
        next = 9
        """
      And it exits 0

    Scenario: Rewording a scenario does not change its id
      Given the scenario tagged @item-003 is retitled
      When I run harness stamp
      Then it is still tagged @item-003

    Scenario: A deleted scenario's id is never reused
      Given @item-008 was the highest id and its scenario was deleted
      When I run harness stamp on a new untagged scenario
      Then it is tagged @item-009

    Scenario: A Scenario Outline is stamped exactly like a Scenario

    Scenario: Two scenarios carrying the same id are refused
      Given @item-003 appears on two scenarios
      When I run harness stamp
      Then stdout is exactly
        """
        stamp = "refused"

        [finding]
        rule = "id-once"
        id = "item-003"
        paths = ["spec/billing.feature", "spec/slots.feature"]
        """
      And it exits 1

  Rule: The checklist is derived, one item per stamped scenario, grouped by component

    Scenario: The shape of a checklist
      Given spec/slots.feature holds @item-003 "A slot is booked" under the Rule "Booking" at line 9, and @item-008 "A second slot needs room" under no Rule at line 20
      And spec/billing.feature holds @item-007 "A booking is charged" at line 6
      When I run harness checklist
      Then stdout is exactly
        """
        checklist = "passed"

        [[items]]
        id = "item-007"
        component = "booking/billing"
        name = "A booking is charged"
        source = "spec/billing.feature:6"

        [[items]]
        id = "item-003"
        component = "booking/slots"
        rule = "Booking"
        name = "A slot is booked"
        source = "spec/slots.feature:9"

        [[items]]
        id = "item-008"
        component = "booking/slots"
        name = "A second slot needs room"
        source = "spec/slots.feature:20"
        """
      And it exits 0

    Scenario: Items are ordered by component, then by file, then by position

    Scenario: An unstamped scenario stops the checklist rather than shrinking it
      Given spec/slots.feature has an untagged scenario at line 25
      When I run harness checklist
      Then stdout is exactly
        """
        checklist = "refused"

        [finding]
        rule = "every-scenario-stamped"
        path = "spec/slots.feature"
        line = 25
        """
      And it exits 1

    Scenario: An empty spec stops the checklist rather than producing an empty one
      Given no feature file holds a scenario
      When I run harness checklist
      Then stdout is exactly
        """
        checklist = "refused"

        [finding]
        rule = "spec-not-empty"
        """
      And it exits 1

    Scenario: A scenario tagged @wip is listed and marked, never dropped
      Given @item-008 is also tagged @wip
      When I run harness checklist
      Then its item carries wip = true

  Rule: A scenario is deterministic unless it says it is not, and then it names what it cannot pin

    # A deterministic scenario's Then is matched byte for byte: by the
    # harness's oracle against a golden, by a grader against the product.
    # A @nondeterministic scenario names each value it cannot pin as a
    # placeholder <what it is> in a Then step or its docstring, and is matched
    # by shape there and exactly everywhere else. A Given or When may describe
    # a placeholder without being one. The tag is the grader's and the
    # oracle's instruction; the placeholder is the builder's.

    Scenario: A placeholder in an untagged scenario is refused
      Given spec/slots.feature holds, at line 14, an untagged scenario whose Then reads "Then the receipt shows <the booking's reference>"
      When I run harness check
      Then stdout holds check = "refused", stage = "spec" and [finding] with rule = "placeholder-tagged", path = "spec/slots.feature", line = 14 and placeholder naming the booking's reference
      And it exits 1

    Scenario: A nondeterministic scenario with no placeholder is refused
      Given spec/slots.feature holds, at line 30, a scenario tagged @nondeterministic whose steps hold no placeholder
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "spec"

        [finding]
        rule = "nondeterministic-has-placeholder"
        path = "spec/slots.feature"
        line = 30
        """
      And it exits 1

    Scenario: The checklist marks a nondeterministic item and its placeholders
      Given @item-008 is tagged @nondeterministic and its Then holds <the booking's reference> and <the time it was made>
      When I run harness checklist
      Then its item carries nondeterministic = true and placeholders = ["the booking's reference", "the time it was made"]

    Scenario: A placeholder's shape is declared once, in the spec, and an undeclared one is refused
      Given spec/placeholders.toml
        """
        [placeholders."the booking's reference"]
        shape = "[A-Z]{3}-[0-9]{4}"

        [placeholders."the time it was made"]
        shape = "[0-9]{2}:[0-9]{2}"

        [placeholders."the confirmation page"]
        judge = "page-judge"
        """
      And spec/slots.feature uses <the slot's receipt number>, which it does not declare
      When I run harness check
      Then stdout holds check = "refused", stage = "spec" and [finding] with rule = "placeholder-declared", path = "spec/slots.feature", line = 30 and placeholder naming the slot's receipt number
      And it exits 1

    Scenario: A placeholder is a pattern or a judge, never both and never neither
      Given spec/placeholders.toml declares "the confirmation page" with neither shape nor judge
      When I run harness check
      Then stdout holds [finding] with rule = "placeholder-shape", placeholder = "the confirmation page"
      And it exits 1

    Scenario: A placeholder naming a judge the configuration does not declare is refused
      Given spec/placeholders.toml declares "the confirmation page" with judge = "page-judge"
      And spec/config.toml declares no plugin named page-judge answering item
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "spec"

        [finding]
        rule = "judge-declared"
        placeholder = "the confirmation page"
        judge = "page-judge"
        """
      And it exits 1

    Scenario: The checklist names the judge of a judged placeholder
      Given @item-009 is tagged @nondeterministic, its Then holds <the confirmation page>, and that placeholder is judged by page-judge
      When I run harness checklist
      Then its item carries nondeterministic = true, placeholders = ["the confirmation page"] and judges = ["page-judge"]

    Scenario: A judge is a plugin answering item, and its goldens are checked like any plugin's
      Given spec/config.toml holds
        """
        [[plugins]]
        name = "page-judge"
        answers = "item"
        command = ["plugins/page-judge/judge"]
        """
      And plugins/page-judge/judge, run with --check, answers its goldens and exits 1 with [finding] rule = "judge-goldens", example = "item-009/cycle-04"
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "plugins"
        plugin = "page-judge"

        [finding]
        rule = "judge-goldens"
        example = "item-009/cycle-04"
        """
      And it exits 1

    Scenario: Examples of an Outline are deterministic rows, never placeholders
      Given a Scenario Outline whose Then reads "Then it exits <code>" with an Examples table for <code>
      When I run harness check
      Then stdout holds no finding with rule = "placeholder-tagged"

  # ------------------------------------------------------------------
  # The cut and its scope: INV-4
  # ------------------------------------------------------------------

  Rule: A cut is a tag on a merged spec that check passed

    @nondeterministic
    Scenario: A cut tags the head of the default branch
      Given check passes and the working tree is clean on the default branch
      When I run harness cut spec-1
      Then the tag spec-1 points at HEAD
      And stdout is exactly
        """
        cut = "passed"
        tag = "spec-1"
        commit = "<HEAD's hash>"
        items = 3
        """
      And it exits 0

    Scenario: A dirty tree is refused
      Given spec/slots.feature has uncommitted changes
      When I run harness cut spec-1
      Then stdout is exactly
        """
        cut = "refused"

        [finding]
        rule = "tree-clean"
        paths = ["spec/slots.feature"]
        """
      And it exits 1

    Scenario: A cut whose check fails is refused with check's finding
      Given check refuses at the stage storm with rule = "storm-exists"
      When I run harness cut spec-1
      Then stdout is exactly
        """
        cut = "refused"
        stage = "storm"

        [finding]
        rule = "storm-exists"
        path = "spec/storm.toml"
        """
      And it exits 1

    Scenario: A tag that exists is refused
      Given the tag spec-1 exists
      When I run harness cut spec-1
      Then stdout is exactly
        """
        cut = "refused"

        [finding]
        rule = "tag-free"
        tag = "spec-1"
        """
      And it exits 1

  Rule: A scope is computed from two cuts, never typed

    Scenario: The components whose scenarios changed are in scope
      Given between spec-1 and spec-2 only spec/billing.feature changed
      When I run harness scopes spec-1 spec-2
      Then stdout is exactly
        """
        scopes = "passed"
        from = "spec-1"
        to = "spec-2"
        changed = ["booking/billing"]
        reached = []
        components = ["booking/billing"]
        containers = ["booking"]
        whole-container = false
        """
      And it exits 0

    Scenario: A change to an event reaches its publisher and every subscriber
      Given between spec-1 and spec-2 the storm's event SlotBooked gained a carried key
      When I run harness scopes spec-1 spec-2
      Then stdout holds changed = [], reached = ["booking/billing", "booking/slots"] and components = ["booking/billing", "booking/slots"]

    Scenario: A change to the model or the storm that moves a component makes its container the scope
      Given between spec-1 and spec-2 the component "booking/billing" moved to the container "ledger"
      When I run harness scopes spec-1 spec-2
      Then stdout holds whole-container = true and containers = ["booking", "ledger"]

    Scenario: Nothing changed is a refusal, not an empty scope
      Given spec-1 and spec-2 point at the same tree
      When I run harness scopes spec-1 spec-2
      Then stdout is exactly
        """
        scopes = "refused"

        [finding]
        rule = "cuts-differ"
        from = "spec-1"
        to = "spec-2"
        """
      And it exits 1

    Scenario: A scope is never read from a file
      Given cycles/03/cycle.toml holds components = ["booking/slots"]
      When I run harness fence 03
      Then stdout is exactly
        """
        fence = "refused"

        [finding]
        rule = "scope-computed"
        path = "cycles/03/cycle.toml"
        key = "components"
        """
      And it exits 1

  Rule: Every Nth cycle is a whole-container cycle, and the scoreboard says when one is due

    Scenario: The fifth cycle since the last whole-container cycle is whole
      Given whole-container-every = 5 and cycles 01 to 04 since the last whole-container cycle
      When I run harness scopes spec-4 spec-5
      Then stdout holds whole-container = true and components = every component of the model

    Scenario: The scoreboard names the cycle a whole-container cycle is due
      When I run harness scoreboard 04
      Then stdout holds whole-container-due = "05"

  # ------------------------------------------------------------------
  # The clean room: README, "regenerate, not copy"
  # ------------------------------------------------------------------

  Rule: The fence is physical and proved

    @nondeterministic
    Scenario: A room holds the cut and nothing of the scope's implementation or any measurement
      Given cycles/03/cycle.toml names cut = "spec-3" and from = "spec-2"
      And scopes spec-2 spec-3 answers components = ["booking/billing"]
      And impl/billing/ and impl/slots/ hold the previous build, and cycles/ the previous measurements
      When I run harness fence 03
      Then cycles/03/room/ is a clone of spec-3 whose history holds no commit touching impl/billing/ or cycles/
      And impl/slots/ is in the room, unchanged
      And stdout is exactly
        """
        fence = "passed"
        cycle = "03"
        cut = "spec-3"
        room = "cycles/03/room"
        removed = ["cycles", "impl/billing"]
        kept = ["impl/slots"]
        tree = "<the room's tree hash>"
        """
      And it exits 0

    Scenario: A whole-container scope removes every implementation of the container
      Given scopes answers whole-container = true and containers = ["booking"]
      When I run harness fence 04
      Then stdout holds removed = ["cycles", "impl/billing", "impl/slots"] and kept = []

    @nondeterministic
    Scenario: The proof fails if a removed path is reachable in the room's history
      Given a room whose history still reaches impl/billing/
      When I run harness fence --prove 03
      Then stdout is exactly
        """
        fence = "refused"

        [finding]
        rule = "room-proved"
        path = "impl/billing"
        commit = "<the reachable commit>"
        """
      And it exits 1

    Scenario: A room that exists is not rebuilt silently
      Given cycles/03/room exists
      When I run harness fence 03
      Then stdout is exactly
        """
        fence = "refused"

        [finding]
        rule = "room-fresh"
        room = "cycles/03/room"
        """
      And it exits 1

  Rule: One builder per cycle, in the room, reading the cut's plan first

    Scenario: The cycle record names one builder
      Given cycles/03/cycle.toml names two builders
      When I run harness verify 03
      Then stdout is exactly
        """
        verify = "refused"
        cycle = "03"

        [finding]
        rule = "one-builder"
        """
      And it exits 1

  # ------------------------------------------------------------------
  # The gate: INV-5
  # ------------------------------------------------------------------

  Rule: "Done" is ratified by verify, which the builder does not control

    # Verify reads results, never the runner (INV-11): the suite writes one
    # result per scenario run, in Allure's result model
    # (raw/allure/docs/how-it-works-test-result-file.md): a file
    # {uuid}-result.json in the folder the configuration names; the item it
    # verifies is the label "item" and what wrote it is the label "tool",
    # among whatever other labels the tool sets; its status is passed,
    # failed, broken, skipped or unknown, and only passed verifies; its
    # attachments ({uuid}-attachment.{ext}, named on the result by name,
    # source and type) sit beside it. Steps, containers and historyId are
    # not read. A tool is anything that answers a scenario: a runner, a
    # browser driver, a metrics query, a telemetry trace, a person. Verify
    # empties the folder before each run and copies what the run wrote into
    # cycles/NN/results/run-N/.

    @nondeterministic
    Scenario: A green suite, twice, inside the budget, is ratified from its results
      Given cycles/03/room holds a build whose suite writes, in 4 seconds, three results labelled item = item-003, item-007 and item-008, each with status passed
      And item-003's and item-007's results are labelled tool = "gherkin-runner", and item-008's tool = "browser-driver" with the attachment trace.zip
      When I run harness verify 03
      Then cycles/03/results/run-1/ and run-2/ hold the three results and trace.zip
      And cycles/03/verified.toml is written with the same content as stdout
      And stdout is exactly
        """
        verify = "passed"
        cycle = "03"
        tree = "<the room's tree hash>"
        budget-seconds = 60
        margin-seconds = <the budget less the slower run>

        [[runs]]
        results = 3
        passed = 3
        failed = 0
        broken = 0
        skipped = 0
        attachments = 1
        seconds = <the run's seconds>
        items = ["item-003", "item-007", "item-008"]
        tools = ["browser-driver", "gherkin-runner"]

        [[runs]]
        results = 3
        passed = 3
        failed = 0
        broken = 0
        skipped = 0
        attachments = 1
        seconds = <the run's seconds>
        items = ["item-003", "item-007", "item-008"]
        tools = ["browser-driver", "gherkin-runner"]
        """
      And it exits 0

    Scenario: A result that names no tool is refused
      Given the suite writes item-003's result with no label tool
      When I run harness verify 03
      Then stdout holds [finding] with rule = "result-tool-named", run = 1 and item = "item-003"
      And it exits 1

    Scenario: A monitor's result is never read by the gate
      Given impl/results holds, before the run, a result labelled item = item-003, tool = "metrics-query", status passed
      When I run harness verify 03
      Then the folder is emptied before the first run and that result is not among run-1's
      And cycles/03/results/ holds nothing a run did not write

    Scenario: A result that is not passed refuses the run, naming the item and its status
      Given the suite's second run writes item-008 with status failed
      When I run harness verify 03
      Then stdout is exactly
        """
        verify = "refused"
        cycle = "03"

        [finding]
        rule = "suite-green-twice"
        run = 2
        item = "item-008"
        status = "failed"
        """
      And it exits 1
      And no cycles/03/verified.toml exists
      And cycles/03/results/run-2/ holds the failed result and its attachments, for the reviewer

    Scenario Outline: Any status but passed is not a pass
      Given the suite writes item-007 with status <status>
      When I run harness verify 03
      Then stdout holds [finding] with rule = "suite-green-twice", item = "item-007" and status = "<status>"
      And it exits 1

      Examples:
        | status  |
        | broken  |
        | skipped |
        | unknown |

    Scenario: Two results for one item in one run must both be passed
      Given the suite writes item-003 twice in one run, once passed and once failed
      When I run harness verify 03
      Then stdout holds [finding] with rule = "suite-green-twice", item = "item-003" and status = "failed"
      And it exits 1

    Scenario: A result's other labels travel with it and are not read
      Given item-003's result also carries the labels framework = "cucumber-js", language = "javascript" and host = "ci-7"
      When I run harness verify 03
      Then stdout holds verify = "passed"
      And cycles/03/results/run-1/ holds the result with every label it was written with

    Scenario: A suite that wrote no result is a refusal, never a pass
      Given impl/test.sh exits 0 having written nothing to impl/results
      When I run harness verify 03
      Then stdout is exactly
        """
        verify = "refused"
        cycle = "03"

        [finding]
        rule = "results-written"
        run = 1
        results = 0
        """
      And it exits 1

    Scenario: A result that names no item is refused
      Given the suite writes a result with no label item
      When I run harness verify 03
      Then stdout is exactly
        """
        verify = "refused"
        cycle = "03"

        [finding]
        rule = "result-labelled"
        run = 1
        result = "impl/results/7c1e-result.json"
        """
      And it exits 1

    Scenario: A result naming an item off the checklist is refused
      Given the suite writes a result labelled item = item-099
      When I run harness verify 03
      Then stdout holds [finding] with rule = "result-item-on-checklist", run = 1 and item = "item-099"
      And it exits 1

    Scenario: An item with no result is refused, unless it is wip
      Given the checklist holds item-003, item-007 and item-008, and the suite writes results for item-003 and item-007 only
      When I run harness verify 03
      Then stdout is exactly
        """
        verify = "refused"
        cycle = "03"

        [finding]
        rule = "every-item-has-result"
        run = 1
        items = ["item-008"]
        """
      And it exits 1

    Scenario: A wip item needs no result
      Given item-008 is tagged @wip and the suite writes results for item-003 and item-007
      When I run harness verify 03
      Then stdout holds verify = "passed" and each run's items = ["item-003", "item-007"]
      And it exits 0

    Scenario: The runner's exit code is never read
      Given the suite writes three passed results and impl/test.sh then exits 3
      When I run harness verify 03
      Then stdout holds verify = "passed"
      And it exits 0

    Scenario: A suite that has not answered when the budget runs out is refused
      Given the suite takes 70 seconds and the budget is 60
      When I run harness verify 03
      Then stdout is exactly
        """
        verify = "refused"
        cycle = "03"

        [finding]
        rule = "inside-budget"
        run = 1
        budget-seconds = 60
        """
      And it exits 1

    Scenario: Verify runs only in a proved room
      Given cycles/03/room fails the fence's proof
      When I run harness verify 03
      Then stdout holds [finding] with rule = "room-proved"
      And it exits 1

    @nondeterministic
    Scenario: A verified build is the room's tree, and a changed room is not scored
      Given cycles/03/verified.toml names tree = "<the verified tree>" and the room's tree is now <another tree>
      When I run harness score 03 verdicts.toml
      Then stdout is exactly
        """
        score = "refused"
        cycle = "03"

        [finding]
        rule = "verified-tree"
        verified = "<the verified tree>"
        room = "<another tree>"
        """
      And it exits 1

  # ------------------------------------------------------------------
  # The instrument: INV-6
  # ------------------------------------------------------------------

  Rule: Score files verdicts against the checklist and refuses a cycle verify did not ratify

    Scenario: A complete set of verdicts is filed
      Given cycles/03/checklist.toml holds item-003, item-007 and item-008
      And verdicts.toml
        """
        [[verdicts]]
        id = "item-003"
        verdict = "pass"
        evidence = "booked Tuesday 17:00, dressing room 8"

        [[verdicts]]
        id = "item-007"
        verdict = "pass"

        [[verdicts]]
        id = "item-008"
        verdict = "fail"
        evidence = "second slot refused with room remaining"
        """
      When I run harness score 03 verdicts.toml
      Then cycles/03/score.toml is written with the same content as stdout
      And stdout is exactly
        """
        score = "passed"
        cycle = "03"
        scored = 3
        passed = 2
        failed = 1
        unreached = 0

        [[items]]
        id = "item-003"
        verdict = "pass"
        evidence = "booked Tuesday 17:00, dressing room 8"

        [[items]]
        id = "item-007"
        verdict = "pass"
        evidence = ""

        [[items]]
        id = "item-008"
        verdict = "fail"
        evidence = "second slot refused with room remaining"
        """
      And it exits 0

    Scenario: An item nobody reached is a fail with the reason, never dropped
      Given verdicts.toml holds item-003 and item-007 only
      When I run harness score 03 verdicts.toml
      Then stdout holds scored = 3, passed = 2, failed = 1 and unreached = 1
      And the item item-008 reads verdict = "fail" and evidence = "unreached: no verdict was given"

    Scenario: Zero verdicts means the grading did not happen
      Given verdicts.toml holds verdicts = []
      When I run harness score 03 verdicts.toml
      Then stdout is exactly
        """
        score = "refused"
        cycle = "03"

        [finding]
        rule = "graded"
        verdicts = 0
        """
      And it exits 1
      And no cycles/03/score.toml exists

    Scenario: A verdict for an id not on the checklist is fatal
      Given verdicts.toml holds a verdict for item-099
      When I run harness score 03 verdicts.toml
      Then stdout is exactly
        """
        score = "refused"
        cycle = "03"

        [finding]
        rule = "id-on-checklist"
        id = "item-099"
        """
      And it exits 1

    Scenario: A verdict that is neither pass nor fail is fatal
      Given verdicts.toml holds item-003 with verdict = "partial"
      When I run harness score 03 verdicts.toml
      Then stdout is exactly
        """
        score = "refused"
        cycle = "03"

        [finding]
        rule = "verdict-pass-or-fail"
        id = "item-003"
        verdict = "partial"
        """
      And it exits 1

    Scenario: Duplicate verdicts for one id keep the last

    Scenario: A cycle verify did not ratify cannot be scored
      Given no cycles/03/verified.toml
      When I run harness score 03 verdicts.toml
      Then stdout is exactly
        """
        score = "refused"
        cycle = "03"

        [finding]
        rule = "verified"
        """
      And it exits 1

    Scenario: Several graders' verdicts are scored apart and agreed by majority
      Given verdicts from graders a, b and c where item-008 is pass by a and fail by b and c
      When I run harness score 03 a.toml b.toml c.toml
      Then stdout holds graders = 3
      And the item item-008 reads verdict = "fail", evidence = "" and [items.by] with a = "pass", b = "fail", c = "fail"

  Rule: The scoreboard shows both directions on the shared items, never a net

    Scenario: A baseline cycle, with nothing to compare against
      Given cycles/01/score.toml holds 3 scored, 2 passed, and no earlier cycle has a score
      When I run harness scoreboard 01
      Then stdout is exactly
        """
        scoreboard = "passed"
        cycle = "01"
        scored = 3
        passed = 2
        rate = 66.7
        whole-container-due = "05"
        """
      And it exits 0

    Scenario: The full comparison against the previous cycle
      Given cycles/02/score.toml holds item-003 pass, item-007 fail, item-008 pass, item-010 pass
      And cycles/01/score.toml holds item-003 pass, item-007 pass, item-008 fail
      When I run harness scoreboard 02
      Then stdout is exactly
        """
        scoreboard = "passed"
        cycle = "02"
        scored = 4
        passed = 3
        rate = 75.0
        denominator-moved = true
        whole-container-due = "05"

        [previous]
        cycle = "01"
        scored = 3
        passed = 2
        rate = 66.7

        [shared]
        items = 3
        better = ["item-008"]
        worse = ["item-007"]
        same = 1

        [new]
        items = 1
        passed = 1

        [dropped]
        items = 0
        """
      And it exits 0

    Scenario: A percentage exactly on a half rounds to the even digit

    Scenario: The cycle to compare against is the numeric predecessor with a score, skipping cycles without one

    Scenario: A cycle with no score cannot be reported
      Given no cycles/03/score.toml
      When I run harness scoreboard 03
      Then stdout is exactly
        """
        scoreboard = "refused"

        [finding]
        rule = "scored"
        cycle = "03"
        """
      And it exits 1

  Rule: The spec owner's decisions are a person's (INV-10)

    Scenario: A cycle record names who built, who graded and who accepted
      Given cycles/03/cycle.toml
        """
        cut = "spec-3"
        from = "spec-2"

        [builder]
        name = "one builder"
        actor = "agent"

        [[graders]]
        name = "a"
        actor = "agent"

        [[graders]]
        name = "b"
        actor = "human"

        [spec-owner]
        name = "Sathish Narayanan"
        actor = "human"
        """
      When I run harness score 03 verdicts.toml
      Then it exits 0

    Scenario: A record whose spec owner is an agent is refused
      Given cycles/03/cycle.toml whose [spec-owner] has actor = "agent"
      When I run harness score 03 verdicts.toml
      Then stdout is exactly
        """
        score = "refused"
        cycle = "03"

        [finding]
        rule = "spec-owner-human"
        """
      And it exits 1

    Scenario: A builder never grades its own build
      Given cycles/03/cycle.toml whose [builder] and one of [[graders]] share a name
      When I run harness score 03 verdicts.toml
      Then stdout is exactly
        """
        score = "refused"
        cycle = "03"

        [finding]
        rule = "grader-not-builder"
        name = "one builder"
        """
      And it exits 1

  # ------------------------------------------------------------------
  # The occasion: INV-9; the held-still list: INV-8
  # ------------------------------------------------------------------

  Rule: Check runs every judge in order and refuses on the first red; its silence is never a pass

    Scenario: Check passes a project and says which stages ran
      Given a project whose storm, model, spec and plugins all pass
      When I run harness check
      Then stdout is exactly
        """
        check = "passed"
        stages = ["config", "storm", "model", "stack-words", "spec", "harness-files", "plugins"]
        """
      And it exits 0

    Scenario: The first red stops check, naming the stage
      Given the storm passes and the model refuses with rule = "container-has-components"
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "model"

        [finding]
        rule = "container-has-components"
        container = "ledger"
        """
      And it exits 1

    Scenario: A judge that cannot run is not a pass
      Given the plugin fees names a command that does not exist
      When I run harness check
      Then stdout is exactly
        """
        check = "cannot-run"
        stage = "plugins"
        plugin = "fees"
        command = ["plugins/fees/check.sh"]
        """
      And it exits 2

    Scenario: Check installs itself as the pre-commit hook
      When I run harness check --install
      Then .git/hooks/pre-commit runs harness check
      And stdout is exactly
        """
        check = "installed"
        hook = ".git/hooks/pre-commit"
        """
      And it exits 0

  Rule: Every file under harness/ is declared shared, per-project or local

    Scenario: A file in no list is refused
      Given harness/notes.md exists and harness/files.toml lists it nowhere
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "harness-files"

        [finding]
        rule = "file-declared"
        path = "harness/notes.md"
        """
      And it exits 1

    Scenario: A per-project file that is missing is refused
      Given harness/files.toml declares per-project = ["harness/probe.sh"] and it does not exist
      When I run harness check
      Then stdout holds [finding] with rule = "file-present" and path = "harness/probe.sh"
      And it exits 1

    Scenario: A shared file that differs from a sibling is refused, and a sibling not on this machine is a loud skip
      Given harness/files.toml declares shared = ["harness/spec.feature"] and siblings = ["../other"]
      And ../other/harness/spec.feature differs
      When I run harness check
      Then stdout holds [finding] with rule = "shared-identical", path = "harness/spec.feature" and sibling = "../other"
      And it exits 1

  # ------------------------------------------------------------------
  # Pluggable, in one way: README, "pluggable"; INV-7
  # ------------------------------------------------------------------

  Rule: A plugin is a command declared in the configuration, answering check or verify

    Scenario: A plugin answers check with the same TOML and exit codes
      Given spec/config.toml holds
        """
        [[plugins]]
        name = "fees"
        answers = "check"
        command = ["plugins/fees/check.sh"]
        """
      And plugins/fees/check.sh prints a document with fees = "refused" and [finding] rule = "fee-sourced", policy = "BillOnBooking", and exits 1
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "plugins"
        plugin = "fees"

        [finding]
        rule = "fee-sourced"
        policy = "BillOnBooking"
        """
      And it exits 1

    Scenario: A plugin is run with the project's folder as its working directory and the configuration's path as its argument

    Scenario: A plugin answering verify runs after the suite and can refuse the build
      Given the plugin probe has answers = "verify" and exits 1 with [finding] rule = "is-this-app"
      When I run harness verify 03
      Then stdout holds plugin = "probe" and [finding] with rule = "is-this-app"
      And it exits 1
      And no cycles/03/verified.toml exists

    Scenario: A plugin that prints no TOML document is cannot-run, never a pass
      Given the plugin fees prints nothing and exits 0
      When I run harness check
      Then stdout is exactly
        """
        check = "cannot-run"
        stage = "plugins"
        plugin = "fees"

        [finding]
        rule = "plugin-answers"
        """
      And it exits 2

  Rule: An opinion is a refusal, not a flag (INV-7)

    Scenario: An unknown configuration key is refused
      Given spec/config.toml holds the key contexts-relate-by
      When I run harness check
      Then stdout is exactly
        """
        check = "refused"
        stage = "config"

        [finding]
        rule = "config-shape"
        key = "contexts-relate-by"
        """
      And it exits 1

    Scenario: A missing configuration key is refused the same way
      Given spec/config.toml lacks whole-container-every
      When I run harness check
      Then stdout holds [finding] with rule = "config-shape" and missing = "whole-container-every"
      And it exits 1
