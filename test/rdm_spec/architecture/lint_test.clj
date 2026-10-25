(ns rdm-spec.architecture.lint-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.lint :as lint]
            [rdm-spec.architecture.model :as model]))

(def room
  (-> (io/resource "rdm_spec/kernel/command.clj") io/file fs/absolutize
      fs/parent fs/parent fs/parent fs/parent str))

(def config (edn/read-string (slurp (str (fs/file room "dhf/harness/config.edn")))))

(defn- req [] {:root room :config config :state (atom {})})

(defn- rules [findings] (set (map :rule findings)))

(defn- lint-of [m]
  (let [r (req)]
    (lint/lint r {:model m :catalogue (edn/read-string (slurp (str (fs/file room "dhf/spec/events.edn"))))
                  :file "dhf/spec/model.edn" :events-file "dhf/spec/events.edn"})))

(def room-model (:model (model/read-model-file (fs/file room "dhf/spec/model.edn") "dhf/spec/model.edn")))

(deftest the-room-model-passes-the-lint
  (let [fs* (lint-of room-model)]
    (is (empty? fs*) (pr-str fs*))))

(deftest the-check-stage-lists-what-a-person-judges
  (let [{:keys [result findings]} (lint/check (req))]
    (is (empty? findings))
    (is (= 3 (count (get-in result [:c4-for-a-person :questions]))))))

(deftest each-rule-fires
  (testing "c4-scope"
    (is ((rules (lint-of (update room-model :views #(assoc-in % [0 :description] nil)))) "c4-scope")))
  (testing "c4-container-runs"
    (is ((rules (lint-of (assoc-in room-model [:containers 1 :kind] :library))) "c4-container-runs")))
  (testing "c4-events-named: an event from the wrong publisher"
    (is ((rules (lint-of (update room-model :relationships conj
                                 {:from :spec/check :to :publishing/site :description "Publishes" :event :SpecParsed})))
         "c4-events-named")))
  (testing "a-contract-only, never for a kernel element"
    (is ((rules (lint-of (update room-model :relationships conj
                                 {:from :spec/check :to :publishing/site :description "Feeds"})))
         "a-contract-only"))
    (is (not ((rules (lint-of (update room-model :relationships conj
                                      {:from :spec/check :to :kernel/bus :description "Publishes on"})))
              "a-contract-only"))))
  (testing "c4-view-size is a warning"
    (let [m (update room-model :external into (for [i (range 20)] {:id (keyword (str "x" i)) :name "X" :description "x"}))
          size (filter #(= "c4-view-size" (:rule %)) (lint-of m))]
      (is (seq size))
      (is (every? #(= :warning (:severity %)) size)))))
