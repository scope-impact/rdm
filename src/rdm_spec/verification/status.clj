(ns rdm-spec.verification.status
  "The Status component of the verification context
  (dhf/spec/verification/status.feature): verified, failed, unverified or
  planned for every stamped scenario, derived from a run's Allure results
  and never written. It reads the scenarios from SpecParsed and publishes
  VerificationDerived.

  A scenario tagged @wip is planned whatever its results say; otherwise it
  is verified when a result labelled with its id passed and none labelled
  with its id did not pass, failed when one did not pass, unverified when it
  has none (dhf/spec/glossary.md, \"Oracle\"). An outline is one scenario:
  the results of all its rows count toward it. A result naming no stamped
  scenario is unknown, counts toward nothing and is reported (a warning); a
  result with no spec label verifies nothing and is not reported.

  The stage `status` (task status) is a report: it exits 0 whatever the
  statuses; an unreadable result file is a finding. Its result:

    {:results \"DIR\"
     :counts {:verified n :failed n :unverified n :planned n}
     :scenarios [{:id :title :status :level :component :context :file :line}]
     :unknown-results [{:file :spec}]
     :unreadable-files [path]}

  The stage `derive!` (task publish, quiet) publishes VerificationDerived
  when results were given, and nothing otherwise."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps SpecParsed in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]])

(defn spec-of
  "The SpecParsed payload this run saw; the task cannot run without one."
  [req]
  (or (some-> (:state req) deref ::spec)
      (command/cannot-run! "the spec was not read: no SpecParsed event reached the verification context")))

;; ---------------------------------------------------------------------------
;; Deriving

(defn stamped
  "The stamped scenarios of `scenarios`, each id once (the first)."
  [scenarios]
  (->> scenarios
       (filter :id)
       (reduce (fn [[seen out] sc] (if (seen (:id sc)) [seen out] [(conj seen (:id sc)) (conj out sc)]))
               [#{} []])
       second))

(defn status-of
  "The status of the scenario `sc` given the results labelled with its id."
  [sc rs]
  (cond
    (:wip? sc) :planned
    (empty? rs) :unverified
    (every? #(= "passed" (:status %)) rs) :verified
    :else :failed))

(defn derive-statuses
  "{:statuses {id status} :scenarios [entry] :unknown-results [{:file :spec}]
  :by-id {id [result]}} of `scenarios` against `read` (results/read-dir)."
  [scenarios read]
  (let [scs (stamped scenarios)
        ids (set (map :id scs))
        by-id (group-by :spec (filter :spec (:results read)))
        statuses (into {} (for [sc scs] [(:id sc) (status-of sc (get by-id (:id sc)))]))]
    {:statuses statuses
     :by-id by-id
     :scenarios (vec (for [sc scs]
                       {:id (:id sc) :title (:title sc) :status (get statuses (:id sc))
                        :level (:level sc)
                        :component (:component-name sc) :context (:context-name sc)
                        :file (:file sc) :line (:line sc)}))
     :unknown-results (vec (for [r (:results read) :when (and (:spec r) (not (ids (:spec r))))]
                             {:file (:file r) :spec (:spec r)}))}))

(defn counts [statuses]
  (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (vals statuses))))

(defn unknown-warnings [unknown]
  (vec (for [{:keys [file spec]} unknown]
         (command/warning file "result-unknown"
                          (str "the result is labelled spec=" spec ", which no stamped scenario carries;"
                               " it counts toward no scenario")))))

(defn derive-from!
  "Reads the results in `dir` against the spec this run read, publishes
  VerificationDerived and returns {:read :derived}."
  [req dir]
  (let [spec (spec-of req)
        read (results/read-dir req dir)
        derived (derive-statuses (:scenarios spec) read)]
    (command/publish! req :verification/status :VerificationDerived
                      {:revision (:revision spec)
                       :statuses (:statuses derived)
                       :unknown-results (:unknown-results derived)
                       :unreadable-files (mapv :file (:unreadable read))})
    {:read read :derived derived}))

;; ---------------------------------------------------------------------------
;; The stages

(defn status
  "The status stage: every stamped scenario's status."
  [req]
  (let [{:keys [read derived]} (derive-from! req (:results req))]
    {:result {:results (:dir read)
              :counts (counts (:statuses derived))
              :scenarios (:scenarios derived)
              :unknown-results (:unknown-results derived)
              :unreadable-files (mapv :file (:unreadable read))}
     :findings (into (results/unreadable-findings read) (unknown-warnings (:unknown-results derived)))}))

(defn derive!
  "Publish's quiet stage: VerificationDerived when results were given,
  nothing otherwise."
  [req]
  (when-not (str/blank? (:results req))
    (derive-from! req (:results req)))
  {:findings []})
