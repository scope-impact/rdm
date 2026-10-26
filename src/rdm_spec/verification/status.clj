(ns rdm-spec.verification.status
  "The Status component of the verification context
  (dhf/spec/verification/status.feature; dhf/spec/glossary.md, \"Verified\"):
  derives verified, failed, unverified or planned for every stamped
  scenario from a run's Allure results (the Results reader), never writes
  it, and publishes VerificationDerived.

  A stamped scenario (one with a single id) is
    planned     tagged @wip, whatever its results;
    failed      a result labelled with its id did not pass;
    verified    a result labelled with its id passed, and none did not;
    unverified  no result is labelled with its id.
  An outline is one scenario: verified only when every row has a passing
  result and none that did not pass, failed when any row's result did not
  pass, else unverified. A result counts toward a row only when it names the
  row by its parameters, so results naming no row do not verify an outline
  (spec-0302). A result labelled with an outline's id that did not pass
  fails it whatever row it names, as the oracle says (no result labelled
  with its id did not pass). A result labelled with an id no stamped
  scenario carries is an unknown result (a warning, result-unknown); a
  result with no spec label counts toward nothing and is not unknown.

  The stage `status` (task status [--results DIR]) is a report: it exits 0
  whatever the statuses; an unreadable result file is a finding
  (result-unreadable). Its result:

    {:results DIR :scenarios [{:id :title :file :line :status}]
     :counts {:verified n :failed n :unverified n :planned n}
     :unknown-results [{:file :spec}]}

  The stage `derive` (publish's) derives and publishes only when the task
  has results, quietly. Trace and the release check read the spec and the
  statuses through `spec-of` and `derive-from!`."
  (:refer-clojure :exclude [derive])
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; The spec

(defn subscriptions
  "Keeps the run's SpecParsed."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]])

(defn spec-of
  "The spec SpecParsed published in this run; the task cannot run without it."
  [req]
  (or (some-> (:state req) deref ::spec)
      (command/cannot-run! "no SpecParsed reached the status: the spec was not read")))

(defn stamped
  "The stamped scenarios of `scenarios`: those carrying a single id."
  [scenarios]
  (filter :id scenarios))

;; ---------------------------------------------------------------------------
;; Pure: the derivation

(defn- verdict
  "The status of results that all count toward one scenario or one row."
  [rs]
  (cond (some #(not= "passed" (:status %)) rs) :failed
        (seq rs) :verified
        :else :unverified))

(defn- outline-status [sc rs]
  (let [rows (:rows sc)
        per-row (for [{:keys [values]} rows]
                  (verdict (filter #(= values (:parameters %)) rs)))]
    (cond
      (some #(not= "passed" (:status %)) rs) :failed
      (and (seq rows) (every? #{:verified} per-row)) :verified
      :else :unverified)))

(defn derive-statuses
  "Pure: {:statuses {id status} :by-id {id [result]} :unknown-results
  [{:file :spec}]} of the stamped scenarios of `scenarios` by the results
  `rs` ([{:spec :status :file :parameters}])."
  [scenarios rs]
  (let [scs (stamped scenarios)
        ids (set (map :id scs))
        by-id (group-by :spec (filter :spec rs))]
    {:statuses (into {} (for [sc scs
                              :let [mine (get by-id (:id sc))]]
                          [(:id sc) (cond (:wip? sc) :planned
                                          (:outline? sc) (outline-status sc mine)
                                          :else (verdict mine))]))
     :by-id (select-keys by-id ids)
     :unknown-results (vec (for [r rs :when (and (:spec r) (not (ids (:spec r))))]
                             {:file (:file r) :spec (:spec r)}))}))

(defn counts
  "How many scenarios have each status."
  [statuses]
  (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (vals statuses))))

(defn unknown-warnings
  "A warning per result naming no stamped scenario."
  [unknown]
  (vec (for [{:keys [file spec]} unknown]
         (command/warning file "result-unknown"
                          (str "this result is labelled spec=" spec ", which no stamped scenario carries:"
                               " it counts toward no scenario")))))

;; ---------------------------------------------------------------------------
;; Deriving in a run

(defn derive-from!
  "Reads the results directory `dir`, derives every stamped scenario's
  status from the run's spec and publishes VerificationDerived:
  {:read (the Results reader's reading) :derived {:revision :statuses
  :by-id :unknown-results :unreadable-files}}."
  [req dir]
  (let [spec (spec-of req)
        read (results/read-dir req dir)
        d (derive-statuses (:scenarios spec) (:results read))
        derived (assoc d :revision (:revision spec) :unreadable-files (mapv :file (:unreadable read)))]
    (command/publish! req :verification/status :VerificationDerived
                      (select-keys derived [:revision :statuses :unknown-results :unreadable-files]))
    {:read read :derived derived}))

(defn status
  "The status stage: each stamped scenario's status, a report."
  [req]
  (let [spec (spec-of req)
        {:keys [read derived]} (derive-from! req (:results req))
        st (:statuses derived)]
    {:result {:results (:dir read)
              :scenarios (vec (for [sc (stamped (:scenarios spec))]
                                {:id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc)
                                 :status (get st (:id sc))}))
              :counts (counts st)
              :unknown-results (:unknown-results derived)}
     :findings (vec (concat (results/unreadable-findings read)
                            (unknown-warnings (:unknown-results derived))))}))

(defn derive
  "Publish's stage: derives and publishes VerificationDerived when the task
  has results; without them it does nothing."
  [req]
  (if (:results req)
    (let [{:keys [read]} (derive-from! req (:results req))]
      {:result {} :findings (results/unreadable-findings read)})
    {:result {} :findings []}))
