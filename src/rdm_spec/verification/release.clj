(ns rdm-spec.verification.release
  "Release check (dhf/spec/verification/release.feature): `rdm-spec release
  [--results DIR]` refuses a release while any stamped scenario is not
  verified (failed, unverified or planned) and, under the regulated profile,
  while any clause is named by no audit tag.

  Statuses come from Status (derive!, which publishes VerificationDerived);
  the clauses arrive as ClausesIndexed from the compliance context's gap
  stage, which the command runs before this one only under :regulated. A
  release check that reads no ClausesIndexed checks no clause.

  Findings (exit 1), each relaying where it is:
    scenario-not-verified   FILE:LINE of the scenario, naming its id and status
    scenario-unstamped      a scenario with no single id, which no status can name
    clause-unreferenced     relayed from ClausesIndexed unchanged: the clause and its list
  and Status's own (an unreadable result file; an unknown result, a warning).

  The result: {:release {:releasable? bool :results dir :scenarios n
                         :blocked [{:id :status}] :gaps [key]}}."
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.status :as status]))

(defn subscriptions
  "Status's subscriptions (SpecParsed), and ClausesIndexed kept in the run's
  state."
  [req]
  (concat (status/subscriptions req)
          [[:ClausesIndexed (fn [payload] (swap! (:state req) assoc ::clauses payload) nil)]]))

(defn scenario-findings
  "Pure: a finding for every scenario of the status report that is not
  verified, and every unstamped one."
  [report]
  (vec (concat
        (for [sc (:scenarios report)
              :let [st (:status sc)]
              :when (not= :verified st)]
          (command/finding (:file sc) (:line sc) "scenario-not-verified"
                           (str (:id sc) " " (pr-str (:name sc)) " is " (name st)
                                ": a release needs every scenario verified")))
        (for [sc (:unstamped report)]
          (command/finding (:file sc) (:line sc) "scenario-unstamped"
                           (str "the scenario " (pr-str (:name sc)) " carries no single id, so no status names it:"
                                " a release needs every scenario verified"))))))

(defn gap-findings
  "Pure: ClausesIndexed's clause-unreferenced findings, relayed unchanged."
  [clauses]
  (vec (for [f (:findings clauses) :when (= "clause-unreferenced" (:rule f))]
         (command/finding (:file f) (:line f) (:rule f) (:message f)))))

(defn release
  "The release stage."
  [req]
  (let [{:keys [report findings]} (status/derive! req)
        clauses (some-> (:state req) deref ::clauses)
        blocked (scenario-findings report)
        gaps (gap-findings clauses)
        all (vec (concat blocked gaps findings))]
    {:result {:release {:releasable? (empty? (remove command/warning? all))
                        :results (:results report)
                        :scenarios (count (:scenarios report))
                        :blocked (vec (for [sc (:scenarios report) :when (not= :verified (:status sc))]
                                        {:id (:id sc) :status (:status sc)}))
                        :gaps (vec (for [c (:clauses clauses) :when (empty? (:named-at c))] (:key c)))}}
     :findings all}))
