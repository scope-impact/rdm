(ns rdm-spec.agency.observe-test
  "One test per rule Observe enforces (07-measure/observe.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.agency.observe :as observe]))

(deftest a-run-is-observed-from-outside-begin-to-end
  (is (= ["/w/stray.txt"] (observe/new-entries {"/w" ["a"]} {"/w" ["a" "stray.txt"]} #{})))
  (is (true? (observe/breach? {:writes-outside ["x"] :tmp-left 0 :repo-touched [] :results-in-room false}))))

(deftest two-cycles-runs-are-compared-side-by-side-never-netted
  (is (= {:runs 2 :minutes 3 :tokens 5 :breaches 1}
         (observe/sums [{:minutes 1 :tokens 2 :defence {:writes-outside ["x"] :tmp-left 0 :repo-touched [] :results-in-room false}}
                        {:minutes 2 :tokens 3 :defence {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room false}}]))))
