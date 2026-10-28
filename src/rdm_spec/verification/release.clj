(ns rdm-spec.verification.release
  "Refuses a release while any scenario is not verified; under the regulated
  profile, while any clause is unnamed or a document unidentified; of every
  spec or the one named, of every slice or the one named
  (dhf/spec/05-verify-by-results/release.feature, 10, 12 and 13)."
  (:require [rdm-spec.kernel.project :as project]
            [rdm-spec.verification.status :as status]))

(defn refusals [scenarios statuses]
  (vec (for [sc scenarios
             :let [st (get statuses (:id sc))]
             :when (not= :verified st)]
         (project/finding (:file sc) (:line sc) (str "release-" (name st))
                          (str (:id sc) " is " (name st) ": a release needs every scenario verified")))))

(defn gap-refusals
  "The gap analysis's findings, warnings aside: each blocks a release."
  [findings]
  (vec (remove :warning? findings)))

(defn release-check
  "The release check's report over a run: findings refuse it."
  [run]
  (let [{:keys [scenarios statuses] :as d} (status/derive! run)
        stamped (filter :id scenarios)
        gap (when (contains? (:cfg run) :regulated)
              ((requiring-resolve 'rdm-spec.compliance.gap/index) run))
        fs* (concat (refusals stamped statuses) (gap-refusals (:findings gap)))]
    {:findings (vec fs*)
     :warnings (status/warnings-of d)
     :derived d
     :result {:release {:passed? (empty? fs*) :scenarios (count stamped)
                        :counts (status/counts (select-keys statuses (map :id stamped)))
                        :refused (count fs*)}}}))

(defn release-task [run] (release-check run))
