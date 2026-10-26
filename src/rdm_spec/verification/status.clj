(ns rdm-spec.verification.status
  "The Status component of the verification context
  (dhf/spec/verification/status.feature): verified, failed, unverified or
  planned, for every stamped scenario, derived from a run's Allure results
  (the Results reader) and the scenarios SpecParsed carries; it publishes
  VerificationDerived.

    planned     tagged @wip, whatever the results say
    verified    a passing result and none that did not pass; an outline
                only when every row has a passing result and no result
                naming a row did not pass (a result counts toward a row
                only when its parameters are the row's values)
    failed      a result (naming a row, for an outline) that did not pass
    unverified  otherwise

  A result naming no stamped scenario is unknown (a warning,
  result-unknown); one with no spec label counts toward nothing and is not
  unknown. Status is a report: it exits 0 whatever the statuses.

  The stage `status` (task status [--results DIR]); its result, exactly
  (spec-0307): {:status {:results DIR :scenarios [{:id :title :file :line
  :status}] :counts {:verified :failed :unverified :planned}
  :unknown-results [{:file :spec}]}}, DIR as given and each :file as DIR
  names it. With no results directory (none given and the configured one
  missing) it cannot run, naming the configured directory.

  The stage `derive` (publish's): derives and publishes when there are
  results, and says nothing."
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

(defn subscriptions
  "Keeps SpecParsed in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]])

(defn spec-of
  "The SpecParsed of the run; the task cannot run without it."
  [req]
  (or (some-> (:state req) deref ::spec)
      (command/cannot-run! "no SpecParsed reached the status: the spec was not read")))

(defn stamped
  "The scenarios that carry one id."
  [scenarios]
  (filterv :id scenarios))

(defn- passed? [r] (= "passed" (:status r)))

(defn- status-of [sc rs]
  (cond
    (:wip? sc) :planned
    (:outline? sc)
    (let [rows (map :values (:rows sc))
          of-row (fn [vs] (filter #(= vs (:parameters %)) rs))
          naming (filter (fn [r] (some #(= % (:parameters r)) rows)) rs)]
      (cond
        (some (complement passed?) naming) :failed
        (and (seq rows) (every? (fn [vs] (some passed? (of-row vs))) rows)) :verified
        :else :unverified))
    (empty? rs) :unverified
    (every? passed? rs) :verified
    :else :failed))

(defn derive-statuses
  "Pure: {:statuses {id status} :by-id {id [result]} :unknown-results
  [{:file :spec}]} of the stamped `scenarios` over the `results`."
  [scenarios results]
  (let [scs (stamped scenarios)
        ids (set (map :id scs))
        by-id (group-by :spec (filter :spec results))]
    {:statuses (into (sorted-map) (for [sc scs] [(:id sc) (status-of sc (get by-id (:id sc)))]))
     :by-id (select-keys by-id ids)
     :unknown-results (vec (for [r results :when (and (:spec r) (not (ids (:spec r))))]
                             {:file (:file r) :spec (:spec r)}))}))

(defn counts
  "How many scenarios have each status."
  [statuses]
  (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (vals statuses))))

(defn unknown-warnings
  "A warning per result naming no stamped scenario."
  [unknown]
  (vec (for [{:keys [file spec]} unknown]
         (command/warning file "result-unknown" (str "the result names " spec ", which no stamped scenario carries: it counts toward nothing")))))

(defn derive-from!
  "Reads the results directory `dir`, derives every stamped scenario's
  status and publishes VerificationDerived: {:read :derived}."
  [req dir]
  (when (nil? dir)
    (command/cannot-run! (str "the results directory " (command/config-value req :results-dir)
                              " does not exist: no test run wrote results there (give one with --results DIR)")))
  (let [spec (spec-of req)
        read (results/read-results req dir)
        derived (derive-statuses (:scenarios spec) (:results read))]
    (command/publish! req :verification/status :VerificationDerived
                      {:revision (:revision spec)
                       :statuses (:statuses derived)
                       :unknown-results (:unknown-results derived)
                       :unreadable-files (mapv :file (:unreadable read))})
    {:read read :derived derived}))

(defn status
  "The status stage."
  [req]
  (let [{:keys [read derived]} (derive-from! req (:results req))
        st (:statuses derived)]
    {:result {:status {:results (:dir read)
                       :scenarios (vec (for [sc (stamped (:scenarios (spec-of req)))]
                                         {:id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc)
                                          :status (get st (:id sc))}))
                       :counts (counts st)
                       :unknown-results (:unknown-results derived)}}
     :findings (vec (concat (results/unreadable-findings read) (unknown-warnings (:unknown-results derived))))}))

(defn derive
  "Publish's stage: derives the statuses when there are results."
  [req]
  (when (:results req) (derive-from! req (:results req)))
  {:result {} :findings []})
