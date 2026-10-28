(ns rdm-spec.architecture.views-test
  "One test per rule Views enforces (04-model-the-architecture/views.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.architecture.views :as views]))

(def m {:system {:id :s :name "S" :description "d"}
        :people [{:id :p :name "P" :description "d"}]
        :external [{:id :e :name "E" :description "d"}]
        :containers [{:id :c :name "C" :kind :runs :description "d"
                      :contexts [{:id :a :name "a" :description "d" :components [{:id :a/x :name "X" :description "d"}]}]}
                     {:id :db :name "DB" :kind :stores :description "d"}]
        :relationships [{:from :p :to :c :description "uses"} {:from :a/x :to :db :description "writes"}]})

(deftest the-model-is-one-source-and-every-view-is-generated-from-it
  (let [src (views/mermaid m {:key "context" :level :context :description "d"} "H" "m.edn")]
    (is (str/starts-with? src "%% model-sha256: H"))))

(deftest each-level-of-view-holds-what-c4-says-it-holds
  (let [src (views/mermaid m {:key "a" :level :component :context :a :description "d"} "H" "m.edn")]
    (is (str/includes? src "Component(a__x"))
    (is (str/includes? src "ContainerDb_Ext(db"))
    (is (not (str/includes? src "Person(p")))))
