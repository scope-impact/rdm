(ns rdm-spec.verification.status
  "Status (dhf/spec/verification/status.feature): verified, failed,
  unverified or planned, for every stamped scenario, derived from a run's
  Allure results (the oracle) and never written anywhere.

    planned     tagged @wip (on it, its rule or its feature), whatever its results
    failed      a result labelled with its id did not pass
    verified    a result labelled with its id passed, and none did not pass
    unverified  no result is labelled with its id

  An outline is one scenario: every row's result counts toward its id.

  The spec arrives as SpecParsed (subscriptions); the results are read with
  the Results reader. Deriving publishes VerificationDerived:

    {:revision        \"<commit>\" | :working-tree
     :statuses        {\"spec-0007\" :verified ...}
     :unknown-results [{:file :spec}]     ; results naming no stamped scenario
     :unreadable-files [path]}

  The report (the status task's result, and what trace and release read):

    {:results \"dir\" :derived? true :revision ..
     :counts {:scenarios n :verified n :failed n :unverified n :planned n}
     :scenarios [{:id :name :file :line :component :context :level :needs
                  :planned? :status :results [{:file :status :name}]}]
     :by-status {:verified [id] :failed [id] :unverified [id] :planned [id]}
     :unstamped [{:file :line :name}]   ; scenarios with no single id: no status
     :unknown-results [{:id :file}]
     :unreadable-files [{:file :reason}]}

  With no results directory the report is {:derived? false :results nil}."
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

(def need-re #"UN-\d+")

(def status-order [:verified :failed :unverified :planned])

;; ---------------------------------------------------------------------------
;; The spec, as SpecParsed carries it

(defn subscriptions
  "Keeps SpecParsed's payload in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec-parsed payload) nil)]])

(defn- spec-parsed [req]
  (or (some-> (:state req) deref ::spec-parsed)
      (command/cannot-run! "the spec was not read: no SpecParsed event reached the verification context")))

(defn- name-of [x] (when x (if (keyword? x) (name x) (str x))))

(defn scenario-entry
  "Pure: a parsed scenario as verification names it."
  [sc]
  {:id (:id sc)
   :name (:name sc)
   :file (:file sc)
   :line (:line sc)
   :component (or (:component-name sc) (name-of (:component sc)))
   :context (or (:context-name sc) (name-of (:context sc)))
   :level (:level sc)
   :needs (vec (:needs sc))
   :planned? (boolean (:wip? sc))
   :outline? (= "Scenario Outline" (:keyword sc))})

(defn scenarios
  "Every stamped scenario (exactly one id) of the spec this run read, in
  spec order."
  [req]
  (vec (for [sc (:scenarios (spec-parsed req)) :when (:id sc)] (scenario-entry sc))))

(defn- unstamped [req]
  (vec (for [sc (:scenarios (spec-parsed req)) :when (nil? (:id sc))]
         (select-keys sc [:file :line :name]))))

(defn declared-needs
  "{\"UN-001\" {:text :sources}}: the needs the spec declares."
  [req]
  (let [n (:needs (spec-parsed req))]
    ;; SpecParsed carries the needs as a vector of {:id :text :sources}
    ;; (dhf/spec/events.edn); keyed by id here.
    (if (map? n) n (into {} (map (juxt :id identity)) n))))

;; ---------------------------------------------------------------------------
;; Deriving

(defn status-of
  "Pure: one scenario's status from whether it is planned and the statuses
  of the results labelled with its id."
  [planned? result-statuses]
  (cond
    planned? :planned
    (empty? result-statuses) :unverified
    (some #(not= "passed" %) result-statuses) :failed
    :else :verified))

(defn derive-report
  "Pure: the report from the stamped scenarios and the results read
  ({:dir :results :unreadable}, as rdm-spec.verification.results/read-dir)."
  [scs {:keys [dir unreadable] :as read}]
  (let [{:keys [by-id unknown]} (results/by-scenario (set (map :id scs)) (:results read))
        rows (vec (for [sc scs
                        :let [rs (get by-id (:id sc) [])]]
                    (assoc sc
                           :status (status-of (:planned? sc) (map :status rs))
                           :results (mapv #(select-keys % [:file :status :name]) rs))))
        by-status (into {} (for [k status-order]
                             [k (vec (keep #(when (= k (:status %)) (:id %)) rows))]))]
    {:results dir
     :derived? true
     :counts (into {:scenarios (count rows)} (for [[k ids] by-status] [k (count ids)]))
     :scenarios rows
     :by-status by-status
     :unknown-results (vec unknown)
     :unreadable-files (vec unreadable)}))

(defn findings
  "The report's findings: an unreadable result file is an error (it may hide
  a failure); a result naming no stamped scenario is a warning."
  [report]
  (vec (concat
        (for [{:keys [file reason]} (:unreadable-files report)]
          (command/finding file nil "unreadable-result"
                           (str "the result file is unreadable, so it verifies nothing: " reason)))
        (for [{:keys [id file]} (:unknown-results report)]
          (command/warning file nil "unknown-result"
                           (str "the result is labelled spec=" id ", which names no stamped scenario;"
                                " it counts toward nothing"))))))

(defn derive!
  "Derives every status from the request's results directory, once per run,
  and publishes VerificationDerived. Returns {:report :findings}; with no
  results directory, {:report {:derived? false :results nil} :findings []}.
  Cannot run when the directory does not exist or holds no result."
  [req]
  (if-let [done (some-> (:state req) deref ::derived)]
    done
    (let [out (if-let [dir (:results req)]
                (let [parsed (spec-parsed req)
                      report (assoc (derive-report (scenarios req) (results/read-dir req dir))
                                    :revision (or (:revision parsed) :working-tree)
                                    :unstamped (unstamped req))]
                  (command/publish! req :verification/status :VerificationDerived
                                    {:revision (:revision report)
                                     :statuses (into {} (map (juxt :id :status) (:scenarios report)))
                                     :unknown-results (mapv (fn [{:keys [id file]}] {:file file :spec id})
                                                            (:unknown-results report))
                                     :unreadable-files (mapv :file (:unreadable-files report))})
                  {:report report :findings (findings report)})
                {:report {:derived? false :results nil} :findings []})]
      (when-let [st (:state req)] (swap! st assoc ::derived out))
      out)))

(defn status
  "The status task: `rdm-spec status [--results DIR]`."
  [req]
  (let [{:keys [report findings]} (derive! req)]
    {:result report :findings findings}))
