(ns steps.architecture-domain
  "Step definitions of dhf/spec/architecture/domain.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.architecture-model :as am]))

(def c "loyly:README.md@17d68d2")

(def one-context
  {:contexts {:booking {:aggregates {:standing-slot {:commands {:RequestStandingSlot {:by :resident :emits [:SlotConfirmed] :refuses [:closed-day :held] :sources [c]}}
                                                     :invariants {:one-holder {:text "A slot is held by at most one household" :verified-by ["spec-0003"] :sources [c]}}
                                                     :reads [:open-days]
                                                     :sources [c]}}
                        :read-models {:open-days {:fed-by [:DaysConfigured] :sources [c]}}
                        :policies {:charge {:when :SlotConfirmed :then "raise an invoicing signal" :sources [c]}}
                        :repositories {:booking-record {:stores [:standing-slot]}}}}
   :events {:SlotConfirmed {:sources [c]}}})

(def two-contexts
  (-> one-context
      (assoc-in [:contexts :schedule]
                {:aggregates {:calendar {:commands {:ConfigureDays {:by :manager :emits [:DaysConfigured] :sources [c]}}
                                         :invariants {:no-overlap {:text "Days never overlap" :verified-by ["spec-0004"] :sources [c]}}
                                         :sources [c]}}
                 :read-models {}})
      (assoc-in [:events :DaysConfigured] {:sources [c]})))

(def path-ss [:contexts :booking :aggregates :standing-slot])

(def variants
  {"one context with one aggregate" one-context
   "two contexts, each command emitting a catalogued event, each invariant verified by a stamped scenario, each read model fed by events, and every element sourced" two-contexts
   "an aggregate with no :sources" (update-in one-context path-ss dissoc :sources)
   "an invariant with no :sources" (update-in one-context (conj path-ss :invariants :one-holder) dissoc :sources)
   "a command citing loyly:raw/x.md@0000000, a pin dhf/raw/sources.edn lacks" (assoc-in one-context (conj path-ss :commands :RequestStandingSlot :sources) ["loyly:raw/x.md@0000000"])
   "the command RequestStandingSlot in two aggregates" (assoc-in one-context [:contexts :booking :aggregates :season-slot]
                                                                 {:commands {:RequestStandingSlot {:emits [:SlotConfirmed] :sources [c]}} :sources [c]})
   "a command that emits no event and names no refusal" (update-in one-context (conj path-ss :commands :RequestStandingSlot) dissoc :emits :refuses)
   "an event in :events that no command emits" (assoc-in one-context [:events :DaysConfigured] {:sources [c]})
   "a read model fed by :RequestStandingSlot, a command" (assoc-in one-context [:contexts :booking :read-models :open-days :fed-by] [:RequestStandingSlot])
   "an invariant whose :verified-by is empty" (assoc-in one-context (conj path-ss :invariants :one-holder :verified-by) [])
   "an invariant verified by spec-9999, which no stamped scenario carries" (assoc-in one-context (conj path-ss :invariants :one-holder :verified-by) ["spec-9999"])
   "an aggregate that :reads an aggregate of another context" (-> two-contexts (update-in (conj path-ss :reads) conj :calendar))
   "an event a command emits that the event catalogue does not declare" (update-in one-context (conj path-ss :commands :RequestStandingSlot :emits) conj :SlotWithdrawn)})

(def names
  {"an aggregate with no :sources" [":standing-slot"]
   "an invariant with no :sources" [":one-holder"]
   "a command citing loyly:raw/x.md@0000000, a pin dhf/raw/sources.edn lacks" [":RequestStandingSlot"]
   "the command RequestStandingSlot in two aggregates" [":RequestStandingSlot" ":standing-slot" ":season-slot"]
   "a command that emits no event and names no refusal" [":RequestStandingSlot"]
   "an event in :events that no command emits" [":DaysConfigured"]
   "a read model fed by :RequestStandingSlot, a command" [":open-days"]
   "an invariant whose :verified-by is empty" [":one-holder"]
   "an invariant verified by spec-9999, which no stamped scenario carries" [":one-holder" "spec-9999"]
   "an aggregate that :reads an aggregate of another context" [":standing-slot" ":calendar"]
   "an event a command emits that the event catalogue does not declare" [":SlotWithdrawn"]})

(def events
  {:SlotConfirmed {:description "A slot was confirmed" :published-by :booking/slots :carries [:slot] :shape {:slot "the slot"}}
   :DaysConfigured {:description "Open days were set" :published-by :schedule/days :carries [:days] :shape {:days "the days"}}})

(defn domain-project! [ctx]
  (if (:dir ctx)
    ctx
    (am/arch-project! ctx (k/model {:contexts [["booking" ["slots"]] ["schedule" ["days"]]]})
                      {:extra {"dhf/spec/events.edn" (k/pr-edn events)
                               "dhf/raw/sources.edn" (k/pr-edn {:demo {:repository "https://example.org/demo" :record "demo.md" :pins ["abc1234"] :fenced []}
                                                                :loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"] :fenced []}})
                               "dhf/raw/loyly.md" "# Source: loyly\n"
                               "dhf/spec/booking/slots.feature" (k/feature-text {:name "Slots" :tags ["UN-001"] :scenarios [(k/sc 3 "One holder")]})
                               "dhf/spec/schedule/days.feature" (k/feature-text {:name "Days" :tags ["UN-001"] :scenarios [(k/sc 4 "No overlap")]})}})))

(defn domain! [ctx d] (k/write-files! (:dir ctx) {"dhf/spec/domain.edn" (k/pr-edn d)}) ctx)

(defstep #"a project set up by rdm-spec with no dhf/spec/domain.edn"
  (fn [ctx] (k/init-project! ctx)))

(defn domain-rules [ctx] (filter #(str/starts-with? % "domain-") (k/finding-rules ctx)))

(defstep #"it exits 0 and names no finding about a domain model"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (str/includes? (:err ctx) "domain")) (:err ctx))
    ctx))

(defstep #"a project whose dhf/spec/domain.edn holds (.+)"
  (fn [ctx what]
    (let [d (or (variants what) (fail! (str "no such domain model: " what)))]
      (-> (domain-project! ctx) (domain! d) (assoc ::names (names what))))))

(defstep #"the file is read as exactly this shape, and a key it does not declare is a finding domain-key-undeclared:"
  (fn [ctx doc]
    (assert (str/includes? doc ":aggregates"))
    (k/check-exit ctx 0)
    (assert (empty? (domain-rules ctx)) (:err ctx))
    (domain! ctx (assoc-in one-context (conj path-ss :owner) "Sami"))
    (let [r (k/rdm ctx ["check"])]
      (k/check-exit r 1)
      (k/err-line-with r "domain-key-undeclared" ":owner"))
    ctx))

(defstep #"a context's id is a bounded context of model.edn, and every event is declared in the event catalogue"
  (fn [ctx]
    (domain! ctx (-> one-context (assoc-in [:contexts :billing] {:aggregates {}})
                     (assoc-in [:events :Unknown] {:sources [c]})))
    (let [r (k/rdm ctx ["check"])]
      (k/check-exit r 1)
      (k/err-line-with r "domain-" ":billing")
      (k/err-line-with r "domain-event-uncatalogued" ":Unknown"))
    ctx))

(defstep #"it exits 1 with the finding (\S+), naming (.+)"
  (fn [ctx rule _]
    (k/check-exit ctx 1)
    (apply k/err-line-with ctx rule (::names ctx))))

(defstep #"it names no finding about the domain model"
  (fn [ctx]
    (assert (empty? (domain-rules ctx)) (:err ctx))
    (k/check-exit ctx 0)))
