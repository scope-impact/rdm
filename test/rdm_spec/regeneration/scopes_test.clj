(ns rdm-spec.regeneration.scopes-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.regeneration.scopes :as scopes]))

(deftest builder-order
  ;; model order: v (subscriber) before p (publisher); k is the kernel
  (is (= [:k/a :s/p :v/s] (scopes/order [:v/s :s/p :k/a] [:k/a :v/s :s/p :x/y] [[:s/p :v/s]])))
  (is (= [:a/b :a/c] (scopes/order [:a/c :a/b] [:a/b :a/c] [])))
  (is (= [:s/p :v/s] (scopes/order [:v/s :s/p] [:v/s :x/m :s/p] [[:s/p :x/m] [:x/m :v/s]]))))

(deftest event-contracts
  (is (= #{:E} (scopes/changed-events {:E {:published-by :a/b :carries [:x] :shape {:x "1"}}}
                                      {:E {:published-by :a/b :carries [:x] :shape {:x "2"}}})))
  (is (empty? (scopes/changed-events {:E {:published-by :a/b :description "x" :read-by {}}}
                                     {:E {:published-by :a/b :description "y"}}))))
