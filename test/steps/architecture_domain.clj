(ns steps.architecture-domain
  "Step definitions for dhf/spec/architecture/domain.feature: `rdm-spec
  check` on a small project rdm-spec set up (steps.architecture-support)
  whose model has the bounded contexts booking (Slots, Rules) and billing
  (Invoice), whose event catalogue declares SlotConfirmed, DaysConfigured
  and InvoiceRaised, whose sources index records loyly pinned at 17d68d2,
  whose spec holds the stamped scenarios spec-0003 and spec-0004, and whose
  dhf/spec/domain.edn a step writes.

  Context keys set here: :dir the project; :domain the domain model
  written; :named the words the finding a Then looks for must hold."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def ^:private cite "loyly:README.md@17d68d2")

(def ^:private catalogue
  {:SlotConfirmed {:description "A slot was confirmed" :published-by :booking/slots :carries [:slot] :shape {:slot "id"}}
   :DaysConfigured {:description "Open days were set" :published-by :booking/rules :carries [:days] :shape {:days "[day]"}}
   :InvoiceRaised {:description "An invoice was raised" :published-by :billing/invoice :carries [:invoice] :shape {:invoice "id"}}})

(defn- feature [title-id]
  (str "@UN-001\nFeature: Slots\n  Holds the slots.\n"
       (str/join (for [[id title] title-id]
                   (str "\n  @" id " @e2e\n  Scenario: " title "\n    Given a slot\n    When it is booked\n    Then it is held\n")))))

(defn- domain-project!
  "ctx with :dir, the project described in the namespace doc, holding no
  domain.edn yet."
  [ctx]
  (let [dir (a/small-project! (dissoc ctx :dir) a/base-model)]
    (s/write! dir "dhf/spec/events.edn" (str (pr-str catalogue) "\n"))
    (s/write! dir "dhf/raw/sources.edn"
              (pr-str {:loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"]}}))
    (s/write! dir "dhf/raw/loyly.md" "# loyly\n\nA source.\n")
    (s/write! dir "dhf/spec/needs.edn" (pr-str {"UN-001" {:text "A user needs a slot." :sources [cite]}}))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 4}\n")
    (s/write! dir "dhf/spec/booking/slots.feature"
              (feature [["spec-0003" "A slot is held once"] ["spec-0004" "A closed day is refused"]]))
    (assoc ctx :dir dir)))

(def one-context
  "One context with one aggregate, every element sourced and sound."
  {:contexts
   {:booking
    {:aggregates {:standing-slot {:commands {:RequestStandingSlot {:by :resident :emits [:SlotConfirmed]
                                                                   :refuses [:closed-day :held] :sources [cite]}}
                                  :invariants {:one-holder {:text "A slot is held by at most one household"
                                                            :verified-by ["spec-0003"] :sources [cite]}}
                                  :reads [:open-days]
                                  :sources [cite]}}
     :read-models {:open-days {:fed-by [:DaysConfigured] :sources [cite]}}
     :policies {:charge {:when :SlotConfirmed :then "raise an invoicing signal" :sources [cite]}}
     :repositories {:booking-record {:stores [:standing-slot]}}}}
   :events {:SlotConfirmed {:sources [cite]}}})

(def two-contexts
  "Two contexts keeping every rule."
  (-> one-context
      (assoc-in [:contexts :booking :aggregates :standing-slot :commands :ConfigureDays]
                {:by :manager :emits [:DaysConfigured] :sources [cite]})
      (assoc-in [:contexts :billing]
                {:aggregates {:invoice {:commands {:RaiseInvoice {:by :policy :emits [:InvoiceRaised] :refuses [:no-slot]
                                                                  :sources [cite]}}
                                        :invariants {:paid-once {:text "An invoice is paid once" :verified-by ["spec-0004"]
                                                                 :sources [cite]}}
                                        :reads []
                                        :sources [cite]}}
                 :read-models {:ledger {:fed-by [:InvoiceRaised :SlotConfirmed] :sources [cite]}}
                 :policies {}
                 :repositories {:invoices {:stores [:invoice]}}})
      (assoc :events {:SlotConfirmed {:sources [cite]} :DaysConfigured {:sources [cite]} :InvoiceRaised {:sources [cite]}})))

(defn- write-domain! [ctx d]
  (s/write! (:dir ctx) "dhf/spec/domain.edn" (str ";; The domain model.\n" (pr-str d) "\n"))
  (assoc ctx :domain d))

(def ^:private agg [:contexts :booking :aggregates :standing-slot])

(def ^:private wrongs
  "Each <element> and <wrong> of the outlines: [the domain model, the words named]."
  {"an aggregate with no :sources"
   [(update-in one-context agg dissoc :sources) [":standing-slot"]]
   "an invariant with no :sources"
   [(update-in one-context (conj agg :invariants :one-holder) dissoc :sources) [":one-holder"]]
   "a command citing loyly:raw/x.md@0000000, a pin dhf/raw/sources.edn lacks"
   [(assoc-in one-context (conj agg :commands :RequestStandingSlot :sources) ["loyly:raw/x.md@0000000"])
    [":RequestStandingSlot" "loyly:raw/x.md@0000000"]]
   "the command RequestStandingSlot in two aggregates"
   [(assoc-in one-context [:contexts :booking :aggregates :slot-pool]
              {:commands {:RequestStandingSlot {:by :resident :emits [:SlotConfirmed] :sources [cite]}} :sources [cite]})
    [":RequestStandingSlot" ":standing-slot" ":slot-pool"]]
   "a command that emits no event and names no refusal"
   [(update-in one-context (conj agg :commands :RequestStandingSlot) assoc :emits [] :refuses []) [":RequestStandingSlot"]]
   "an event in :events that no command emits"
   [(assoc-in one-context [:events :DaysConfigured] {:sources [cite]}) [":DaysConfigured"]]
   "a read model fed by :RequestStandingSlot, a command"
   [(assoc-in one-context [:contexts :booking :read-models :open-days :fed-by] [:RequestStandingSlot]) [":open-days"]]
   "an invariant whose :verified-by is empty"
   [(assoc-in one-context (conj agg :invariants :one-holder :verified-by) []) [":one-holder"]]
   "an invariant verified by spec-9999, which no stamped scenario carries"
   [(assoc-in one-context (conj agg :invariants :one-holder :verified-by) ["spec-9999"]) [":one-holder" "spec-9999"]]
   "an aggregate that :reads an aggregate of another context"
   [(-> one-context
        (assoc-in [:contexts :billing] {:aggregates {:invoice {:commands {} :invariants {} :reads [] :sources [cite]}}})
        (assoc-in (conj agg :reads) [:invoice]))
    [":standing-slot" ":invoice"]]
   "an event a command emits that the event catalogue does not declare"
   [(-> one-context
        (assoc-in (conj agg :commands :RequestStandingSlot :emits) [:SlotConfirmed :SlotHeld])
        (assoc-in [:events :SlotHeld] {:sources [cite]}))
    [":SlotHeld"]]})

(defn- domain-lines [run] (filter #(re-find #": domain-[a-z-]+: " %) (s/err-lines run)))

(defn- finding-naming! [ctx rule named]
  (let [r (kc/exits! ctx 1)]
    (s/check! (some (fn [l] (and (str/includes? l (str ": " rule ": ")) (every? #(str/includes? l %) named)))
                    (kc/finding-lines r))
              (str "no " rule " finding names " (str/join ", " named) "\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------

(defstep #"a project set up by rdm-spec with no dhf/spec/domain\.edn"
  (fn [ctx]
    (let [ctx (kc/project-set-up! ctx)]
      (s/check! (not (.exists (java.io.File. (str (:dir ctx) "/dhf/spec/domain.edn")))) "the project has a domain.edn")
      ctx)))

(defstep #"it exits 0 and names no finding about a domain model"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (empty? (domain-lines r)) (str "the run names the domain model\n" (s/show r)))
      (s/check! (not (str/includes? (str (:err r)) "domain.edn")) (str "the run names domain.edn\n" (s/show r)))
      nil)))

(defstep #"a project whose dhf/spec/domain\.edn holds one context with one aggregate"
  (fn [ctx] (domain-project! ctx)))

(defstep #"the file is read as exactly this shape, and a key it does not declare is a finding domain-key-undeclared:"
  (fn [ctx doc]
    (let [shape (walk/postwalk #(if (= 'citation %) cite %) (edn/read-string doc))
          ctx (write-domain! ctx shape)
          clean (kc/run! ctx (:dir ctx) ["check"])]
      (s/check! (#{0 1} (:exit clean)) (str "the check did not run\n" (s/show clean)))
      (s/check! (empty? (domain-lines clean)) (str "the shape itself is a finding\n" (s/show clean)))
      (let [ctx (write-domain! ctx (assoc-in shape [:contexts :booking :aggregates :standing-slot :owner] :someone))
            r (kc/run! ctx (:dir ctx) ["check"])]
        (finding-naming! (assoc ctx :run r) "domain-key-undeclared" [":owner"])
        (write-domain! ctx shape)))))

(defstep #"a context's id is a bounded context of model\.edn, and every event is declared in the event catalogue"
  (fn [ctx]
    (let [shape (:domain ctx)
          c1 (write-domain! ctx (assoc-in shape [:contexts :lodging] (get-in shape [:contexts :booking])))
          r1 (kc/run! c1 (:dir c1) ["check"])
          _ (finding-naming! (assoc ctx :run r1) "domain-context-unknown" [":lodging"])
          c2 (write-domain! ctx (assoc-in shape [:events :SlotHeld] {:sources [cite]}))
          r2 (kc/run! c2 (:dir c2) ["check"])]
      (finding-naming! (assoc ctx :run r2) "domain-event-uncatalogued" [":SlotHeld"])
      nil)))

(defstep #"a project whose dhf/spec/domain\.edn holds two contexts, each command emitting a catalogued event, each invariant verified by a stamped scenario, each read model fed by events, and every element sourced"
  (fn [ctx] (write-domain! (domain-project! ctx) two-contexts)))

(defstep #"a project whose dhf/spec/domain\.edn holds (?!one context with one aggregate$)(?!two contexts, )(.+)"
  (fn [ctx what]
    (let [[d named] (or (get wrongs what) (s/fail! (str "no such domain model: " what)))]
      (assoc (write-domain! (domain-project! ctx) d) :named named))))

(defstep #"it exits 1 with the finding (domain-[a-z-]+), naming (?:.+)"
  (fn [ctx rule] (finding-naming! ctx rule (:named ctx)) nil))

(defstep #"it names no finding about the domain model"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (empty? (domain-lines r)) (str "the run names the domain model\n" (s/show r)))
      nil)))
