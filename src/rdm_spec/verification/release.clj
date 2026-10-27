(ns rdm-spec.verification.release
  "The release check (dhf/spec/verification/release.feature): refuses a
  release while any scenario is planned, failed or unverified, naming each
  with its status; under the regulated profile, gap analysis's findings
  refuse it too (the task table runs it first). With --spec it reads the one
  spec named."
  (:require [rdm-spec.kernel.command :as cmd]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

(defn refusals [scenarios]
  (vec (for [s scenarios :when (not= :verified (:status s))]
         (cmd/finding (:file s) (:line s) "release-refused"
                      (str (:id s) " is " (if (= :planned (:status s)) "planned (tagged @wip)" (name (:status s)))
                           ": a release needs every scenario verified")))))

(defn stage [{:keys [root config opts] :as ctx}]
  (let [named (cmd/results-dir ctx)
        spec (:spec opts)
        _ (when (and spec (not (some #{spec} (map :name (:specs config)))))
            (cmd/cannot-run! (str "the configuration keeps no spec named " spec ": its specs are "
                                  (pr-str (mapv :name (:specs config))))))
        d (status/derive-statuses root config named)
        ss (cond->> (:scenarios d) spec (filter #(= spec (:spec %))))
        refused (refusals ss)]
    (status/publish! d)
    {:result {:release {:results named :scenarios (count ss) :refused (count refused)}}
     :findings (into (results/unreadable-findings (:unreadable d)) refused)}))
