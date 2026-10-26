(ns rdm-spec.architecture.model-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]))

(deftest the-hash-is-the-one-the-views-record
  (let [m (edn/read-string (slurp "dhf/spec/model.edn"))
        recorded (second (re-find #"model-sha256: ([0-9a-f]{64})" (slurp "dhf/views/context.mmd")))]
    (is (= recorded (model/model-hash m)))
    (is (= (model/model-hash {:a #{3 1} :b {:y 1 :x 2}}) (model/model-hash {:b {:x 2 :y 1} :a #{1 3}})))))

(deftest read-errors-name-their-line
  (is (= [12] (map :line (:findings (model/read-text "m.edn" (str (apply str (repeat 11 "\n")) "{:a 1\n"))))))
  (is (= [7] (map :line (:findings (model/read-text "m.edn" (str "{:a 1}" (apply str (repeat 6 "\n")) "}")))))))

(def good {:system {:id :s :name "S" :description "d"}
           :people [{:id :p :name "P" :description "d" :actor #{:human}}]
           :containers [{:id :c :name "C" :description "d" :kind :runs :technology "t"
                         :contexts [{:id :x :name "x" :description "d" :components [{:id :x/a :name "A" :description "d" :spec "x/a"}]}]}]
           :relationships [{:from :p :to :s :description "uses"}]
           :views []})

(deftest shape-ids-and-relationships
  (is (empty? (model/check-model "m.edn" good "")))
  (is (= ["model-key-undeclared"] (map :rule (model/check-model "m.edn" (assoc-in good [:containers 0 :owner] "me") ""))))
  (is (= ["model-id-duplicate"] (map :rule (model/check-model "m.edn" (assoc-in good [:containers 0 :contexts 0 :components 0 :id] :c) ""))))
  (is (= ["model-element-unknown"] (map :rule (model/check-model "m.edn" (update good :relationships conj {:from :p :to :archive :description "x"}) "")))))
