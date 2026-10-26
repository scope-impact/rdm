(ns rdm-spec.verification.release
  "The release check (dhf/spec/verification/release.feature): refuses while
  any stamped scenario is not verified, and, under the regulated profile,
  while gap analysis has a finding (ClausesIndexed)."
  (:require [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(defn stage [{:keys [verification]}]
  (let [{:keys [scenarios]} (bus/latest :SpecParsed)
        statuses (:statuses verification)
        clauses (bus/latest :ClausesIndexed)
        not-verified (for [s scenarios :when (:id s) :let [st (get statuses (:id s))] :when (not= :verified st)] [s st])
        findings (vec (concat
                       (for [[s st] not-verified]
                         (cmd/finding (:file s) (:line s) (str "release-" (name st))
                                      (str (:id s) " is " (name st) ": a release needs every scenario verified")))
                       (for [f (:findings clauses) :when (not (:warning? f))]
                         (dissoc f :warning?))))]
    {:findings findings
     :result {:release {:releasable (empty? findings)
                        :scenarios (count (filter :id scenarios))
                        :verified (count (filter #(= :verified %) (vals statuses)))}}}))
