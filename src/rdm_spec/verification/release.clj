(ns rdm-spec.verification.release
  "Component Release check (verification context): refuses a release while
  any scenario is planned, failed or unverified; under the regulated
  profile, also while any clause is named by no audit tag or any controlled
  document is not identified (ClausesIndexed)."
  (:require [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

(defn release-task [{:keys [cfg opts]}]
  (let [rd (project/results-dir cfg opts)
        {:keys [scenarios statuses unreadable]} (status/derive! cfg opts rd)
        refused (for [sc (status/stamped scenarios)
                      :let [st (statuses (:id sc))] :when (not= :verified st)]
                  (project/finding (:file sc) (:line sc) "release-refused"
                                   (str (:id sc) " is " (name st) ": a release needs every scenario verified")))
        _ (when (:regulated cfg) ((requiring-resolve 'rdm-spec.compliance.gap/index!) cfg))
        clauses (bus/latest :ClausesIndexed)
        clause-findings (remove :warning (:findings clauses))
        findings (vec (concat (results/unreadable-findings unreadable) refused clause-findings))]
    (project/report {:release {:passed? (empty? findings) :results (:dir rd)
                               :scenarios (count (status/stamped scenarios))
                               :verified (count (filter #(= :verified %) (vals statuses)))
                               :refused (vec (keep #(when (not= :verified (statuses (:id %))) (:id %)) (status/stamped scenarios)))}}
                    findings
                    (filter :warning (:findings clauses)))))
