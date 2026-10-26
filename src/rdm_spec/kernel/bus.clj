(ns rdm-spec.kernel.bus
  "The event bus (dhf/spec/kernel/bus.feature; dhf/spec/glossary.md, \"Event
  bus\"): rdm-spec's in-process bus, within one run, synchronous and in
  subscription order.

  It carries rdm-spec's own events, the ones between its bounded contexts,
  built in: `catalogue` is rdm-spec's own spec, dhf/spec/events.edn, as the
  release was built from it (each event's publisher and the keys it
  carries). A project's configured catalogue is that project's design
  record: the bus never reads it, so it never changes what a run publishes
  (spec-0303, spec-0304).

  It refuses (ex-info, `refused?`) an event the catalogue it was built with
  does not declare, one from another publisher than the declared one, and
  one missing a key it carries. Refusals are programming errors of
  rdm-spec, never findings about a project.

    (def b (bus))                        ; rdm-spec's own catalogue
    (subscribe! b :SpecParsed f)         ; f gets the payload
    (publish! b :spec/parser :SpecParsed payload)
    (published b)                        ; [{:event :publisher}] in order
    (event-line e)                       ; \"event: SpecParsed published-by spec/parser\"")

(def catalogue
  "rdm-spec's own events (dhf/spec/events.edn): {event {:published-by :carries}}."
  {:KnowledgeIndexed {:published-by :knowledge/sources
                      :carries [:revision :sources :pages :citations :findings]}
   :SpecParsed {:published-by :spec/parser
                :carries [:revision :scenarios :needs :refusals]}
   :SpecGated {:published-by :spec/check
               :carries [:revision :passed? :findings]}
   :VerificationDerived {:published-by :verification/status
                         :carries [:revision :statuses :unknown-results :unreadable-files]}
   :ClausesIndexed {:published-by :compliance/gap
                    :carries [:revision :clauses :documents :findings]}})

(defn refused?
  "Whether `e` is the bus's refusal."
  [e]
  (boolean (::refused (ex-data e))))

(defn- refuse! [msg]
  (throw (ex-info msg {::refused true})))

(defn bus
  "A new bus carrying the events of `cat` (rdm-spec's own `catalogue` when
  none is given)."
  ([] (bus catalogue))
  ([cat] (atom {:catalogue cat :subscriptions [] :published []})))

(defn- declared! [b event]
  (or (get-in @b [:catalogue event])
      (refuse! (str "the event " (name event) " is not one of rdm-spec's events, so the bus does not carry it"))))

(defn subscribe!
  "Subscribes `handler` (fn [payload]) to `event`; deliveries follow the
  order of subscription."
  [b event handler]
  (declared! b event)
  (swap! b update :subscriptions conj [event handler])
  nil)

(defn publish!
  "Publishes `event` with `payload` from `publisher`: delivers it to every
  subscriber, in order, before returning."
  [b publisher event payload]
  (let [{:keys [published-by carries]} (declared! b event)
        missing (remove #(contains? payload %) carries)]
    (when (not= published-by publisher)
      (refuse! (str "the event " (name event) " is published by " (subs (str published-by) 1)
                    ", not " (subs (str publisher) 1))))
    (when (seq missing)
      (refuse! (str "the event " (name event) " carries " (pr-str (vec missing)) ", which its payload lacks")))
    (swap! b update :published conj {:event event :publisher publisher})
    (doseq [[e h] (:subscriptions @b) :when (= e event)]
      (h payload))
    nil))

(defn published
  "Every event published on `b`, in order: [{:event :publisher}]."
  [b]
  (:published @b))

(defn event-line
  "The line a run prints for a published event, with --events."
  [{:keys [event publisher]}]
  (str "event: " (name event) " published-by " (subs (str publisher) 1)))
