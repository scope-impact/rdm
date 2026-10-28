(ns rdm-spec.kernel.bus
  "The in-process event bus: delivers each of rdm-spec's own events to its
  subscribers, synchronously and in subscription order, within one run
  (dhf/spec/08-plugins/bus.feature). It carries rdm-spec's own catalogue, built
  in, whatever a project's catalogue declares, and refuses an event it does
  not declare, one missing a key it carries, or one from the wrong publisher:
  a defect in rdm-spec, never a finding about the project (exit 2)."
  (:require [rdm-spec.kernel.project :as project]))

(def catalogue
  "rdm-spec's own events, from its own spec's events.edn: publisher and carried keys."
  {:KnowledgeIndexed {:published-by :knowledge/sources :carries [:revision :sources :pages :citations :findings]}
   :SpecParsed {:published-by :spec/parser :carries [:revision :scenarios :needs :refusals]}
   :SpecGated {:published-by :spec/check :carries [:revision :passed? :findings]}
   :CycleVerified {:published-by :verification/verify :carries [:cycle :cut :checkout :checks]}
   :VerificationDerived {:published-by :verification/status :carries [:revision :statuses :unknown-results :unreadable-files]}
   :ClausesIndexed {:published-by :compliance/gap :carries [:revision :clauses :documents :findings]}})

(def ^:dynamic *published* nil)
(def ^:dynamic *subscribers* nil)

(defn new-run [] {:published (atom []) :subscribers (atom [])})

(defmacro with-bus
  "Runs body with a fresh bus: nothing published, nobody subscribed."
  [& body]
  `(binding [*published* (atom []) *subscribers* (atom [])] ~@body))

(defn subscribe!
  "Subscribes f to the event named by kw, after every earlier subscriber."
  [kw f]
  (when *subscribers* (swap! *subscribers* conj [kw f])))

(defn publish!
  "Publishes an event; returns the payload. The payload is the event's carried keys."
  [kw publisher payload]
  (let [decl (catalogue kw)]
    (when-not decl
      (project/cannot-run! (str "rdm-spec publishes no event " (name kw) ": a defect in rdm-spec")))
    (when-not (= publisher (:published-by decl))
      (project/cannot-run! (str "the event " (name kw) " is published by " (subs (str (:published-by decl)) 1)
                                ", not " (subs (str publisher) 1) ": a defect in rdm-spec")))
    (when-let [missing (seq (remove #(contains? payload %) (:carries decl)))]
      (project/cannot-run! (str "the event " (name kw) " lacks " (pr-str (vec missing)) ": a defect in rdm-spec")))
    (let [payload (select-keys payload (:carries decl))]
      (when *published* (swap! *published* conj {:event kw :publisher publisher :payload payload}))
      (when *subscribers*
        (doseq [[k f] @*subscribers* :when (= k kw)] (f payload)))
      payload)))

(defn published [] (if *published* @*published* []))

(defn event-line
  "The --events line of a published event."
  [{:keys [event publisher]}]
  (str "event: " (name event) " published-by " (namespace publisher) "/" (name publisher)))
