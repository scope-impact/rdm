(ns rdm-spec.verification.release
  "Release check (dhf/spec/verification/release.feature): refuses a release
  while any stamped scenario is not verified (planned, failed or unverified),
  and, under the regulated profile, while gap analysis has any finding that
  is not a warning (a gap, a document with no id, a duplicate id), relayed
  unchanged from ClausesIndexed.

  Statuses come from Status (derive!, within this context); the clauses from
  the ClausesIndexed event the gap stage publishes before it. The audit copy
  runs it as its gate.

  Findings: scenario-planned, scenario-failed, scenario-unverified (at the
  scenario's file and line, naming its id and status), scenario-unstamped,
  the status report's own (an unreadable result), and gap analysis's.
  The result: {:release {:releasable? :results :scenarios :blocking
  :clauses :gaps}}."
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.status :as status]))

(defn subscriptions
  "Status's SpecParsed, and ClausesIndexed kept in the run's state."
  [req]
  (conj (vec (status/subscriptions req))
        [:ClausesIndexed (fn [payload] (swap! (:state req) assoc ::clauses payload) nil)]))

(defn scenario-findings
  "Pure: a finding for each scenario of the status report that is not
  verified, and each scenario with no single id."
  [report]
  (vec (concat
        (for [{:keys [id file line status name]} (:scenarios report)
              :when (not= :verified status)]
          (command/finding file line (str "scenario-" (clojure.core/name status))
                           (str id " is " (clojure.core/name status)
                                (case status
                                  :planned " (tagged @wip): planned work is not releasable"
                                  :failed ": a result labelled with its id did not pass"
                                  :unverified ": no result is labelled with its id"
                                  "")
                                (when name (str " (" name ")")))))
        (for [{:keys [file line name]} (:unstamped report)]
          (command/finding file line "scenario-unstamped"
                           (str "the scenario " (pr-str name) " carries no single id, so it has no status"))))))

(defn clause-findings
  "Pure: gap analysis's findings, as ClausesIndexed carries them, relayed
  unchanged (warnings stay warnings)."
  [clauses-indexed]
  (vec (for [f (:findings clauses-indexed)]
         (if (= :warning (:severity f))
           (command/warning (:file f) (:line f) (:rule f) (:message f))
           (command/finding (:file f) (:line f) (:rule f) (:message f))))))

(defn release
  "The release check."
  [req]
  (let [{:keys [report findings]} (status/derive! req)
        _ (when-not (:derived? report)
            (command/cannot-run! "the release check reads results, and none were given"))
        regulated? (contains? (:config req) :regulated)
        clauses (when regulated?
                  (or (some-> (:state req) deref ::clauses)
                      (command/cannot-run! "under the regulated profile the clauses were not read: no ClausesIndexed event reached the release check")))
        all (vec (concat (scenario-findings report) findings (clause-findings clauses)))
        blocking (remove command/warning? all)]
    {:result {:release (cond-> {:releasable? (empty? blocking)
                                :results (:results report)
                                :scenarios (:counts report)
                                :blocking (count blocking)}
                         regulated? (assoc :clauses (count (:clauses clauses))
                                           :gaps (count (filter #(empty? (:named-at %)) (:clauses clauses)))))}
     :findings all}))
