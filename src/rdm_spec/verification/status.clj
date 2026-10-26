(ns rdm-spec.verification.status
  "The Status component of the verification context
  (dhf/spec/verification/status.feature; dhf/spec/glossary.md, \"Verified /
  failed / unverified / planned\"): derives every stamped scenario's status
  from a run's Allure results (read by the Results reader) and publishes
  VerificationDerived.

    planned     tagged @wip, whatever the results say
    failed      a result labelled with its id did not pass
    verified    a passing result and none that did not pass; for an
                outline, every row named by a passing result's parameters
    unverified  otherwise: no result, or a row no result names

  A result labelled with no stamped scenario's id is an unknown result,
  reported, never counted; a result with no spec label counts toward
  nothing and is not reported.

  The stage `status` (task status [--results DIR]); status is a report: it
  exits 0 whatever the statuses. Its result, under its own name (spec-0307):

    {:status {:results DIR :scenarios [{:id :title :file :line :status}]
              :counts {:verified n :failed n :unverified n :planned n}
              :unknown-results [{:file :spec}]}}

  DIR as given; :file of an unknown result is the result's file as DIR
  names it (DIR/name). Unreadable result files are findings
  (result-unreadable), unknown results warnings (result-unknown).

  For trace and release: subscriptions, spec-of, stamped, derive-from!,
  counts, unknown-warnings."
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

(defn subscriptions
  "Keeps SpecParsed in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]])

(defn spec-of
  "The SpecParsed this run published; the task cannot run without it."
  [req]
  (or (some-> (:state req) deref ::spec)
      (command/cannot-run! "no SpecParsed reached the status: the spec was not read")))

(defn stamped
  "The scenarios carrying exactly one id."
  [scenarios]
  (filter :id scenarios))

(defn- passed? [r] (= "passed" (:status r)))

(defn- status-of [sc rs]
  (cond
    (:wip? sc) :planned
    (empty? rs) :unverified
    (some (complement passed?) rs) :failed
    (and (:outline? sc) (seq (:rows sc)))
    (if (every? (fn [row] (some #(and (passed? %) (= (:values row) (:parameters %))) rs)) (:rows sc))
      :verified
      :unverified)
    :else :verified))

(defn derive-statuses
  "Pure: {:statuses {id status} :unknown-results [{:file :spec}] :by-id {id [result]}}
  of the scenarios `scenarios` and the results read `rs`."
  [scenarios rs]
  (let [scs (stamped scenarios)
        ids (set (map :id scs))
        by-id (group-by :spec (filter :spec rs))]
    {:statuses (into {} (for [sc scs] [(:id sc) (status-of sc (get by-id (:id sc)))]))
     :unknown-results (vec (for [r rs :when (and (:spec r) (not (ids (:spec r))))] {:file (:file r) :spec (:spec r)}))
     :by-id (select-keys by-id ids)}))

(defn counts
  "How many scenarios have each status."
  [statuses]
  (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (vals statuses))))

(defn unknown-warnings
  "A warning per result naming no stamped scenario."
  [unknown]
  (vec (for [{:keys [file spec]} unknown]
         (command/warning file "result-unknown"
                          (str "this result is labelled " spec ", which no stamped scenario carries: it counts toward nothing")))))

(defn derive-from!
  "Reads the results directory `dir`, derives every stamped scenario's
  status and publishes VerificationDerived: {:read :derived}."
  [req dir]
  (let [spec (spec-of req)
        read (results/read-dir req dir)
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
  (let [spec (spec-of req)
        {:keys [read derived]} (derive-from! req (:results req))
        statuses (:statuses derived)]
    {:result {:status {:results (:dir read)
                       :scenarios (vec (for [sc (stamped (:scenarios spec))]
                                         {:id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc)
                                          :status (get statuses (:id sc))}))
                       :counts (counts statuses)
                       :unknown-results (:unknown-results derived)}}
     :findings (vec (concat (results/unreadable-findings read)
                            (unknown-warnings (:unknown-results derived))))}))
