(ns rdm-spec.architecture.domain
  "The domain model (dhf/spec/architecture/domain.feature): an optional
  dhf/spec/domain.edn, held to its shape, to its sources and to the rules of
  domain-driven design a machine can judge. It reads the stamped scenarios
  from SpecParsed."
  (:require [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def shape
  {:top #{:contexts :events}
   :context #{:aggregates :read-models :policies :repositories}
   :aggregate #{:commands :invariants :reads :sources}
   :command #{:by :emits :refuses :sources}
   :invariant #{:text :verified-by :sources}
   :read-model #{:fed-by :sources}
   :policy #{:when :then :sources}
   :repository #{:stores}
   :event #{:sources}})

(def citation-re #"^([A-Za-z0-9][\w.-]*):([^@\s]+)@([0-9a-fA-F]{4,40})$")

(defn- pin-of? [pins commit]
  (some #(or (str/starts-with? commit %) (str/starts-with? % commit)) pins))

(defn findings
  "The domain model's findings. env: :contexts (the model's bounded context
  ids) :catalogue (event names) :scenario-ids (stamped) :sources {source-id #{pin}}."
  [d {:keys [contexts catalogue scenario-ids sources]}]
  (let [ctxs (:contexts d)
        events (:events d)
        aggregates (for [[cid c] ctxs [aid a] (:aggregates c)] {:context cid :id aid :a a})
        agg-context (into {} (map (juxt :id :context) aggregates))
        commands (for [{:keys [context id a]} aggregates [kid k] (:commands a)] {:context context :aggregate id :id kid :k k})
        command-names (set (map :id commands))
        event-names (into (set (keys events)) catalogue)
        emitted (set (mapcat #(get-in % [:k :emits]) commands))
        f (fn [needle rule msg] {:needle needle :rule rule :message msg})
        undeclared (fn [kind m label]
                     (when (map? m)
                       (for [k (keys m) :when (not (contains? (shape kind) k))]
                         (f k "domain-key-undeclared" (str label " carries the key " k ", which the domain model's shape does not declare")))))
        sourced (fn [needle label m]
                  (let [ss (:sources m)]
                    (if (empty? ss)
                      [(f needle "domain-unsourced" (str label " cites no source: every element rests on a raw source"))]
                      (for [s ss
                            :let [[_ src _ commit] (re-matches citation-re (str s))]
                            :when (not (and src (contains? sources src) (pin-of? (get sources src) commit)))]
                        (f needle "domain-source-unknown"
                           (str label " cites " s ", " (cond (nil? src) "which is not a citation"
                                                             (not (contains? sources src)) (str "but dhf/raw/sources.edn records no source " src)
                                                             :else (str "a pin dhf/raw/sources.edn lacks for " src))))))))]
    (vec
     (concat
      (undeclared :top d "the domain model")
      (for [[cid c] ctxs
            x (concat
               (when-not (contains? contexts cid)
                 [(f cid "domain-context-unknown" (str "the context " cid " is no bounded context of model.edn"))])
               (undeclared :context c (str "the context " cid))
               (for [[rid r] (:read-models c)
                     x (concat (undeclared :read-model r (str "the read model " rid))
                               (sourced rid (str "the read model " rid) r)
                               (for [e (:fed-by r) :when (or (command-names e) (not (event-names e)))]
                                 (f rid "domain-read-model-fed"
                                    (str "the read model " rid " is fed by " e ", which is "
                                         (if (command-names e) "a command" "no event") ": a read model is fed only by events"))))]
                 x)
               (for [[pid pl] (:policies c)
                     x (concat (undeclared :policy pl (str "the policy " pid)) (sourced pid (str "the policy " pid) pl))]
                 x)
               (for [[rid r] (:repositories c) x (undeclared :repository r (str "the repository " rid))] x))]
        x)
      (for [{:keys [context id a]} aggregates
            x (concat
               (undeclared :aggregate a (str "the aggregate " id))
               (sourced id (str "the aggregate " id) a)
               (for [r (:reads a)
                     :let [other (agg-context r)]
                     :when (and other (not= other context))]
                 (f id "domain-contexts-coupled"
                    (str "the aggregate " id " of " context " reads the aggregate " r " of " other
                         ": contexts meet through events, never by reading another context's aggregate")))
               (for [[iid inv] (:invariants a)
                     x (concat
                        (undeclared :invariant inv (str "the invariant " iid))
                        (sourced iid (str "the invariant " iid) inv)
                        (if (empty? (:verified-by inv))
                          [(f iid "domain-invariant-unverified" (str "the invariant " iid " of " id " is verified by no scenario"))]
                          (for [s (:verified-by inv) :when (not (contains? scenario-ids s))]
                            (f iid "domain-invariant-unverified"
                               (str "the invariant " iid " of " id " is verified by " s ", which no stamped scenario carries")))))]
                 x))]
        x)
      (for [{:keys [aggregate id k]} commands
            x (concat
               (undeclared :command k (str "the command " id))
               (sourced id (str "the command " id) k)
               (when (and (empty? (:emits k)) (empty? (:refuses k)))
                 [(f id "domain-command-silent" (str "the command " id " of " aggregate " emits no event and names no refusal"))])
               (for [e (:emits k) :when (not (contains? catalogue e))]
                 (f e "domain-event-uncatalogued"
                    (str "the command " id " emits " e ", which the event catalogue does not declare"))))]
        x)
      (for [[kid cs] (group-by :id commands) :when (> (count cs) 1)]
        (f kid "domain-command-handlers"
           (str "the command " kid " is handled by " (count cs) " aggregates, "
                (str/join " and " (map :aggregate cs)) ": a command goes to one aggregate")))
      (for [[eid e] events
            x (concat
               (undeclared :event e (str "the event " eid))
               (sourced eid (str "the event " eid) e)
               (when-not (contains? catalogue eid)
                 [(f eid "domain-event-uncatalogued" (str "the event " eid " is not declared in the event catalogue"))])
               (when-not (emitted eid)
                 [(f eid "domain-event-orphan" (str "the event " eid " is emitted by no command"))]))]
        x)))))

(defn stage
  "The domain model's stage: nothing when the project keeps no domain model."
  [{:keys [root config revision]}]
  (let [file (:domain config "dhf/spec/domain.edn")
        text (cmd/read-at root revision file)]
    (if (nil? text)
      {}
      (let [{:keys [value] :as read} (model/read-text file text)]
        (if-not value
          {:findings (mapv #(assoc % :rule "domain-unreadable") (:findings read))}
          (let [m (:model (model/read-model root revision config))
                srcs (try (cmd/read-edn (cmd/read-at root revision (str (:raw-dir config "dhf/raw") "/sources.edn")))
                          (catch Exception _ nil))
                env {:contexts (set (for [c (:containers m) cx (:contexts c)] (:id cx)))
                     :catalogue (set (keys (:events (model/read-events root revision config))))
                     :scenario-ids (set (keep :id (:scenarios (bus/latest :SpecParsed))))
                     :sources (into {} (for [[k v] srcs] [(name k) (set (map str (:pins v)))]))}]
            {:findings (mapv (fn [{:keys [needle rule message]}]
                               (cmd/finding file (model/line-of text (str needle)) rule message))
                             (findings value env))}))))))
