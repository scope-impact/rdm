(ns rdm-spec.verification.status
  "Status (dhf/spec/verification/status.feature): verified, failed,
  unverified or planned for every stamped scenario, derived from a run's
  Allure results and never written. An outline is one requirement, verified
  only when every row has a passing result; a result counts toward a row only
  when it names the row by its parameters. A scenario tagged @wip is planned
  whatever its results say. Status is a report: it exits 0 whatever the
  statuses. Publishes VerificationDerived."
  (:require [babashka.fs :as fs]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]))

(defn- row-key [headings params]
  (let [pm (into {} (map (juxt #(get % "name") #(get % "value")) params))]
    (mapv #(get pm %) headings)))

(defn status-of
  "A scenario's status from the results labelled with its id."
  [s rs]
  (cond
    (:wip? s) :planned
    (:outline? s)
    (let [rows (for [ex (:examples s) row (:rows ex)] [(:headings ex) row])
          of-row (fn [[h row]] (filter #(= row (row-key h (:parameters %))) rs))
          per-row (map of-row rows)]
      (cond
        (some (fn [rs] (some #(not= "passed" (:status %)) rs)) per-row) :failed
        (and (seq rows) (every? seq per-row)) :verified
        :else :unverified))
    (empty? rs) :unverified
    (some #(not= "passed" (:status %)) rs) :failed
    :else :verified))

(defn stamped
  "Every stamped scenario of every spec the project keeps, at a revision."
  [root rev config]
  (vec (for [sc (cmd/spec-configs config)
             s (:scenarios (parser/read-spec root rev sc))
             :when (:id s)]
         s)))

(defn derive-statuses
  "Every stamped scenario with its status, from the results directory named:
  {:scenarios :counts :unknown-results :unreadable :results}."
  [root config named]
  (let [ss (stamped root nil config)
        {rs :results bad :unreadable} (results/read-dir root named)
        by-spec (group-by :spec rs)
        ids (set (map :id ss))
        scenarios (mapv #(assoc % :status (status-of % (by-spec (:id %)))) ss)]
    {:scenarios scenarios
     :results rs
     :counts (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (map :status scenarios)))
     :unknown-results (vec (for [r rs :when (and (:spec r) (not (ids (:spec r))))] {:file (:file r) :spec (:spec r)}))
     :unreadable bad}))

(defn publish! [{:keys [scenarios unknown-results unreadable]}]
  (bus/publish! :verification/status :VerificationDerived
                {:revision :working-tree
                 :statuses (into {} (map (juxt :id :status) scenarios))
                 :unknown-results unknown-results
                 :unreadable-files (mapv :file unreadable)}))

(defn report-of
  "A scenario as the status report lists it."
  [config s]
  (cond-> {:id (:id s) :title (:title s) :file (:file s) :line (:line s) :status (:status s)}
    (seq (:specs config)) (assoc :spec (:spec s))))

(defn stage
  "rdm-spec status."
  [{:keys [root config] :as ctx}]
  (let [named (cmd/results-dir ctx)
        d (derive-statuses root config named)]
    (publish! d)
    {:result {:status {:results named
                       :scenarios (mapv #(report-of config %) (:scenarios d))
                       :counts (:counts d)
                       :unknown-results (:unknown-results d)}}
     :findings (results/unreadable-findings (:unreadable d))}))

(defn derive-stage
  "For a task that shows status: derives it from the results, or, when none
  were named and the configured directory does not exist, says none was
  derived (:no-results)."
  [{:keys [root config] :as ctx}]
  (let [named (cmd/results-dir ctx)]
    (if (and (not (cmd/results-given? ctx)) (not (fs/directory? (fs/path root named))))
      {:ctx {:no-results true}}
      (let [d (derive-statuses root config named)]
        (publish! d)
        {:findings (results/unreadable-findings (:unreadable d))}))))
