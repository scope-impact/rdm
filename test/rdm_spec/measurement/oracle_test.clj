(ns rdm-spec.measurement.oracle-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.oracle :as oracle]))

(deftest the-first-difference-is-named-by-its-path
  (is (nil? (oracle/difference {:a [1 2]} {:a [1 2]})))
  (is (= {:path [:items 1 :verdict] :golden :pass :actual :fail}
         (oracle/difference {:items [{:verdict :pass} {:verdict :pass}]} {:items [{:verdict :pass} {:verdict :fail}]})))
  (is (= {:path [:b] :golden nil :actual 2} (oracle/difference {:a 1} {:a 1 :b 2})))
  (is (= {:path [:xs] :golden [1] :actual [1 2]} (oracle/difference {:xs [1]} {:xs [1 2]}))))

(deftest the-cuts-a-cycle-names-are-tagged
  (is (= ["spec-1" "spec-2"] (oracle/cuts {"02" {:cycle {:cut "spec-2"}} "01" {:cycle {:cut "spec-1"}} "03" {:cycle {:cut "spec-1"}}}))))

(deftest recording-rewrites-the-goldens-that-moved
  (let [o {:scorer {"01" 1} :scoreboard {"01-02" 1} :recorded [{:reason "first"}]}
        n (oracle/recorded o {:scoreboard {"01-02" 2}} "2026-10-26" "why")]
    (is (= {:scorer {"01" 1} :scoreboard {"01-02" 2}
            :recorded [{:reason "first"} {:date "2026-10-26" :reason "why" :changed [:scoreboard]}]}
           n))))
