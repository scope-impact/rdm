(ns rdm-spec.architecture.views-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.architecture.views :as views]))

(def m
  {:system {:id :s :name "S" :description "A system"}
   :people [{:id :p :name "P" :description "A person" :actor #{:human}}]
   :external [{:id :x :name "X" :description "Outside"}]
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The program"
                 :contexts [{:id :a :name "a" :description "A"
                             :components [{:id :a/one :name "One" :spec "a/one" :description "One"}
                                          {:id :a/two :name "Two" :spec "a/two" :description "Two"}]}
                            {:id :b :name "b" :description "B"
                             :components [{:id :b/three :name "Three" :spec "b/three" :description "Three"}]}]}
                {:id :db :name "DB" :kind :stores :technology "files" :description "Store"}]
   :relationships [{:from :p :to :app :description "Uses"}
                   {:from :a/one :to :db :description "Writes to"}
                   {:from :a/one :to :b/three :description "Publishes" :event :Done}
                   {:from :a/two :to :x :description "Calls"}]
   :views [{:key "context" :level :context :description "Context"}
           {:key "containers" :level :container :description "Containers"}
           {:key "a-components" :level :component :context :a :description "A"}
           {:key "flow" :level :dynamic :description "Flow" :steps [[:p :app "1. Asks"] [:a/one :db "2. Writes"]]}]})

(defn- contents [k] (views/view-contents m (first (filter #(= k (:key %)) (:views m)))))

(deftest what-each-level-holds
  (is (= [:s :p :x] (map :id (:elements (contents "context")))))
  (is (= #{[:p :s] [:s :x]} (set (map (juxt first second) (:rels (contents "context"))))))
  (is (= [:app :db :p :x] (map :id (:elements (contents "containers")))))
  (is (= #{[:p :app] [:app :db] [:app :x]} (set (map (juxt first second) (:rels (contents "containers"))))))
  (let [c (contents "a-components")]
    (is (= [:a/one :a/two] (map :id (:inside c))))
    (is (= [:db :b/three :x] (map :id (remove (set (:inside c)) (:elements c)))))
    (is (every? :ext? (remove (set (:inside c)) (:elements c))))
    (is (some #(= [:a/one :b/three "Publishes Done"] %) (:rels c))))
  (is (= [:p :app :a/one :db] (map :id (:elements (contents "flow")))))
  (is (= ["1. Asks" "2. Writes"] (map #(nth % 2) (:rels (contents "flow"))))))

(deftest mermaid-records-the-hash
  (let [src (views/mermaid m (first (:views m)) (apply str (repeat 64 "a")) "dhf/spec/model.edn")]
    (is (str/starts-with? src (str "%% model-sha256: " (apply str (repeat 64 "a")))))
    (is (str/includes? src "C4Context"))
    (is (str/includes? src "Person(p, \"P\", \"A person\")"))))

(deftest freshness
  (let [root (str (fs/create-temp-dir {:prefix "views-test-"}))
        h (apply str (repeat 64 "b"))]
    (try
      (is (= #{"view-missing"} (set (map :rule (views/freshness-findings root "v" m h)))))
      (fs/create-dirs (fs/file root "v"))
      (doseq [v (:views m) ext ["mmd" "svg"]]
        (spit (str (fs/file root "v" (str (:key v) "." ext))) (str "%% model-sha256: " h "\nx\n")))
      (is (empty? (views/freshness-findings root "v" m h)))
      (is (= 4 (count (filter #(= "view-stale" (:rule %)) (views/freshness-findings root "v" m (apply str (repeat 64 "c")))))))
      (finally (fs/delete-tree root)))))
