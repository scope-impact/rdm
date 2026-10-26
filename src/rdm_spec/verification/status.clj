(ns rdm-spec.verification.status
  "Status (dhf/spec/verification/status.feature): verified, failed, unverified
  or planned for every stamped scenario, derived from the results. Status is
  a report; only the release check refuses. Publishes VerificationDerived."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.verification.results :as results]))

(defn- params-map [ps] (into {} (map (fn [p] [(get p "name") (str (get p "value"))]) ps)))

(defn rows
  "An outline's rows, each a map of heading to value."
  [scenario]
  (vec (for [ex (:examples scenario) r (:rows ex)]
         (zipmap (:headings ex) (map str (or (:cells r) r))))))

(defn row-of
  "The row of the outline a result names, by its parameters, or nil."
  [scenario result]
  (let [pm (params-map (:parameters result))]
    (first (filter #(= % pm) (rows scenario)))))

(defn status-of
  "A scenario's status from the results that carry its id."
  [scenario rs]
  (let [mine (filter #(= (:id scenario) (:spec %)) rs)
        passed? #(= "passed" (:status %))]
    (cond
      (:wip? scenario) :planned
      (:outline? scenario)
      (let [rows (rows scenario)
            counted (filter #(row-of scenario %) mine)]
        (cond
          (some (complement passed?) counted) :failed
          (and (seq rows) (every? (fn [row] (some #(and (passed? %) (= row (row-of scenario %))) counted)) rows)) :verified
          :else :unverified))
      (empty? mine) :unverified
      (every? passed? mine) :verified
      :else :failed)))

(defn stamped [scenarios] (filter :id scenarios))

(defn derive-statuses [scenarios rs]
  (into {} (for [s (stamped scenarios)] [(:id s) (status-of s rs)])))

(defn stage
  "Reads the results and derives every stamped scenario's status. Where the
  task allows it (trace, publish), no results directory is no status."
  [{:keys [root opts env task results-optional] :as ctx}]
  (let [{:keys [scenarios revision]} (bus/latest :SpecParsed)
        explicit? (or (:results opts) (not (str/blank? (get env "RESULTS_DIR"))))
        named (cmd/results-dir ctx)]
    (if (and results-optional (not explicit?) (not (fs/directory? (fs/absolutize (fs/path root named)))))
      {:ctx {:no-results true}}
      (let [{rs :results unreadable :unreadable} (results/read-dir root named)
            statuses (derive-statuses scenarios rs)
            ids (set (keep :id scenarios))
            unknown (vec (for [r rs :when (and (:spec r) (not (ids (:spec r))))] {:file (:file r) :spec (:spec r)}))
            listed (vec (for [s (stamped scenarios)]
                          {:id (:id s) :title (:title s) :file (:file s) :line (:line s) :status (statuses (:id s))}))]
        (bus/publish! :verification/status :VerificationDerived
                      {:revision revision :statuses statuses :unknown-results unknown
                       :unreadable-files (mapv :file unreadable)})
        (cond-> {:findings (results/unreadable-findings unreadable)
                 :ctx {:verification {:dir named :results rs :statuses statuses}}}
          (= "status" task)
          (assoc :result {:status {:results named
                                   :scenarios listed
                                   :counts (merge {:verified 0 :failed 0 :unverified 0 :planned 0}
                                                  (frequencies (map :status listed)))
                                   :unknown-results unknown}}))))))
