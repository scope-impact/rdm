(ns rdm-spec.verification.status
  "Component Status (verification context): derives verified, failed,
  unverified or planned for every stamped scenario from a run's Allure
  results, never writing it anywhere. An outline is one requirement: verified
  only when every row has a passing result naming it. Publishes
  VerificationDerived."
  (:require [babashka.fs]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]))

(defn names-row? [result sc row]
  (= (:parameters result) (zipmap (:header row) (:cells row))))

(defn status-of
  "The status of the stamped scenario SC, given the results naming its id."
  [sc rs]
  (cond
    (:wip? sc) :planned
    (:outline? sc)
    (let [rows (:rows sc)
          per-row (for [row rows] (filter #(names-row? % sc row) rs))]
      (cond
        (some #(not= :passed (:status %)) (apply concat per-row)) :failed
        (and (seq rows) (every? #(some (fn [r] (= :passed (:status r))) %) per-row)) :verified
        :else :unverified))
    (empty? rs) :unverified
    (some #(not= :passed (:status %)) rs) :failed
    :else :verified))

(defn stamped [scenarios] (filter :id scenarios))

(defn derive-statuses
  "{:statuses {id status} :unknown-results [{:file :spec}]} of SCENARIOS over RESULTS."
  [scenarios results]
  (let [by-id (group-by :spec (filter :spec results))
        ids (set (map :id (stamped scenarios)))]
    {:statuses (into {} (for [sc (stamped scenarios)] [(:id sc) (status-of sc (get by-id (:id sc)))]))
     :unknown-results (vec (for [r results :when (and (:spec r) (not (ids (:spec r))))] {:file (:file r) :spec (:spec r)}))}))

(defn scenarios-of
  "The scenarios of the specs read, restricted to the spec named by --spec."
  [read opts]
  (let [only (get opts "--spec")]
    (when (and only (not (some #(= only (get-in % [:spec :name])) read)))
      (project/cannot-run! (str "--spec names " only ", and the configuration declares no such spec")))
    (vec (for [r read :when (or (nil? only) (= only (get-in r [:spec :name]))) sc (:scenarios r)] sc))))

(defn derive!
  "Reads the spec and the results directory RD, derives every status, and
  publishes VerificationDerived. {:read :scenarios :results :unreadable :statuses :unknown-results}."
  [cfg opts rd]
  (let [read (parser/read-specs-once (project/root) cfg)
        _ (when-not (bus/latest :SpecParsed) (parser/publish! :working-tree read))
        scenarios (scenarios-of read opts)
        {rs :results unreadable :unreadable} (results/read-dir rd)
        d (derive-statuses scenarios rs)]
    (bus/publish! :verification/status :VerificationDerived
                  {:revision :working-tree :statuses (:statuses d) :unknown-results (:unknown-results d)
                   :unreadable-files (mapv :file unreadable)})
    (merge d {:read read :scenarios scenarios :results rs :unreadable unreadable})))

(defn status-task [{:keys [cfg opts]}]
  (let [rd (project/results-dir cfg opts)
        {:keys [scenarios statuses unknown-results unreadable]} (derive! cfg opts rd)
        multi? (seq (:specs cfg))
        listed (vec (for [sc (stamped scenarios)]
                      (cond-> {:id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc) :status (statuses (:id sc))}
                        multi? (assoc :spec (:spec sc)))))
        counts (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (map :status listed)))]
    (project/report {:status {:results (:dir rd) :scenarios listed :counts counts :unknown-results unknown-results}}
                    (results/unreadable-findings unreadable)
                    (for [{:keys [file spec]} unknown-results]
                      (project/warning file "result-unknown" (str "the result names " spec ", which no stamped scenario is"))))))

(defn derive-stage
  "Derives every status when results are given, or the configured results
  directory exists; publishes VerificationDerived."
  [{:keys [cfg opts]}]
  (let [rd (project/results-dir cfg opts)
        fs? (babashka.fs/directory? (:abs rd))]
    (if (or (:explicit? rd) fs?)
      (let [d (derive! cfg opts rd)]
        {:result {:status {:results (:dir rd)}} :findings (vec (results/unreadable-findings (:unreadable d))) :warnings []})
      {:result nil :findings [] :warnings []})))
