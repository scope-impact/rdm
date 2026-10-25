(ns rdm-spec.architecture.lint-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.architecture.lint :as lint]
            [rdm-spec.architecture.model-test :refer [small]]
            [rdm-spec.kernel.bus :as bus]))

(def catalogue {:E {:published-by :one/b :carries []}})

(def files ["dhf/spec/one/a.feature" "dhf/spec/one/b.feature" "dhf/spec/two/c.feature" "dhf/spec/kernel/k.feature"])

(defn- run
  ([m] (run m {}))
  ([m opts]
   (lint/lint-model "m.edn" m (merge {:catalogue catalogue :view-limit 20 :description-max 45 :spec-dir "dhf/spec"
                                      :feature-files files :exists? (set files)}
                                     opts))))

(defn- the [rule fs] (filter #(= rule (:rule %)) fs))

(def with-two-view (update small :views conj {:key "two" :level :component :context :two :description "Two"}))

(deftest a-sound-model-passes
  (is (= [] (run with-two-view))))

(deftest a-context-with-components-needs-a-component-view
  (let [[f :as fs] (run small)]
    (is (= 1 (count fs)))
    (is (= "a-component-view" (:rule f)))
    (is (str/includes? (:message f) ":two"))))

(deftest every-view-and-element-is-described
  (is (str/includes? (:message (first (the "c4-scope" (run (update-in with-two-view [:views 0] dissoc :description))))) "\"ctx\""))
  (is (str/includes? (:message (first (the "c4-element-described" (run (update-in with-two-view [:containers 1] assoc :description " ")))))
                     ":db"))
  (let [[f] (the "c4-arrow-labelled" (run (update-in with-two-view [:relationships 1] dissoc :description)))]
    (is (str/includes? (:message f) ":app"))
    (is (str/includes? (:message f) ":x"))))

(deftest a-context-view-needs-a-person
  (let [m (-> with-two-view (assoc :people []) (update :relationships #(vec (rest %)))
              (update :views #(vec (butlast (remove (fn [v] (= :dynamic (:level v))) %)))))
        m (update m :views conj {:key "two" :level :component :context :two :description "Two"})]
    (is (= ["c4-context-actors"] (map :rule (run m))))))

(deftest a-container-runs-or-stores
  (let [[f] (run (assoc-in with-two-view [:containers 1 :kind] :library))]
    (is (= "c4-container-runs" (:rule f)))
    (is (str/includes? (:message f) ":db"))
    (is (str/includes? (:message f) ":library"))))

(deftest an-event-is-catalogued-and-published-by-its-publisher
  (let [[f] (run with-two-view {:catalogue {}})]
    (is (= "c4-events-named" (:rule f)))
    (is (str/includes? (:message f) ":one/b -> :two/c"))
    (is (str/includes? (:message f) ":E")))
  (is (= ["c4-events-named"] (map :rule (run with-two-view {:catalogue {:E {:published-by :one/a}}})))))

(deftest a-big-view-warns-with-both-counts
  (let [[f :as fs] (run with-two-view {:view-limit 3})]
    (is (every? #(= :warning (:severity %)) fs))
    (is (= #{"c4-view-size"} (set (map :rule fs))))
    (is (some #(and (str/includes? (:message %) "\"cont\"") (str/includes? (:message %) " 4 ")
                    (str/includes? (:message %) " 3")) fs))))

(deftest a-long-person-or-system-description-fails
  (let [d (apply str (repeat 60 "x"))
        [f] (run (assoc-in with-two-view [:people 0 :description] d))]
    (is (= "c4-description-length" (:rule f)))
    (is (str/includes? (:message f) ":p"))
    (is (str/includes? (:message f) "60"))
    (is (str/includes? (:message f) "45")))
  (is (= [] (run (assoc-in with-two-view [:containers 1 :description] (apply str (repeat 60 "x")))))
      "only people and systems"))

(deftest components-own-feature-files-and-feature-files-have-owners
  (let [fs (run with-two-view {:feature-files (conj files "dhf/spec/one/z.feature") :exists? (set (rest files))})]
    (is (= #{"a-scenario-owner"} (set (map :rule fs))))
    (is (some #(and (str/includes? (:message %) ":one/a") (str/includes? (:message %) "dhf/spec/one/a.feature")) fs))
    (is (some #(= "dhf/spec/one/z.feature" (:file %)) fs))))

(deftest contexts-relate-only-through-events
  (let [[f] (run (update with-two-view :relationships conj {:from :two/c :to :one/a :description "Calls"}))]
    (is (= "a-contract-only" (:rule f)))
    (is (str/includes? (:message f) ":two/c -> :one/a")))
  (is (= [] (run (update with-two-view :relationships conj {:from :one/a :to :kernel/k :description "Uses"})))
      "the kernel is in no context"))

(deftest the-check-stage-reads-the-model-and-lists-what-a-person-judges
  (let [dir (str (fs/create-temp-dir))
        req {:root dir :config {:model "dhf/spec/model.edn" :spec-dir "dhf/spec" :c4 {:view-element-limit 20}}
             :state (atom {}) :bus (bus/make-bus)}]
    (fs/create-dirs (fs/file dir "dhf/spec/one"))
    (fs/create-dirs (fs/file dir "dhf/spec/two"))
    (fs/create-dirs (fs/file dir "dhf/spec/kernel"))
    (spit (str (fs/file dir "dhf/spec/model.edn")) (pr-str with-two-view))
    (doseq [f files] (spit (str (fs/file dir f)) "Feature: x\n"))
    (doseq [[e h] (lint/subscriptions req)] (bus/subscribe! (:bus req) e :architecture/lint h))
    (bus/publish! (:bus req) :spec/parser :SpecParsed {:scenarios [{:file "dhf/spec/z.feature"}]})
    (let [{:keys [result findings]} (lint/check req)]
      (is (= ["c4-events-named" "a-scenario-owner"] (map :rule findings))
          "no catalogue declares the event, and SpecParsed names a feature file no component owns")
      (is (= "dhf/spec/z.feature" (:file (second findings))))
      (is (= 3 (count (get-in result [:lint :for-a-person]))))
      (is (false? (get-in result [:lint :passed?]))))))
