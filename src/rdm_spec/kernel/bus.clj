(ns rdm-spec.kernel.bus
  "Kernel component Event bus: delivers each of rdm-spec's own events to its
  subscribers, synchronously and in subscription order, within one run. It
  carries the events rdm-spec's own spec declares, built in; a project's
  catalogue never changes them. It refuses an event it does not carry, one
  missing a key it carries, or one from the wrong publisher: a defect in
  rdm-spec, so the task cannot run (exit 2)."
  (:require [rdm-spec.kernel.project :as project]))

(def catalogue
  "rdm-spec's own events (its dhf/spec/events.edn): the publisher and the keys of each."
  (array-map
   :KnowledgeIndexed {:published-by :knowledge/sources :carries [:revision :sources :pages :citations :findings]}
   :SpecParsed {:published-by :spec/parser :carries [:revision :scenarios :needs :refusals]}
   :SpecGated {:published-by :spec/check :carries [:revision :passed? :findings]}
   :CycleVerified {:published-by :verification/verify :carries [:cycle :cut :checkout :checks]}
   :VerificationDerived {:published-by :verification/status :carries [:revision :statuses :unknown-results :unreadable-files]}
   :ClausesIndexed {:published-by :compliance/gap :carries [:revision :clauses :documents :findings]}))

(defn event-names [] (vec (keys catalogue)))

(defn new-bus [] (atom {:published [] :subscribers []}))

(def ^:dynamic *bus* nil)

(defn- bus [] (or *bus* (throw (ex-info "no bus in this run" {}))))

(defn subscribe!
  "Calls F with each EVENT published from now on in this run, after the
  subscribers before it."
  [event f]
  (when-not (contains? catalogue event)
    (project/cannot-run! (str "the bus carries no event " (name event))))
  (swap! (bus) update :subscribers conj [event f])
  nil)

(defn publish!
  "Publishes EVENT from PUBLISHER with DATA, delivering it to every subscriber in order."
  [publisher event data]
  (let [{:keys [published-by carries]} (get catalogue event)]
    (cond
      (nil? published-by) (project/cannot-run! (str "the bus refuses the event " (name event) ": rdm-spec does not declare it"))
      (not= publisher published-by) (project/cannot-run! (str "the bus refuses " (name event) " from " (subs (str publisher) 1)
                                                               ": its publisher is " (subs (str published-by) 1)))
      :else (when-let [missing (seq (remove #(contains? data %) carries))]
              (project/cannot-run! (str "the bus refuses " (name event) ": it carries no " (pr-str (vec missing)))))))
  (let [msg (select-keys data (get-in catalogue [event :carries]))]
    (when *bus*
      (swap! *bus* update :published conj {:event event :publisher publisher :data msg})
      (doseq [[e f] (:subscribers @*bus*) :when (= e event)] (f msg)))
    msg))

(defn published [] (if *bus* (:published @*bus*) []))

(defn event-line [{:keys [event publisher]}]
  (str "event: " (name event) " published-by " (subs (str publisher) 1)))

(defn latest
  "The data of the last EVENT published in this run, as its subscribers were given it; nil when none was."
  [event]
  (some (fn [{e :event d :data}] (when (= e event) d)) (reverse (published))))
