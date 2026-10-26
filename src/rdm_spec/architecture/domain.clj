(ns rdm-spec.architecture.domain
  "The Domain model component of the architecture context
  (dhf/spec/architecture/domain.feature; glossary, \"Domain model\"): holds
  a project's optional domain model, the file the configuration's :domain
  names (default dhf/spec/domain.edn), to the rules of domain-driven design
  and to its sources. A stage of `rdm-spec check`; a project without the
  file is asked for nothing.

  The shape (spec-0335), each element a map keyed by its id:

    {:contexts {ctx {:aggregates   {agg {:commands   {Cmd {:by :emits [Ev] :refuses [why] :sources [c]}}
                                         :invariants {inv {:text :verified-by [id] :sources [c]}}
                                         :reads      [read-model-or-aggregate]
                                         :sources    [c]}}
                     :read-models  {rm {:fed-by [Ev] :sources [c]}}
                     :policies     {p {:when Ev :then \"...\" :sources [c]}}
                     :repositories {r {:stores [agg]}}}}
     :events {Ev {:sources [c]}}}

  It reads the model (its bounded contexts) and the event catalogue with
  the Model component, the stamped scenario ids from SpecParsed, and the
  pins of the raw sources from the sources index, <raw-dir>/sources.edn.

  Rules, each a stable name on the error stream, at the file's line naming
  the element:
    domain-unreadable            not EDN, or not a map
    domain-key-undeclared        a key the shape does not declare
    domain-shape                 an entry of the wrong kind
    domain-context-unknown       a context that is no bounded context of the model
    domain-unsourced             an aggregate, command, invariant, read model,
                                 policy or event citing no source
    domain-source-unknown        a citation that is not <source>:<path>@<commit>
                                 of a recorded source at one of its pins
    domain-command-handlers      a command two aggregates handle
    domain-command-silent        a command that emits no event and names no refusal
    domain-event-orphan          an event of :events no command emits
    domain-read-model-fed        a read model fed by what is no event
    domain-invariant-unverified  an invariant no stamped scenario verifies
    domain-contexts-coupled      an aggregate reading an aggregate of another context
    domain-event-uncatalogued    an event (emitted or under :events) the event
                                 catalogue does not declare

  Result: {:domain {:file F :contexts n :aggregates n}} when there is one."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.kernel.command :as command]))

(def shape
  {:domain #{:contexts :events}
   :context #{:aggregates :read-models :policies :repositories}
   :aggregate #{:commands :invariants :reads :sources}
   :command #{:by :emits :refuses :sources}
   :invariant #{:text :verified-by :sources}
   :read-model #{:fed-by :sources}
   :policy #{:when :then :sources}
   :repository #{:stores}
   :event #{:sources}})

(def ^:private kind-names
  {:context "the context" :aggregate "the aggregate" :command "the command" :invariant "the invariant"
   :read-model "the read model" :policy "the policy" :repository "the repository" :event "the event"})

(def ^:private citation-re #"^([^\s:@]+):([^\s@]+)@([0-9a-f]{7,40})$")

(defn- line-of [text k]
  (when text (model/line-of text [(pr-str k)])))

(defn- entries
  "Every element of the domain model `d`: [{:kind :id :el :context :aggregate :path}]."
  [d]
  (let [m? map?
        ctxs (when (m? (:contexts d)) (:contexts d))]
    (vec
     (concat
      (for [[c x] ctxs] {:kind :context :id c :el x :path [:contexts c]})
      (for [[c x] ctxs :when (m? x) :when (m? (:aggregates x)) [a ag] (:aggregates x)]
        {:kind :aggregate :id a :el ag :context c :path [:contexts c :aggregates a]})
      (for [[c x] ctxs :when (m? x) :when (m? (:aggregates x)) [a ag] (:aggregates x) :when (m? ag)
            :when (m? (:commands ag)) [k cmd] (:commands ag)]
        {:kind :command :id k :el cmd :context c :aggregate a :path [:contexts c :aggregates a :commands k]})
      (for [[c x] ctxs :when (m? x) :when (m? (:aggregates x)) [a ag] (:aggregates x) :when (m? ag)
            :when (m? (:invariants ag)) [k inv] (:invariants ag)]
        {:kind :invariant :id k :el inv :context c :aggregate a :path [:contexts c :aggregates a :invariants k]})
      (for [[c x] ctxs :when (m? x) :when (m? (:read-models x)) [k rm] (:read-models x)]
        {:kind :read-model :id k :el rm :context c :path [:contexts c :read-models k]})
      (for [[c x] ctxs :when (m? x) :when (m? (:policies x)) [k p] (:policies x)]
        {:kind :policy :id k :el p :context c :path [:contexts c :policies k]})
      (for [[c x] ctxs :when (m? x) :when (m? (:repositories x)) [k r] (:repositories x)]
        {:kind :repository :id k :el r :context c :path [:contexts c :repositories k]})
      (when (m? (:events d))
        (for [[k e] (:events d)] {:kind :event :id k :el e :path [:events k]}))))))

(defn- describe [{:keys [kind id context aggregate]}]
  (str (kind-names kind) " " (pr-str id)
       (cond aggregate (str " of the aggregate " (pr-str aggregate) " in " (pr-str context))
             context (str " of " (pr-str context)))))

(defn- map-shaped [x] (or (nil? x) (map? x)))

(defn findings
  "Pure: every finding of the domain model, `in` {:domain d (::unreadable
  with :error) :text :file :contexts #{model context id} :catalogue {event
  ..} :scenario-ids #{id} :sources {source-id #{pin}}}."
  [{:keys [domain text file contexts catalogue scenario-ids sources error]}]
  (if (or (= ::unreadable domain) (not (map? domain)))
    [(command/finding file nil "domain-unreadable"
                      (str "the domain model " file " is not readable as one EDN map"
                           (when error (str ": " error))))]
    (let [d domain
          es (entries d)
          at (fn [e] (line-of text (:id e)))
          f (fn [e rule msg] (command/finding file (at e) rule msg))
          shape-of (fn [e] (get shape (:kind e)))
          events-decl (if (map? (:events d)) (set (keys (:events d))) #{})
          commands (filter #(= :command (:kind %)) es)
          command-ids (set (map :id commands))
          emitted (set (mapcat #(when (map? (:el %)) (:emits (:el %))) commands))
          aggregates (filter #(= :aggregate (:kind %)) es)
          agg-context (into {} (map (juxt :id :context)) aggregates)
          event? (fn [x] (or (contains? events-decl x) (contains? catalogue x)))]
      (vec
       (concat
        ;; the shape
        (for [k (sort-by str (keys d)) :when (not ((:domain shape) k))]
          (command/finding file (line-of text k) "domain-key-undeclared"
                           (str "the domain model carries the key " (pr-str k) ", which its shape does not declare")))
        (for [[k v] [[:contexts (:contexts d)] [:events (:events d)]] :when (not (map-shaped v))]
          (command/finding file (line-of text k) "domain-shape" (str "the domain model's " k " is not a map")))
        (for [e es :when (not (map? (:el e)))]
          (f e "domain-shape" (str (describe e) " is not a map")))
        (for [e es :when (map? (:el e))
              k (sort-by str (keys (:el e))) :when (not ((shape-of e) k))]
          (command/finding file (or (line-of text k) (at e)) "domain-key-undeclared"
                           (str (describe e) " carries the key " (pr-str k) ", which the domain model's shape does not declare"
                                " (it declares " (str/join " " (map pr-str (sort-by str (shape-of e)))) ")")))
        (for [e es :when (and (= :context (:kind e)) (map? (:el e)))
              k [:aggregates :read-models :policies :repositories]
              :when (not (map-shaped (get (:el e) k)))]
          (f e "domain-shape" (str (describe e) "'s " k " is not a map")))
        (for [e es :when (and (= :aggregate (:kind e)) (map? (:el e)))
              k [:commands :invariants] :when (not (map-shaped (get (:el e) k)))]
          (f e "domain-shape" (str (describe e) "'s " k " is not a map")))
        (for [e es :when (and (= :context (:kind e)) (not (contains? contexts (:id e))))]
          (f e "domain-context-unknown"
             (str (describe e) " is no bounded context of the model: a domain context is one of its bounded contexts")))
        ;; the sources
        (for [e es :when (and (map? (:el e)) (not= :context (:kind e)) (not= :repository (:kind e))
                              (empty? (:sources (:el e))))]
          (f e "domain-unsourced" (str (describe e) " cites no source: every element rests on a raw source")))
        (for [e es :when (map? (:el e))
              c (when (sequential? (:sources (:el e))) (:sources (:el e)))
              :let [[_ src _ commit] (re-matches citation-re (str c))]
              :when (not (and src (contains? (get sources src) commit)))]
          (f e "domain-source-unknown"
             (str (describe e) " cites " (pr-str c) ", "
                  (cond (nil? src) "which is not <source>:<path>@<commit>"
                        (not (contains? sources src)) (str "but the sources index records no source " src)
                        :else (str "but the sources index pins " src " at no commit " commit)))))
        ;; the rules of domain-driven design
        (for [[k cs] (sort-by (comp str key) (group-by :id commands)) :when (> (count cs) 1)]
          (f (second cs) "domain-command-handlers"
             (str "the command " (pr-str k) " is handled by " (str/join " and " (map #(pr-str (:aggregate %)) cs))
                  ": one aggregate handles a command")))
        (for [e commands :when (map? (:el e))
              :when (and (empty? (:emits (:el e))) (empty? (:refuses (:el e))))]
          (f e "domain-command-silent" (str (describe e) " emits no event and names no refusal")))
        (for [k (sort-by str events-decl) :when (not (contains? emitted k))]
          (command/finding file (line-of text k) "domain-event-orphan"
                           (str "the event " (pr-str k) " is emitted by no command")))
        (for [e es :when (and (= :read-model (:kind e)) (map? (:el e)))
              x (:fed-by (:el e)) :when (not (event? x))]
          (f e "domain-read-model-fed"
             (str (describe e) " is fed by " (pr-str x) ", "
                  (if (contains? command-ids x) "a command" "which is no event") ": a read model is fed only by events")))
        (for [e es :when (and (= :invariant (:kind e)) (map? (:el e)))
              :let [vb (:verified-by (:el e))
                    unknown (remove #(contains? scenario-ids (str %)) vb)]
              :when (or (empty? vb) (seq unknown))]
          (f e "domain-invariant-unverified"
             (if (empty? vb)
               (str (describe e) " is verified by no scenario")
               (str (describe e) " is verified by " (str/join ", " unknown) ", which no stamped scenario carries"))))
        (for [e aggregates :when (map? (:el e))
              r (:reads (:el e))
              :let [other (get agg-context r)]
              :when (and other (not= other (:context e)))]
          (f e "domain-contexts-coupled"
             (str (describe e) " reads the aggregate " (pr-str r) " of " (pr-str other)
                  ": contexts meet only through events")))
        (for [k (sort-by str (distinct (concat emitted events-decl))) :when (not (contains? catalogue k))]
          (command/finding file (line-of text k) "domain-event-uncatalogued"
                           (str "the event " (pr-str k) " is not declared in the event catalogue"))))))))

(defn subscriptions
  "Keeps SpecParsed: the stamped scenario ids invariants are verified by."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]])

(defn- read-sources [req]
  (let [f (command/path req (str (str/replace (command/config-value req :raw-dir) #"/+$" "") "/sources.edn"))
        v (when (fs/regular-file? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))]
    (if (map? v)
      (into {} (for [[k s] v :when (map? s)] [(name k) (set (map str (:pins s)))]))
      {})))

(defn check
  "The domain model's stage of `rdm-spec check`."
  [req]
  (let [rel (command/config-value req :domain)
        f (command/path req rel)]
    (if-not (fs/exists? f)
      {:result {} :findings []}
      (let [text (slurp (str f))
            v (try {:ok (edn/read-string text)} (catch Exception e {:error (ex-message e)}))
            {:keys [model catalogue] refusals :findings} (model/load! req)
            spec (some-> (:state req) deref ::spec)
            fs* (findings {:domain (if (contains? v :ok) (:ok v) ::unreadable) :error (:error v)
                           :text text :file rel
                           :contexts (set (map :id (model/contexts model)))
                           :catalogue (or (:catalogue catalogue) {})
                           :scenario-ids (set (keep :id (:scenarios spec)))
                           :sources (read-sources req)})
            d (:ok v)]
        {:result {:domain {:file rel
                           :contexts (if (map? (:contexts d)) (count (:contexts d)) 0)
                           :aggregates (count (filter #(= :aggregate (:kind %)) (when (map? d) (entries d))))}}
         :findings (vec (concat refusals fs*))}))))
