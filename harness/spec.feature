# The harness's own contract (docs/wow/invariants.md, INV-3). Every rule
# cites README.md or an invariant. Every When runs the command; every Then
# pins what it prints and how it exits. Nothing a scenario does not state is
# a contract.
#
# Every task prints exactly one EDN map on stdout, keys in the order shown,
# and exits 0 (passed), 1 (refused: a finding about the project) or 2
# (cannot run: the harness's own defect or a missing input). Nothing else is
# written to stdout; stderr is free.

Feature: The harness: storm, model, cut, fence, regenerate, gate, measure

  Background:
    Given a project folder with the configuration spec/config.edn
      """
      {:spec "spec" :storm "spec/storm.edn" :model "spec/model.edn"
       :raw "raw" :implementation ["impl"] :cycles "cycles"
       :whole-container-every 5 :plugins []}
      """

  # ------------------------------------------------------------------
  # The front of the chain: README, "the storm is forced"; INV-1
  # ------------------------------------------------------------------

  Rule: A project keeps its storm as data, and every element cites a source

    Scenario: The shape of a storm
      Given spec/storm.edn
        """
        {:actors      {"resident" {:text "lives in the building" :sources ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]}}
         :commands    {"BookSlot" {:actor "resident" :aggregate "Slot" :sources ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]}}
         :events      {"SlotBooked" {:command "BookSlot" :carries [:date :resource :start] :sources ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]}}
         :policies    {"BillOnBooking" {:when "SlotBooked" :then "RecordCharge" :sources ["loyly:raw/domain/public/yle-sauna-fees-2022.md@17d68d2"]}}
         :aggregates  {"Slot" {:sources ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]}}
         :read-models {"WeeklySchedule" {:fed-by ["SlotBooked"] :sources ["loyly:raw/domain/elicitation-2026-08-26.md@17d68d2"]}}
         :hotspots    {}}
        """
      And raw/loyly/raw/domain/elicitation-2026-08-26.md and raw/loyly/raw/domain/public/yle-sauna-fees-2022.md exist
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :passed :actors 1 :commands 1 :events 1 :policies 1 :aggregates 1 :read-models 1 :hotspots {:open 0 :decided 0}}
        """
      And it exits 0

    Scenario: An element with no source is refused, naming it
      Given spec/storm.edn whose event "SlotBooked" has :sources []
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :refused :finding {:rule :element-sourced :element [:events "SlotBooked"]}}
        """
      And it exits 1

    Scenario: A source that is not under raw/ is refused
      Given spec/storm.edn whose actor "resident" cites "https://example.org/sauna"
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :refused :finding {:rule :source-is-raw :element [:actors "resident"] :source "https://example.org/sauna"}}
        """
      And it exits 1

    Scenario: A source whose file is not in raw/ is refused
      Given spec/storm.edn whose aggregate "Slot" cites "loyly:raw/domain/missing.md@17d68d2"
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :refused :finding {:rule :source-exists :element [:aggregates "Slot"] :path "raw/loyly/raw/domain/missing.md"}}
        """
      And it exits 1

    Scenario: A command whose actor or aggregate the storm does not name is refused
      Given spec/storm.edn whose command "BookSlot" names the aggregate "Booking", which :aggregates does not hold
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :refused :finding {:rule :command-aggregate-named :element [:commands "BookSlot"] :aggregate "Booking"}}
        """
      And it exits 1

    Scenario: An event with no command is refused
      Given spec/storm.edn whose event "SlotBooked" has no :command
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :refused :finding {:rule :event-has-command :element [:events "SlotBooked"]}}
        """
      And it exits 1

    Scenario: A read model fed by an unknown event is refused
      Given spec/storm.edn whose read model "WeeklySchedule" is :fed-by ["SlotCancelled"], which :events does not hold
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :refused :finding {:rule :read-model-fed-by-event :element [:read-models "WeeklySchedule"] :event "SlotCancelled"}}
        """
      And it exits 1

    Scenario: The first finding stops the run
      Given spec/storm.edn with an unsourced actor and an unsourced event
      When I run harness storm
      Then stdout holds one :finding, the actor's
      And it exits 1

  Rule: No storm, no cut (INV-1)

    Scenario: A project with no storm is refused by check
      Given no spec/storm.edn
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :storm :finding {:rule :storm-exists :path "spec/storm.edn"}}
        """
      And it exits 1

    Scenario: An open hotspot refuses a cut
      Given spec/storm.edn whose :hotspots hold {"H1" {:text "who may book a second slot" :status :open}}
      When I run harness cut spec-1
      Then stdout is exactly
        """
        {:cut :refused :stage :storm :finding {:rule :hotspots-decided :hotspot "H1"}}
        """
      And it exits 1
      And no tag spec-1 exists

    Scenario: A decided hotspot carries its decision and a source
      Given spec/storm.edn whose :hotspots hold {"H1" {:text "who may book a second slot" :status :decided :decision "when there is room" :sources ["loyly:docs/wiki/pages/actors-and-fairness.md@17d68d2"]}}
      When I run harness storm
      Then stdout holds :hotspots {:open 0 :decided 1}
      And it exits 0

    Scenario: A decided hotspot with no decision text is refused
      Given spec/storm.edn whose hotspot "H1" has :status :decided and no :decision
      When I run harness storm
      Then stdout is exactly
        """
        {:storm :refused :finding {:rule :decision-stated :hotspot "H1"}}
        """
      And it exits 1

  Rule: The model is derived from the storm, never drawn (INV-1)

    Scenario: The shape of a model
      Given spec/model.edn
        """
        {:containers {"booking" {:runs true}}
         :components {"booking/slots"    {:container "booking" :context "booking" :owns ["Slot" "WeeklySchedule"] :spec "spec/slots.feature"}
                      "booking/billing"  {:container "booking" :context "billing" :owns ["Charge"] :spec "spec/billing.feature"}}
         :relationships [{:from "booking/slots" :to "booking/billing" :event "SlotBooked"}]}
        """
      And a storm holding the aggregates Slot and Charge, the read model WeeklySchedule and the event SlotBooked
      When I run harness model
      Then stdout is exactly
        """
        {:model :passed :containers 1 :components 2 :relationships 1}
        """
      And it exits 0

    Scenario: A component that owns nothing of the storm is refused
      Given spec/model.edn whose component "booking/audit" has :owns []
      When I run harness model
      Then stdout is exactly
        """
        {:model :refused :finding {:rule :component-owns-storm-element :component "booking/audit"}}
        """
      And it exits 1

    Scenario: A component that owns something the storm does not name is refused
      Given spec/model.edn whose component "booking/slots" owns "Waitlist", which the storm does not hold
      When I run harness model
      Then stdout is exactly
        """
        {:model :refused :finding {:rule :owned-element-in-storm :component "booking/slots" :element "Waitlist"}}
        """
      And it exits 1

    Scenario: An aggregate owned by two components is refused
      Given spec/model.edn where "booking/slots" and "booking/billing" both own "Slot"
      When I run harness model
      Then stdout is exactly
        """
        {:model :refused :finding {:rule :element-owned-once :element "Slot" :components ["booking/billing" "booking/slots"]}}
        """
      And it exits 1

    Scenario: A component in no container is refused
      Given spec/model.edn whose component "booking/slots" names the container "api", which :containers does not hold
      When I run harness model
      Then stdout is exactly
        """
        {:model :refused :finding {:rule :component-in-container :component "booking/slots" :container "api"}}
        """
      And it exits 1

    Scenario: Components of different contexts relate only through a storm event
      Given spec/model.edn whose relationship from "booking/slots" to "booking/billing" names no :event
      When I run harness model
      Then stdout is exactly
        """
        {:model :refused :finding {:rule :contexts-relate-by-event :from "booking/slots" :to "booking/billing"}}
        """
      And it exits 1

    Scenario: A relationship naming an event the storm does not hold is refused
      Given spec/model.edn whose relationship names the event "SlotCancelled", which the storm does not hold
      When I run harness model
      Then stdout is exactly
        """
        {:model :refused :finding {:rule :event-in-storm :from "booking/slots" :to "booking/billing" :event "SlotCancelled"}}
        """
      And it exits 1

    Scenario: An event the storm holds that no relationship carries is refused
      Given a storm holding the event "SlotCancelled" and a model whose relationships never name it
      When I run harness model
      Then stdout is exactly
        """
        {:model :refused :finding {:rule :event-carried :event "SlotCancelled"}}
        """
      And it exits 1

  Rule: The spec never names the stack (INV-2)

    Scenario: A stack word in a feature file is refused, naming the line
      Given spec/slots.feature whose line 12 reads "Then the Postgres row is updated"
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :stack-words :finding {:rule :no-stack-words :path "spec/slots.feature" :line 12 :word "Postgres"}}
        """
      And it exits 1

    Scenario: A stack word in the storm is refused the same way
      Given spec/storm.edn whose policy "BillOnBooking" has :then "write a Kafka message"
      When I run harness check
      Then stdout holds :finding {:rule :no-stack-words :path "spec/storm.edn" :line 5 :word "Kafka"}
      And it exits 1

    Scenario: raw/ is exempt
      Given raw/loyly/README.md names Babashka
      When I run harness check
      Then stdout holds no :no-stack-words finding

  # ------------------------------------------------------------------
  # The spec and its ids: INV-6
  # ------------------------------------------------------------------

  Rule: Every scenario belongs to one component, and every component owns at least one

    Scenario: A feature file the model does not name is refused
      Given spec/waitlist.feature, which no component's :spec names
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :spec :finding {:rule :feature-owned-by-component :path "spec/waitlist.feature"}}
        """
      And it exits 1

    Scenario: A component whose feature file has no scenario is refused
      Given spec/billing.feature holding a Feature line and nothing else
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :spec :finding {:rule :component-has-scenarios :component "booking/billing" :path "spec/billing.feature"}}
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
        {:stamp :passed :stamped 1 :files ["spec/slots.feature"] :next 9}
        """
      And it exits 0

    Scenario: Stamping twice changes nothing
      Given every scenario is stamped
      When I run harness stamp
      Then no file changes
      And stdout is exactly
        """
        {:stamp :passed :stamped 0 :files [] :next 9}
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
        {:stamp :refused :finding {:rule :id-once :id "item-003" :paths ["spec/billing.feature" "spec/slots.feature"]}}
        """
      And it exits 1

  Rule: The checklist is derived, one item per stamped scenario, grouped by component

    Scenario: The shape of a checklist
      Given spec/slots.feature holds @item-003 "A slot is booked" under the Rule "Booking" at line 9, and @item-008 "A second slot needs room" under no Rule at line 20
      And spec/billing.feature holds @item-007 "A booking is charged" at line 6
      When I run harness checklist
      Then stdout is exactly
        """
        {:checklist :passed :items [{:id "item-007" :component "booking/billing" :rule nil :name "A booking is charged" :source "spec/billing.feature:6"}
                                    {:id "item-003" :component "booking/slots" :rule "Booking" :name "A slot is booked" :source "spec/slots.feature:9"}
                                    {:id "item-008" :component "booking/slots" :rule nil :name "A second slot needs room" :source "spec/slots.feature:20"}]}
        """
      And it exits 0

    Scenario: Items are ordered by component, then by file, then by position

    Scenario: An unstamped scenario stops the checklist rather than shrinking it
      Given spec/slots.feature has an untagged scenario at line 25
      When I run harness checklist
      Then stdout is exactly
        """
        {:checklist :refused :finding {:rule :every-scenario-stamped :path "spec/slots.feature" :line 25}}
        """
      And it exits 1

    Scenario: An empty spec stops the checklist rather than producing an empty one
      Given no feature file holds a scenario
      When I run harness checklist
      Then stdout is exactly
        """
        {:checklist :refused :finding {:rule :spec-not-empty}}
        """
      And it exits 1

    Scenario: A scenario tagged @wip is listed and marked, never dropped
      Given @item-008 is also tagged @wip
      When I run harness checklist
      Then its item carries :wip true

  # ------------------------------------------------------------------
  # The cut and its scope: INV-4
  # ------------------------------------------------------------------

  Rule: A cut is a tag on a merged spec that check passed

    Scenario: A cut tags the head of the default branch
      Given check passes and the working tree is clean on the default branch
      When I run harness cut spec-1
      Then the tag spec-1 points at HEAD
      And stdout is exactly
        """
        {:cut :passed :tag "spec-1" :commit "<HEAD's hash>" :items 3}
        """
      And it exits 0

    Scenario: A dirty tree is refused
      Given spec/slots.feature has uncommitted changes
      When I run harness cut spec-1
      Then stdout is exactly
        """
        {:cut :refused :finding {:rule :tree-clean :paths ["spec/slots.feature"]}}
        """
      And it exits 1

    Scenario: A cut whose check fails is refused with check's finding
      Given check refuses with {:rule :storm-exists :path "spec/storm.edn"}
      When I run harness cut spec-1
      Then stdout is exactly
        """
        {:cut :refused :stage :storm :finding {:rule :storm-exists :path "spec/storm.edn"}}
        """
      And it exits 1

    Scenario: A tag that exists is refused
      Given the tag spec-1 exists
      When I run harness cut spec-1
      Then stdout is exactly
        """
        {:cut :refused :finding {:rule :tag-free :tag "spec-1"}}
        """
      And it exits 1

  Rule: A scope is computed from two cuts, never typed

    Scenario: The components whose scenarios changed are in scope
      Given between spec-1 and spec-2 only spec/billing.feature changed
      When I run harness scopes spec-1 spec-2
      Then stdout is exactly
        """
        {:scopes :passed :from "spec-1" :to "spec-2" :changed ["booking/billing"] :reached [] :components ["booking/billing"] :containers ["booking"] :whole-container false}
        """
      And it exits 0

    Scenario: A change to an event reaches its publisher and every subscriber
      Given between spec-1 and spec-2 the storm's event SlotBooked gained a carried key
      When I run harness scopes spec-1 spec-2
      Then stdout holds :changed [] :reached ["booking/billing" "booking/slots"] :components ["booking/billing" "booking/slots"]

    Scenario: A change to the model or the storm that moves a component makes its container the scope
      Given between spec-1 and spec-2 the component "booking/billing" moved to the container "ledger"
      When I run harness scopes spec-1 spec-2
      Then stdout holds :whole-container true :containers ["booking" "ledger"]

    Scenario: Nothing changed is a refusal, not an empty scope
      Given spec-1 and spec-2 point at the same tree
      When I run harness scopes spec-1 spec-2
      Then stdout is exactly
        """
        {:scopes :refused :finding {:rule :cuts-differ :from "spec-1" :to "spec-2"}}
        """
      And it exits 1

    Scenario: A scope is never read from a file
      Given cycles/03/cycle.edn holds :components ["booking/slots"]
      When I run harness fence 03
      Then stdout is exactly
        """
        {:fence :refused :finding {:rule :scope-computed :path "cycles/03/cycle.edn" :key :components}}
        """
      And it exits 1

  Rule: Every Nth cycle is a whole-container cycle, and the scoreboard says when one is due

    Scenario: The fifth cycle since the last whole-container cycle is whole
      Given :whole-container-every 5 and cycles 01 to 04 since the last whole-container cycle
      When I run harness scopes spec-4 spec-5
      Then stdout holds :whole-container true and :components every component of the model

    Scenario: The scoreboard names the cycle a whole-container cycle is due
      When I run harness scoreboard 04
      Then stdout holds :whole-container-due "05"

  # ------------------------------------------------------------------
  # The clean room: README, "regenerate, not copy"
  # ------------------------------------------------------------------

  Rule: The fence is physical and proved

    Scenario: A room holds the cut and nothing of the scope's implementation or any measurement
      Given cycles/03/cycle.edn names the cut spec-3 and the previous cut spec-2
      And scopes spec-2 spec-3 answers :components ["booking/billing"]
      And impl/billing/ and impl/slots/ hold the previous build, and cycles/ the previous measurements
      When I run harness fence 03
      Then cycles/03/room/ is a clone of spec-3 whose history holds no commit touching impl/billing/ or cycles/
      And impl/slots/ is in the room, unchanged
      And stdout is exactly
        """
        {:fence :passed :cycle "03" :cut "spec-3" :room "cycles/03/room" :removed ["cycles" "impl/billing"] :kept ["impl/slots"] :tree "<the room's tree hash>"}
        """
      And it exits 0

    Scenario: A whole-container scope removes every implementation of the container
      Given scopes answers :whole-container true :containers ["booking"]
      When I run harness fence 04
      Then stdout holds :removed ["cycles" "impl/billing" "impl/slots"] :kept []

    Scenario: The proof fails if a removed path is reachable in the room's history
      Given a room whose history still reaches impl/billing/
      When I run harness fence --prove 03
      Then stdout is exactly
        """
        {:fence :refused :finding {:rule :room-proved :path "impl/billing" :commit "<the reachable commit>"}}
        """
      And it exits 1

    Scenario: A room that exists is not rebuilt silently
      Given cycles/03/room exists
      When I run harness fence 03
      Then stdout is exactly
        """
        {:fence :refused :finding {:rule :room-fresh :room "cycles/03/room"}}
        """
      And it exits 1

  Rule: One builder per cycle, in the room, reading the cut's plan first

    Scenario: The cycle record names one builder
      Given cycles/03/cycle.edn names two builders
      When I run harness verify 03
      Then stdout is exactly
        """
        {:verify :refused :finding {:rule :one-builder :cycle "03"}}
        """
      And it exits 1

  # ------------------------------------------------------------------
  # The gate: INV-5
  # ------------------------------------------------------------------

  Rule: "Done" is ratified by verify, which the builder does not control

    Scenario: A green suite, twice, inside the budget, is ratified
      Given cycles/03/room holds a build whose suite passes 3 scenarios in 4 seconds, and the configuration's :verify holds {:command ["impl/test.sh"] :budget-seconds 60}
      When I run harness verify 03
      Then cycles/03/verified.edn is written
      And stdout is exactly
        """
        {:verify :passed :cycle "03" :tree "<the room's tree hash>" :runs [{:scenarios 3 :passed 3 :seconds 4} {:scenarios 3 :passed 3 :seconds 4}] :budget-seconds 60 :margin-seconds 56}
        """
      And it exits 0

    Scenario: A suite that fails on either run is refused, naming the run
      Given the suite passes the first run and fails one scenario on the second
      When I run harness verify 03
      Then stdout is exactly
        """
        {:verify :refused :cycle "03" :finding {:rule :suite-green-twice :run 2 :scenarios 3 :passed 2}}
        """
      And it exits 1
      And no cycles/03/verified.edn exists

    Scenario: A suite that did not run is a refusal, never a pass
      Given impl/test.sh exits 0 having run no scenario
      When I run harness verify 03
      Then stdout is exactly
        """
        {:verify :refused :cycle "03" :finding {:rule :suite-ran :run 1 :scenarios 0}}
        """
      And it exits 1

    Scenario: A suite that has not answered when the budget runs out is refused
      Given the suite takes 70 seconds and the budget is 60
      When I run harness verify 03
      Then stdout is exactly
        """
        {:verify :refused :cycle "03" :finding {:rule :inside-budget :run 1 :budget-seconds 60}}
        """
      And it exits 1

    Scenario: Verify runs only in a proved room
      Given cycles/03/room fails the fence's proof
      When I run harness verify 03
      Then stdout holds :finding {:rule :room-proved ...}
      And it exits 1

    Scenario: A verified build is the room's tree, and a changed room is not verified
      Given cycles/03/verified.edn names the tree T and the room's tree is now U
      When I run harness score 03 verdicts.edn
      Then stdout is exactly
        """
        {:score :refused :cycle "03" :finding {:rule :verified-tree :verified "T" :room "U"}}
        """
      And it exits 1

  # ------------------------------------------------------------------
  # The instrument: INV-6
  # ------------------------------------------------------------------

  Rule: Score files verdicts against the checklist and refuses a cycle verify did not ratify

    Scenario: A complete set of verdicts is filed
      Given cycles/03/checklist.edn holds item-003, item-007 and item-008
      And verdicts.edn
        """
        [{:id "item-003" :verdict :pass :evidence "booked Tuesday 17:00, dressing room 8"}
         {:id "item-007" :verdict :pass}
         {:id "item-008" :verdict :fail :evidence "second slot refused with room remaining"}]
        """
      When I run harness score 03 verdicts.edn
      Then cycles/03/score.edn is written
      And stdout is exactly
        """
        {:score :passed :cycle "03" :scored 3 :passed 2 :failed 1 :unreached 0 :items [{:id "item-003" :verdict :pass :evidence "booked Tuesday 17:00, dressing room 8"} {:id "item-007" :verdict :pass :evidence ""} {:id "item-008" :verdict :fail :evidence "second slot refused with room remaining"}]}
        """
      And it exits 0

    Scenario: An item nobody reached is a fail with the reason, never dropped
      Given verdicts.edn holds item-003 and item-007 only
      When I run harness score 03 verdicts.edn
      Then stdout holds :scored 3 :passed 2 :failed 1 :unreached 1
      And the item item-008 reads {:id "item-008" :verdict :fail :evidence "unreached: no verdict was given"}

    Scenario: Zero verdicts means the grading did not happen
      Given verdicts.edn holds []
      When I run harness score 03 verdicts.edn
      Then stdout is exactly
        """
        {:score :refused :cycle "03" :finding {:rule :graded :verdicts 0}}
        """
      And it exits 1
      And no cycles/03/score.edn exists

    Scenario: A verdict for an id not on the checklist is fatal
      Given verdicts.edn holds {:id "item-099" :verdict :pass}
      When I run harness score 03 verdicts.edn
      Then stdout is exactly
        """
        {:score :refused :cycle "03" :finding {:rule :id-on-checklist :id "item-099"}}
        """
      And it exits 1

    Scenario: A verdict that is neither pass nor fail is fatal
      Given verdicts.edn holds {:id "item-003" :verdict :partial}
      When I run harness score 03 verdicts.edn
      Then stdout is exactly
        """
        {:score :refused :cycle "03" :finding {:rule :verdict-pass-or-fail :id "item-003" :verdict :partial}}
        """
      And it exits 1

    Scenario: Duplicate verdicts for one id keep the last

    Scenario: A cycle verify did not ratify cannot be scored
      Given no cycles/03/verified.edn
      When I run harness score 03 verdicts.edn
      Then stdout is exactly
        """
        {:score :refused :cycle "03" :finding {:rule :verified :cycle "03"}}
        """
      And it exits 1

    Scenario: Several graders' verdicts are scored apart and agreed by majority
      Given verdicts from graders a, b and c where item-008 is :pass by a and :fail by b and c
      When I run harness score 03 a.edn b.edn c.edn
      Then stdout holds :graders 3 and item-008 reads {:id "item-008" :verdict :fail :evidence "" :by {"a" :pass "b" :fail "c" :fail}}

  Rule: The scoreboard shows both directions on the shared items, never a net

    Scenario: A baseline cycle, with nothing to compare against
      Given cycles/01/score.edn holds 3 scored, 2 passed, and no earlier cycle has a score
      When I run harness scoreboard 01
      Then stdout is exactly
        """
        {:scoreboard :passed :cycle "01" :scored 3 :passed 2 :rate 66.7 :previous nil :whole-container-due "05"}
        """
      And it exits 0

    Scenario: The full comparison against the previous cycle
      Given cycles/02/score.edn holds item-003 :pass, item-007 :fail, item-008 :pass, item-010 :pass
      And cycles/01/score.edn holds item-003 :pass, item-007 :pass, item-008 :fail
      When I run harness scoreboard 02
      Then stdout is exactly
        """
        {:scoreboard :passed :cycle "02" :scored 4 :passed 3 :rate 75.0 :previous {:cycle "01" :scored 3 :passed 2 :rate 66.7} :shared {:items 3 :better ["item-008"] :worse ["item-007"] :same 1} :new {:items 1 :passed 1} :dropped {:items 0} :denominator-moved true :whole-container-due "05"}
        """
      And it exits 0

    Scenario: A percentage exactly on a half rounds to the even digit

    Scenario: The cycle to compare against is the numeric predecessor with a score, skipping cycles without one

    Scenario: A cycle with no score cannot be reported
      Given no cycles/03/score.edn
      When I run harness scoreboard 03
      Then stdout is exactly
        """
        {:scoreboard :refused :finding {:rule :scored :cycle "03"}}
        """
      And it exits 1

  Rule: The spec owner's decisions are a person's (INV-10)

    Scenario: A cycle record names who built, who graded and who accepted
      Given cycles/03/cycle.edn
        """
        {:cut "spec-3" :from "spec-2"
         :builder {:name "one builder" :actor :agent}
         :graders [{:name "a" :actor :agent} {:name "b" :actor :human}]
         :spec-owner {:name "Sathish Narayanan" :actor :human}}
        """
      When I run harness score 03 verdicts.edn
      Then it exits 0

    Scenario: A record whose spec owner is an agent is refused
      Given cycles/03/cycle.edn whose :spec-owner has :actor :agent
      When I run harness score 03 verdicts.edn
      Then stdout is exactly
        """
        {:score :refused :cycle "03" :finding {:rule :spec-owner-human :cycle "03"}}
        """
      And it exits 1

    Scenario: A builder never grades its own build
      Given cycles/03/cycle.edn whose :builder and one of :graders share a :name
      When I run harness score 03 verdicts.edn
      Then stdout is exactly
        """
        {:score :refused :cycle "03" :finding {:rule :grader-not-builder :name "one builder"}}
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
        {:check :passed :stages [:storm :model :stack-words :spec :harness-files :plugins]}
        """
      And it exits 0

    Scenario: The first red stops check, naming the stage
      Given the storm passes and the model refuses with {:rule :component-in-container :component "booking/slots" :container "api"}
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :model :finding {:rule :component-in-container :component "booking/slots" :container "api"}}
        """
      And it exits 1

    Scenario: A judge that cannot run is not a pass
      Given the plugin "fees" names a command that does not exist
      When I run harness check
      Then stdout is exactly
        """
        {:check :cannot-run :stage :plugins :plugin "fees" :command ["plugins/fees/check.sh"]}
        """
      And it exits 2

    Scenario: Check installs itself as the pre-commit hook
      When I run harness check --install
      Then .git/hooks/pre-commit runs harness check
      And stdout is exactly
        """
        {:check :installed :hook ".git/hooks/pre-commit"}
        """
      And it exits 0

  Rule: Every file under harness/ is declared shared, per-project or local

    Scenario: A file in no list is refused
      Given harness/notes.md exists and harness/files.edn lists it nowhere
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :harness-files :finding {:rule :file-declared :path "harness/notes.md"}}
        """
      And it exits 1

    Scenario: A per-project file that is missing is refused
      Given harness/files.edn declares harness/probe.sh :per-project and it does not exist
      When I run harness check
      Then stdout holds :finding {:rule :file-present :path "harness/probe.sh"}
      And it exits 1

    Scenario: A shared file that differs from a sibling is refused, and a sibling not on this machine is a loud skip
      Given harness/files.edn declares harness/spec.feature :shared and :siblings ["../other"]
      And ../other/harness/spec.feature differs
      When I run harness check
      Then stdout holds :finding {:rule :shared-identical :path "harness/spec.feature" :sibling "../other"}
      And it exits 1

  # ------------------------------------------------------------------
  # Pluggable, in one way: README, "pluggable"; INV-7
  # ------------------------------------------------------------------

  Rule: A plugin is a command declared in the configuration, answering check or verify

    Scenario: A plugin answers check with the same EDN and exit codes
      Given the configuration's :plugins hold [{:name "fees" :answers :check :command ["plugins/fees/check.sh"]}]
      And plugins/fees/check.sh prints {:fees :refused :finding {:rule :fee-sourced :policy "BillOnBooking"}} and exits 1
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :plugins :plugin "fees" :finding {:rule :fee-sourced :policy "BillOnBooking"}}
        """
      And it exits 1

    Scenario: A plugin is run with the project's folder as its working directory and the configuration's path as its argument

    Scenario: A plugin answering verify runs after the suite and can refuse the build
      Given the plugin "probe" :answers :verify and exits 1 with {:probe :refused :finding {:rule :is-this-app}}
      When I run harness verify 03
      Then stdout holds :finding {:rule :is-this-app} and :plugin "probe"
      And it exits 1
      And no cycles/03/verified.edn exists

    Scenario: A plugin that prints no EDN map is cannot-run, never a pass
      Given the plugin "fees" prints nothing and exits 0
      When I run harness check
      Then stdout is exactly
        """
        {:check :cannot-run :stage :plugins :plugin "fees" :finding {:rule :plugin-answers}}
        """
      And it exits 2

  Rule: An opinion is a refusal, not a flag (INV-7)

    Scenario: An unknown configuration key is refused
      Given spec/config.edn holds the key :contexts-relate-by
      When I run harness check
      Then stdout is exactly
        """
        {:check :refused :stage :config :finding {:rule :config-shape :key :contexts-relate-by}}
        """
      And it exits 1

    Scenario: A missing configuration key is refused the same way
      Given spec/config.edn lacks :whole-container-every
      When I run harness check
      Then stdout holds :finding {:rule :config-shape :missing :whole-container-every}
      And it exits 1
