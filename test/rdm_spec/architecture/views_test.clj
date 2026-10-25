(ns rdm-spec.architecture.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.views :as views]))

(def m
  {:system {:id :sys :name "Sys" :description "The system"}
   :people [{:id :eng :name "Engineer" :description "Writes"}]
   :external [{:id :host :name "Host" :description "Hosts"}]
   :containers [{:id :app :name "App" :kind :runs :technology "cli" :description "Runs"
                 :contexts [{:id :a :name "a" :description "A"
                             :components [{:id :a/one :name "One" :spec "a/one" :description "First"}
                                          {:id :a/two :name "Two" :spec "a/two" :description "Second"}]}
                            {:id :b :name "b" :description "B"
                             :components [{:id :b/three :name "Three" :spec "b/three" :description "Third"}]}]}
                {:id :store :name "Store" :kind :stores :technology "git" :description "Stores"}]
   :kernel [{:id :kernel/bus :name "Bus" :spec "kernel/bus" :description "Delivers"}]
   :relationships [{:from :eng :to :app :description "Uses"}
                   {:from :app :to :host :description "Tags in"}
                   {:from :app :to :store :description "Writes to"}
                   {:from :a/one :to :a/two :description "Calls"}
                   {:from :a/one :to :b/three :description "Publishes" :event :Done}
                   {:from :a/two :to :store :description "Reads"}
                   {:from :b/three :to :kernel/bus :description "Uses"}]
   :views [{:key "context" :level :context :description "c"}
           {:key "containers" :level :container :description "k"}
           {:key "a-components" :level :component :context :a :description "a"}
           {:key "flow" :level :dynamic :description "f"
            :steps [[:eng :app "1. Runs"] [:a/one :store "2. Writes"]]}]})

(defn- view [k] (first (filter #(= k (:key %)) (:views m))))
(defn- ids [els] (set (map :id els)))
(defn- pairs [c] (set (map (juxt :from :to) (:rels c))))

(deftest the-hash-is-of-the-value-printed-with-sorted-keys
  (let [h (views/model-hash {:b 1 :a {:d 2 :c 3}})]
    (is (re-matches #"[0-9a-f]{64}" h))
    (testing "exactly the glossary's definition"
      (is (= h (views/sha256-hex "{:a {:c 3, :d 2}, :b 1}"))))
    (is (= h (views/model-hash-of-text ";; a comment\n{:a {:d 2\n     :c 3}\n :b 1}")) "formatting and key order change nothing")
    (is (not= h (views/model-hash {:b 2 :a {:d 2 :c 3}})) "a value changes it")
    (is (nil? (views/model-hash-of-text "{:a")) "no EDN, no hash")))

(deftest the-first-line-records-the-hash
  (let [h (views/model-hash m)]
    (is (= h (views/recorded-hash (views/source-header h "dhf/spec/model.edn" "context"))))
    (is (= h (views/recorded-hash (str (views/render-header h "context") "\n<svg/>"))))
    (is (nil? (views/recorded-hash (str "%% generated\n%% model-sha256: " h))) "only the first line counts")
    (is (nil? (views/recorded-hash "%% model-sha256: abc")) "a short hash is none")
    (is (not (str/includes? (views/render-header h "a--b") "a--b")) "no -- inside an XML comment")))

(deftest a-context-view-holds-the-system-people-and-externals
  (let [c (views/view-content m (view "context"))]
    (is (= #{:sys} (ids (:inside c))))
    (is (= #{:eng :host} (ids (:outside c))))
    (is (= #{[:eng :sys] [:sys :host]} (pairs c)) "lifted to the system, no self-relationship")
    (is (= 3 (views/element-count m (view "context"))))))

(deftest a-container-view-holds-the-containers-people-and-externals
  (let [c (views/view-content m (view "containers"))]
    (is (= #{:app :store} (ids (:inside c))))
    (is (= #{:eng :host} (ids (:outside c))))
    (is (= #{[:eng :app] [:app :host] [:app :store]} (pairs c)))
    (is (= 4 (views/element-count m (view "containers"))))
    (is (= "Writes to" (:label (first (filter #(= [:app :store] ((juxt :from :to) %)) (:rels c)))))
        "a relationship the model states keeps its own label over lifted ones")))

(deftest a-component-view-holds-one-context-and-what-it-relates-to
  (let [c (views/view-content m (view "a-components"))]
    (is (= #{:a/one :a/two} (ids (:inside c))))
    (is (= #{:b/three :store} (ids (:outside c))))
    (is (every? :external? (:outside c)))
    (is (= #{[:a/one :a/two] [:a/one :b/three] [:a/two :store]} (pairs c)))
    (is (some #(= "Publishes Done" (:label %)) (:rels c)) "an event is named on its arrow")))

(deftest a-dynamic-view-holds-its-steps-in-order
  (let [c (views/view-content m (view "flow"))]
    (is (= #{:eng :app :a/one :store} (ids (concat (:inside c) (:outside c)))))
    (is (= [[:eng :app "1. Runs"] [:a/one :store "2. Writes"]]
           (mapv (juxt :from :to :label) (:rels c))))))

(deftest the-source-is-c4-mermaid
  (let [h (views/model-hash m)
        src (views/source m (view "a-components") h "dhf/spec/model.edn")
        lines (str/split-lines src)]
    (is (str/starts-with? (first lines) (str "%% model-sha256: " h)))
    (is (= "C4Component" (second lines)))
    (is (some #(re-find #"^\s*Component\(a__one, \"One\"" %) lines))
    (is (some #(re-find #"^Component_Ext\(b__three" %) lines))
    (is (some #(re-find #"^ContainerDb_Ext\(store" %) lines))
    (is (some #(= "Rel(a__one, b__three, \"Publishes Done\")" %) lines))))

(deftest freshness-names-each-stale-or-missing-output
  (let [h (views/model-hash m)
        other (apply str (repeat 64 "1"))
        outputs {"v/context.mmd" (views/source-header h "x" "context")
                 "v/context.svg" (views/render-header other "context")
                 "v/containers.mmd" "C4Container"}
        fs* (views/freshness-findings m h "v" outputs)
        by (fn [rule] (set (map :file (filter #(= rule (:rule %)) fs*))))]
    (is (= #{"v/context.svg" "v/containers.mmd"} (by "views-stale")))
    (is (= #{"v/containers.svg" "v/a-components.mmd" "v/a-components.svg" "v/flow.mmd" "v/flow.svg"}
           (by "views-missing")))
    (is (some #(str/includes? (:message %) "view \"context\"") fs*))))
