(ns rdm-spec.verification.status
  "The Status component of the verification context
  (dhf/spec/verification/status.feature; dhf/spec/glossary.md, \"Verified /
  failed / unverified / planned\"): derives every stamped scenario's status
  from a run's Allure results (the Results reader) and publishes it,
  VerificationDerived. It reads the scenarios from SpecParsed.

  A scenario is
    :planned     tagged @wip, whatever its results;
    :failed      a result labelled with its id did not pass;
    :verified    a result labelled with its id passed, none did not, and,
                 for an outline, every example row has a passing result;
    :unverified  otherwise: no result, or an outline row with none.
  A row of an outline is named by the end of a result's name: its
  `key=value` pairs, in the header's order (the runner writes
  \"NAME [k=v, k2=v2]\").

  The stage `status` (task status) reports
    {:results DIR
     :scenarios [{:id :title :file :line :context :component :status}]
     :counts {:verified n :failed n :unverified n :planned n}
     :unknown-results [{:file :spec}]
     :unreadable [file]}
  and exits 0 whatever the statuses: only the release check refuses. An
  unreadable result file and a result naming no stamped scenario are each
  named on the error stream, as warnings. The stage `derive!` (publish) only
  derives and publishes, when results were given.

  Others read it through `subscriptions`, `spec-of`, `stamped`,
  `derive-from!`, `counts` and `unknown-warnings`."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; The spec, from SpecParsed

(defn subscriptions
  "Keeps SpecParsed in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (some-> (:state req) (swap! assoc ::spec payload)) nil)]])

(defn spec-of
  "The SpecParsed payload of the run; the task cannot run without one."
  [req]
  (or (some-> (:state req) deref ::spec)
      (command/cannot-run! "the spec was not read: no SpecParsed event reached the status")))

(defn stamped
  "The scenarios of `scenarios` that carry one id."
  [scenarios]
  (filterv :id scenarios))

;; ---------------------------------------------------------------------------
;; Deriving

(defn- q [s] (java.util.regex.Pattern/quote (str s)))

(defn names-row?
  "Whether the result name `nm` ends with the row's `pairs` ([[k v] ..]) as
  key=value pairs, in order."
  [nm pairs]
  (boolean
   (when (and (string? nm) (seq pairs))
     (re-find (re-pattern (str "(?:^|[\\s\\[(,;|])"
                               (str/join "[\\s,;|]+" (map (fn [[k v]] (str (q k) "=" (q v))) pairs))
                               "[\\s\\])}]*$"))
              nm))))

(defn rows
  "The example rows of the outline `sc`: [[[k v] ..] ..], in order."
  [sc]
  (vec (for [ex (:examples sc) row (:rows ex)]
         (mapv vector (:header ex) (:cells row)))))

(defn status-of
  "The status of the scenario `sc` given its results `rs` ([{:status :name}])."
  [sc rs]
  (cond
    (:wip? sc) :planned
    (empty? rs) :unverified
    (some #(not= "passed" (:status %)) rs) :failed
    (and (:outline? sc) (seq (rows sc)))
    (if (every? (fn [pairs] (some #(names-row? (:name %) pairs) rs)) (rows sc)) :verified :unverified)
    :else :verified))

(defn derive-statuses
  "Pure: {:statuses {id status} :by-id {id [result]} :unknown-results
  [{:file :spec}]} of the scenarios and the results read."
  [scenarios rs]
  (let [scs (stamped scenarios)
        ids (set (map :id scs))
        by-id (group-by :spec (filter #(ids (:spec %)) rs))]
    {:statuses (into {} (for [sc scs] [(:id sc) (status-of sc (get by-id (:id sc) []))]))
     :by-id by-id
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
                          (str "this result is labelled spec=" spec ", which names no stamped scenario: it counts toward none")))))

(defn derive-from!
  "Reads the results directory `dir`, derives every stamped scenario's status
  and publishes VerificationDerived: {:read (the Results reader's) :derived}."
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

;; ---------------------------------------------------------------------------
;; The stages

(defn status
  "The status stage: every stamped scenario with its status."
  [req]
  (let [spec (spec-of req)
        {:keys [read derived]} (derive-from! req (:results req))
        statuses (:statuses derived)]
    {:result {:results (:dir read)
              :scenarios (vec (for [sc (stamped (:scenarios spec))]
                                {:id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc)
                                 :context (:context-name sc) :component (:component-name sc)
                                 :status (get statuses (:id sc))}))
              :counts (counts statuses)
              :unknown-results (:unknown-results derived)
              :unreadable (mapv :file (:unreadable read))}
     :findings (vec (concat (for [{:keys [file why]} (:unreadable read)]
                              (command/warning file "result-unreadable"
                                               (str "this Allure result is unreadable: " why "; it verifies nothing")))
                            (unknown-warnings (:unknown-results derived))))}))

(defn derive!
  "Derives and publishes the statuses when results were given; reports
  nothing."
  [req]
  (when-not (str/blank? (:results req))
    (derive-from! req (:results req)))
  {:result {} :findings []})
