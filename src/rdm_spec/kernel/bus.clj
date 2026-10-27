(ns rdm-spec.kernel.bus
  "The in-process event bus (dhf/spec/kernel/bus.feature): synchronous, in
  subscription order, within one run. It carries rdm-spec's own events, built
  in from rdm-spec's own spec (dhf/spec/events.edn), and never reads a
  project's catalogue. It refuses an event it does not declare, one missing a
  key it carries, or one from the wrong publisher: a defect in rdm-spec,
  reported as cannot-run.")

(def catalogue
  "rdm-spec's own events: each one's publisher and the keys it carries."
  {:KnowledgeIndexed {:published-by :knowledge/sources
                      :carries [:revision :sources :pages :citations :findings]}
   :SpecParsed {:published-by :spec/parser
                :carries [:revision :scenarios :needs :refusals]}
   :SpecGated {:published-by :spec/check
               :carries [:revision :passed? :findings]}
   :CycleVerified {:published-by :verification/verify
                   :carries [:cycle :cut :checkout :checks]}
   :VerificationDerived {:published-by :verification/status
                         :carries [:revision :statuses :unknown-results :unreadable-files]}
   :ClausesIndexed {:published-by :compliance/gap
                    :carries [:revision :clauses :documents :findings]}})

(def ^:private state (atom {:subscribers [] :log [] :latest {}}))

(defn start!
  "Begins a run: no subscriber, nothing published."
  []
  (reset! state {:subscribers [] :log [] :latest {}}))

(defn subscribe!
  "Delivers every later `event` to `f`, after the subscribers before it."
  [event subscriber f]
  (swap! state update :subscribers conj {:event event :subscriber subscriber :f f})
  nil)

(defn- refuse [msg]
  (throw (ex-info msg {:cannot-run msg})))

(defn publish!
  "Publishes `event` from `publisher` carrying `payload`, delivering it to
  each subscriber in subscription order."
  [publisher event payload]
  (let [decl (get catalogue event)]
    (cond
      (nil? decl)
      (refuse (str "the bus refuses the event " (name event) ": rdm-spec does not declare it"))
      (not= publisher (:published-by decl))
      (refuse (str "the bus refuses the event " (name event) " from " (subs (str publisher) 1)
                   ": its publisher is " (subs (str (:published-by decl)) 1)))
      :else
      (when-let [missing (seq (remove #(contains? payload %) (:carries decl)))]
        (refuse (str "the bus refuses the event " (name event) ": it does not carry "
                     (clojure.string/join ", " (map str missing)))))))
  (swap! state #(-> % (update :log conj [event publisher]) (assoc-in [:latest event] payload)))
  (doseq [{:keys [f]} (filter #(= event (:event %)) (:subscribers @state))]
    (f payload))
  nil)

(defn published
  "Every event published in this run, in order, as [event publisher]."
  []
  (:log @state))

(defn latest
  "The last payload of `event` published in this run, or nil."
  [event]
  (get-in @state [:latest event]))
