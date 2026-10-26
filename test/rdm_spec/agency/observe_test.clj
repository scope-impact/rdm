(ns rdm-spec.agency.observe-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.agency.observe :as o]))

(deftest new-entries
  (is (= ["/w/b"] (o/new-entries {"/w" ["/w/a"]} {"/w" ["/w/a" "/w/b"]} #{}))))

(deftest touched-outside-the-cycle
  (is (= ["dhf/spec/x.feature"] (o/touched ["?? a.md"] ["?? a.md" "?? dhf/spec/x.feature" "?? dhf/cycles/13/runs.edn"] "dhf/cycles/13"))))

(deftest report-sums
  (let [r (o/report "12" [{:minutes 1.5 :tokens 100 :defence {:writes-outside ["/x"] :tmp-left 0 :repo-touched [] :results-in-room false}}
                          {:minutes 2.25 :tokens nil :defence {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room false}}]
                    "13" [])]
    (is (= {:cycles ["12" "13"] :runs {"12" 2 "13" 0} :minutes {"12" 3.75 "13" 0.0} :tokens {"12" 100 "13" 0} :breaches {"12" 1 "13" 0}} r))))
