(ns rdm-spec.verification.status
  "Derives verified, failed, unverified or planned for every stamped scenario,
  from Allure results; an outline is one requirement, verified by all its
  rows. Status is a report: it exits 0 whatever the statuses
  (dhf/spec/05-verify-by-results/status.feature, 12-several-specs, 13-slice-development)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]))

(defn- row-of [sc r]
  (let [ps (into {} (for [p (:parameters r)] [(get p "name") (str (get p "value"))]))]
    (some #(when (and (seq ps) (= (:values %) (select-keys ps (keys (:values %))))
                      (= (set (keys (:values %))) (set (keys ps))))
             %)
          (:rows sc))))

(defn status-of
  "A scenario's status from the results labelled with its id."
  [sc rs]
  (cond
    (:wip? sc) :planned
    (and (:outline? sc) (seq (:rows sc)))
    (let [by-row (group-by #(row-of sc %) rs)
          by-row (dissoc by-row nil)]
      (cond (some #(not= "passed" (:status %)) (apply concat (vals by-row))) :failed
            (every? #(some (fn [r] (= "passed" (:status r))) (get by-row %)) (:rows sc)) :verified
            :else :unverified))
    (some #(not= "passed" (:status %)) rs) :failed
    (seq rs) :verified
    :else :unverified))

(defn counts [statuses]
  (merge {:verified 0 :failed 0 :unverified 0 :planned 0} (frequencies (vals statuses))))

(defn derive-statuses
  "{:statuses {id status} :unknown [{:file :spec}]} over the stamped scenarios."
  [scenarios rs]
  (let [stamped (filter :id scenarios)
        ids (set (map :id stamped))
        by-spec (group-by :spec rs)]
    {:statuses (into {} (for [sc stamped] [(:id sc) (status-of sc (get by-spec (:id sc)))]))
     :unknown (vec (for [r rs :when (and (:spec r) (not (ids (:spec r))))] {:file (:file r) :spec (:spec r)}))}))

(defn slice-rows
  "Each slice's plan status and its scenarios verified of all."
  [slices scenarios statuses]
  (vec (for [{:keys [slice status]} slices
             :let [scs (filter #(and (:id %) (= slice (:slice %))) scenarios)]]
         {:slice slice :status status
          :verified (count (filter #(= :verified (statuses (:id %))) scs))
          :scenarios (count scs)})))

(defn slices-of-spec [root spec]
  (let [d (fs/path root (:dir spec))]
    (vec (for [s (sort (map #(str (fs/file-name %)) (filter fs/directory? (fs/list-dir d))))
               :when (parser/slice-folder? s)
               :let [plan (fs/path d s "plan.md")]]
           {:slice s :status (when (fs/exists? plan)
                               ((requiring-resolve 'rdm-spec.spec.check/plan-status) (slurp (str plan))))}))))

(defn read-results!
  "Reads the task's results directory, refusing a missing or empty one."
  [root dir]
  (when-let [why (results/dir-problem root dir)] (project/cannot-run! why))
  (results/read-dir (str (fs/path root dir)) dir))

(defn scope-scenarios
  "The scenarios a --spec or --slice option names; cannot run on an unknown one."
  [reading opts]
  (let [specs (:specs reading)
        spec-name (get opts "--spec")
        slice (get opts "--slice")
        specs (if spec-name
                (or (seq (filter #(= spec-name (:name %)) specs))
                    (project/cannot-run! (str "no spec " spec-name ": the specs are " (str/join ", " (keep :name specs)))))
                specs)
        scs (vec (mapcat :scenarios specs))]
    (if slice
      (let [all (set (keep :slice scs))]
        (when-not (all slice) (project/cannot-run! (str "no slice " slice " in the spec")))
        (filterv #(= slice (:slice %)) scs))
      scs)))

(defn derive!
  "Reads the spec and the results, publishes SpecParsed and VerificationDerived. Returns the derivation."
  [run]
  (let [root (:root run) cfg (:cfg run)
        _ (parser/validate-specs! root cfg)
        dir ((requiring-resolve 'rdm-spec.kernel.command/results-dir) run)
        reading (parser/read-specs root cfg)
        _ (bus/publish! :SpecParsed :spec/parser (parser/spec-parsed-event :working-tree reading))
        {rs :results unreadable :unreadable} (read-results! root dir)
        scs (scope-scenarios reading (:opts run))
        all-scs (mapcat :scenarios (:specs reading))
        {:keys [statuses unknown]} (derive-statuses all-scs rs)]
    (bus/publish! :VerificationDerived :verification/status
                  {:revision :working-tree :statuses statuses :unknown-results unknown :unreadable-files (mapv :file unreadable)})
    {:dir dir :reading reading :scenarios scs :statuses statuses :unknown unknown :unreadable unreadable :results rs}))

(defn warnings-of [{:keys [unknown unreadable]}]
  (vec (concat (for [u unreadable] (project/warning (:file u) nil "result-unreadable" (str "the result cannot be read: " (:why u))))
               (for [u unknown] (project/warning (:file u) nil "result-unknown" (str (:spec u) " names no stamped scenario: the result counts toward none"))))))

(defn status-task [run]
  (let [{:keys [dir reading scenarios statuses unknown unreadable] :as d} (derive! run)
        stamped (filter :id scenarios)
        several? (some :several? (:specs reading))
        scoped (select-keys statuses (map :id stamped))
        slices? (some #(= :slices (:layout %)) (:specs reading))]
    {:warnings (warnings-of d)
     :result {:status
              (cond-> {:results dir
                       :scenarios (vec (for [sc stamped]
                                         (cond-> {:id (:id sc) :title (:title sc) :file (:file sc) :line (:line sc) :status (statuses (:id sc))}
                                           several? (assoc :spec (:spec sc)))))
                       :counts (counts scoped)
                       :unknown-results unknown}
                (seq unreadable) (assoc :unreadable-files (mapv :file unreadable))
                slices? (assoc :slices (let [rows (slice-rows (mapcat #(slices-of-spec (:root run) %) (filter #(= :slices (:layout %)) (:specs reading)))
                                                              stamped statuses)]
                                         (if-let [s (get-in run [:opts "--slice"])] (filterv #(= s (:slice %)) rows) rows))))}}))
