(ns rdm-spec.verification.status
  "The Status component of the verification context
  (dhf/spec/verification/status.feature): verified, failed, unverified or
  planned, for every stamped scenario, derived from a run's Allure results
  (the Results reader), never written. Status is a report: the task exits 0
  whatever the statuses; only the release check refuses.

    planned     tagged @wip, whatever the results say
    failed      a result labelled with its id did not pass
    verified    a passing result labelled with its id, and none that did not
                pass; an outline only when every example row has a passing
                result
    unverified  otherwise: no result, or an outline row with none

  A row of an outline is named by its result's parameters, or by its name
  ending with the row as [key=value, ...]; when no result of an outline
  names a row, it takes one passing result per row.

  It reads the scenarios from SpecParsed (`subscriptions`, `spec-of`) and
  publishes VerificationDerived. `derive-from!` is what Trace, the Release
  check and the audit copy read statuses with.

  The status task's result: {:results DIR :scenarios [{:id :title :file
  :line :status}] :counts {:verified :failed :unverified :planned}
  :unknown-results [{:file :spec}] :unreadable [file]}; each unreadable
  result file is a finding (result-unreadable), each result naming no
  stamped scenario a warning (result-unknown)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; SpecParsed

(defn subscriptions
  "Keeps SpecParsed in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]])

(defn spec-of
  "The spec SpecParsed carried in this run; the task cannot run without it."
  [req]
  (or (some-> (:state req) deref ::spec)
      (command/cannot-run! "no SpecParsed reached the status: the spec was not read")))

(defn stamped
  "The scenarios that carry exactly one id."
  [scenarios]
  (filterv :id scenarios))

;; ---------------------------------------------------------------------------
;; Pure

(defn- row-of
  "The row values a result names: its parameters, or the [k=v, ...] its
  name ends with; nil when it names none."
  [{:keys [parameters name]}]
  (or (when (seq parameters)
        (into {} (for [p parameters :when (map? p)] [(str (or (:name p) (get p "name"))) (str (or (:value p) (get p "value")))])))
      (when-let [[_ inner] (re-find #"\[([^\[\]]*=[^\[\]]*)\]\s*$" (str name))]
        (into {} (for [pair (str/split inner #",\s*")
                       :let [[k v] (str/split pair #"=" 2)]
                       :when v]
                   [(str/trim k) (str/trim v)])))))

(defn- outline-status [sc rs]
  (let [rows (:rows sc)
        named (filter row-of rs)
        covered? (fn [row] (some #(= (:values row) (row-of %)) named))]
    (cond
      (empty? rows) (if (seq rs) :verified :unverified)
      (seq named) (if (every? covered? rows) :verified :unverified)
      :else (if (>= (count rs) (count rows)) :verified :unverified))))

(defn statuses-of
  "Pure: {id status} of the stamped `scenarios`, from the results `rs`
  ([{:spec :status :name :parameters}])."
  [scenarios rs]
  (let [by-id (group-by :spec rs)]
    (into {}
          (for [sc (stamped scenarios)
                :let [mine (get by-id (:id sc))]]
            [(:id sc)
             (cond
               (:wip? sc) :planned
               (some #(not= "passed" (:status %)) mine) :failed
               (empty? mine) :unverified
               (:outline? sc) (outline-status sc mine)
               :else :verified)]))))

(defn unknown
  "The results labelled with an id no stamped scenario carries: [{:file :spec}]."
  [scenarios rs]
  (let [ids (set (map :id (stamped scenarios)))]
    (vec (for [r rs :when (and (:spec r) (not (ids (:spec r))))] {:file (:file r) :spec (:spec r)}))))

(defn counts
  "How many scenarios have each status."
  [statuses]
  (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (vals statuses))))

(defn unknown-warnings
  "A warning for each result naming no stamped scenario."
  [unknown-results]
  (vec (for [{:keys [file spec]} unknown-results]
         (command/warning file "result-unknown"
                          (str "this result is labelled " spec ", which no stamped scenario of the spec carries;"
                               " it counts toward no scenario")))))

;; ---------------------------------------------------------------------------
;; Deriving

(defn- parameters-of [req file]
  (try (let [m (json/parse-string (slurp (str (command/path req file))) true)]
         (when (sequential? (:parameters m)) (:parameters m)))
       (catch Exception _ nil)))

(defn derive-from!
  "Reads the results in the directory `dir` and derives every stamped
  scenario's status from them, publishing VerificationDerived:
  {:read (the Results reader's) :derived {:statuses :by-id :unknown-results}}."
  [req dir]
  (let [spec (spec-of req)
        read (results/read-dir req dir)
        outline-ids (set (map :id (filter :outline? (stamped (:scenarios spec)))))
        rs (mapv (fn [r] (if (outline-ids (:spec r)) (assoc r :parameters (parameters-of req (:file r))) r))
                 (:results read))
        statuses (statuses-of (:scenarios spec) rs)
        unknown-results (unknown (:scenarios spec) rs)]
    (command/publish! req :verification/status :VerificationDerived
                      {:revision (:revision spec)
                       :statuses statuses
                       :unknown-results unknown-results
                       :unreadable-files (mapv :file (:unreadable read))})
    {:read read
     :derived {:statuses statuses
               :by-id (group-by :spec (filter :spec rs))
               :unknown-results unknown-results}}))

(defn status
  "The status stage: `rdm-spec status [--results DIR]`."
  [req]
  (let [spec (spec-of req)
        {:keys [read derived]} (derive-from! req (:results req))
        sts (:statuses derived)]
    {:result {:results (:dir read)
              :scenarios (vec (for [sc (stamped (:scenarios spec))]
                                {:id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc)
                                 :status (get sts (:id sc))}))
              :counts (counts sts)
              :unknown-results (:unknown-results derived)
              :unreadable (mapv :file (:unreadable read))}
     :findings (vec (concat (results/unreadable-findings read)
                            (unknown-warnings (:unknown-results derived))))}))
