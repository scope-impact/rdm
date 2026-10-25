(ns rdm-spec.verification.release
  "Release check (dhf/spec/verification/release.feature): refuses a release
  while any scenario is not verified: failed, unverified, planned (tagged
  @wip), or with no single id, so no status at all. Statuses come from Status;
  an unreadable result file refuses too, as Status reports it.

  Each refused scenario is one finding, `FILE:LINE: release-<status>: id is
  <status>, not verified`. The result:

    {:results \"dir\" :released? bool
     :counts {:scenarios n :verified n ...}
     :refused [{:id :status :file :line}]}"
  (:require [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.status :as status]))

(defn subscriptions [req] (status/subscriptions req))

(def ^:private why
  {:failed "a result labelled with its id did not pass"
   :unverified "no result is labelled with its id"
   :planned "it is tagged @wip, planned work"})

(defn refusals
  "Pure: [{:id :status :file :line}] for every scenario the report does not
  give verified, and every scenario with no single id."
  [report]
  (vec (concat
        (for [sc (:scenarios report) :when (not= :verified (:status sc))]
          (select-keys sc [:id :status :file :line]))
        (for [sc (:unstamped report)]
          (assoc (select-keys sc [:file :line]) :id nil :status :unstamped)))))

(defn- refusal-finding [{:keys [id status file line]}]
  (if (= :unstamped status)
    (command/finding file line "release-unstamped"
                     "a scenario with no single id has no status, so it cannot be verified")
    (command/finding file line (str "release-" (name status))
                     (str id " is " (name status) ", not verified: " (why status)))))

(defn release
  "The release task: `rdm-spec release [--results DIR]`."
  [req]
  (let [{:keys [report findings]} (status/derive! req)
        refused (refusals report)
        own (mapv refusal-finding refused)
        errors (remove command/warning? (concat findings own))]
    {:result {:results (:results report)
              :released? (empty? errors)
              :counts (:counts report)
              :refused refused}
     :findings (vec (concat own findings))}))
