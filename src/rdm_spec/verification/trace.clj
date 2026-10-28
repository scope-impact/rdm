(ns rdm-spec.verification.trace
  "A need or a scenario with everything linked to it, in both directions
  (dhf/spec/05-verify-by-results/trace.feature, 12-several-specs/trace.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

(def no-status "no status was derived because no results were given")

(defn scenario-row [sc statuses]
  {:id (:id sc) :title (:title sc) :component (:component-name sc) :context (some-> (:context sc) name)
   :level (:level sc) :status (get statuses (:id sc))})

(defn need-trace [need scenarios statuses]
  (let [scs (filter #(and (:id %) (some #{(:id need)} (:needs %))) scenarios)]
    (cond-> {:kind :need :id (:id need) :text (:text need) :scenarios (mapv #(scenario-row % statuses) scs)}
      (empty? scs) (assoc :says (str "no scenario traces to " (:id need))))))

(defn scenario-trace [sc statuses rs]
  (cond-> {:kind :scenario :id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc)
           :needs (:needs sc) :component (:component-name sc) :context (some-> (:context sc) name) :level (:level sc)}
    (:spec sc) (assoc :spec (:spec sc))
    statuses (assoc :status (get statuses (:id sc))
                    :results (vec (for [r rs :when (= (:id sc) (:spec r))] {:file (:file r) :status (:status r)})))))

(defn find-target
  "{:need n :scenarios scs} or {:scenario sc}, within the specs; cannot run when nothing or more than one spec holds it."
  [id specs spec-name]
  (let [specs (if spec-name
                (or (seq (filter #(= spec-name (:name %)) specs))
                    (project/cannot-run! (str "no spec " spec-name ": the specs are " (str/join ", " (keep :name specs)))))
                specs)]
    (if (re-matches #"UN-\d+" id)
      (let [holders (filter #(some (fn [n] (= id (:id n))) (:needs %)) specs)]
        (cond (empty? holders) (project/cannot-run! (str id " names no need and no scenario"))
              (> (count holders) 1) (project/cannot-run! (str id " is a need of more than one spec: " (str/join " and " (map :name holders)) "; name one with --spec"))
              :else (let [s (first holders)] {:need (some #(when (= id (:id %)) %) (:needs s)) :scenarios (:scenarios s)})))
      (let [hits (for [s specs sc (:scenarios s) :when (= id (:id sc))] sc)]
        (if (seq hits) {:scenario (first hits)} (project/cannot-run! (str id " names no scenario and no need")))))))

(defn trace [run]
  (let [root (:root run) cfg (:cfg run)
        _ (parser/validate-specs! root cfg)
        id (first (:args run))
        reading (parser/read-specs root cfg)
        given? ((requiring-resolve 'rdm-spec.kernel.command/results-given?) run)
        dir ((requiring-resolve 'rdm-spec.kernel.command/results-dir) run)
        exists? (fs/directory? (fs/path root dir))
        _ (when (and given? (not exists?)) (project/cannot-run! (str "the results directory " dir " does not exist")))
        target (find-target id (:specs reading) (get-in run [:opts "--spec"]))
        {rs :results} (when exists? (results/read-dir (str (fs/path root dir)) dir))
        all (mapcat :scenarios (:specs reading))
        statuses (when exists? (:statuses (status/derive-statuses all rs)))
        t (if (:need target)
            (need-trace (:need target) (:scenarios target) statuses)
            (scenario-trace (:scenario target) statuses rs))]
    {:result {:trace (cond-> t (not exists?) (assoc :status-note no-status))}}))
