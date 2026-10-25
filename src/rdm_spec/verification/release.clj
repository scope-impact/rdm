(ns rdm-spec.verification.release
  "The Release check of the verification context
  (dhf/spec/verification/release.feature): refuses a release while any
  scenario is not verified, and, under the regulated profile, while any
  clause is not named or any controlled document is not identified.

  The stage `release` (task release, and the gate before the audit copy)
  derives every stamped scenario's status from the results (the Status
  component, which publishes VerificationDerived) and finds, at the
  scenario's file and line:
    release-planned     a scenario tagged @wip (planned work is not released)
    release-failed      a scenario a result did not pass
    release-unverified  a scenario with no result
    release-unstamped   a scenario with no single id: nothing can verify it
  the parser's refusals (relayed: a spec the parser refuses is not
  releasable), every unreadable result file (result-unreadable), and under
  :regulated every finding of the gap stage, relayed from ClausesIndexed
  unchanged, its warnings as warnings. A result naming no stamped scenario
  is a warning (result-unknown).

  Its result: {:release {:results DIR :releasable? bool :counts {..}}}."
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

(defn subscriptions
  "Keeps SpecParsed (as the Status component reads it) and ClausesIndexed."
  [req]
  (conj (vec (status/subscriptions req))
        [:ClausesIndexed (fn [payload] (swap! (:state req) assoc ::clauses payload) nil)]))

(defn- relay [{:keys [file line rule message severity]}]
  (if (= :warning severity)
    (command/warning file line rule message)
    (command/finding file line rule message)))

(defn scenario-findings
  "A finding per scenario of `scenarios` that is not verified, by `statuses`."
  [scenarios statuses]
  (vec
   (concat
    (for [sc scenarios :when (nil? (:id sc))]
      (command/finding (:file sc) (:line sc) "release-unstamped"
                       (str "the scenario \"" (:title sc) "\" carries no single id, so nothing can verify it")))
    (for [sc (status/stamped scenarios)
          :let [st (get statuses (:id sc))]
          :when (not= :verified st)]
      (command/finding (:file sc) (:line sc) (str "release-" (name st))
                       (str (:id sc) " \"" (:title sc) "\" is " (name st)
                            (case st
                              :planned " (tagged @wip): planned work is not released"
                              :failed ": a result labelled with its id did not pass"
                              :unverified ": no result is labelled with its id")))))))

(defn release
  "The release check stage."
  [req]
  (let [spec (status/spec-of req)
        {:keys [read derived]} (status/derive-from! req (:results req))
        clauses (some-> (:state req) deref ::clauses)
        regulated? (contains? (:config req) :regulated)
        _ (when (and regulated? (nil? clauses))
            (command/cannot-run! "the configuration holds :regulated, but the clauses were not read: no ClausesIndexed event reached the release check"))
        refusals (for [{:keys [file line rule message]} (:refusals spec)] (command/finding file line rule message))
        findings (vec (concat (scenario-findings (:scenarios spec) (:statuses derived))
                              refusals
                              (results/unreadable-findings read)
                              (when regulated? (map relay (:findings clauses)))
                              (status/unknown-warnings (:unknown-results derived))))]
    {:result {:release {:results (:dir read)
                        :releasable? (not-any? (complement command/warning?) findings)
                        :counts (status/counts (:statuses derived))}}
     :findings findings}))
