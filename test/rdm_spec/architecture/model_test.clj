(ns rdm-spec.architecture.model-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.model :as model]))

(def room
  (-> (io/resource "rdm_spec/kernel/command.clj") io/file fs/absolutize
      fs/parent fs/parent fs/parent fs/parent str))

(defn- read-text [text]
  (let [f (fs/create-temp-file {:suffix ".edn"})]
    (spit (str f) text)
    (model/read-model-file (fs/file (str f)) "dhf/spec/model.edn")))

(def small
  "{:system {:id :sys :name \"Sys\" :description \"d\"}
 :people [{:id :p :name \"P\" :description \"d\" :actor #{:human}}]
 :containers [{:id :sys :name \"Sys\" :kind :runs :description \"d\"
               :contexts [{:id :c :name \"c\" :description \"d\"
                           :components [{:id :c/x :name \"X\" :spec \"c/x\" :description \"d\"}]}]}]
 :relationships [{:from :p :to :sys :description \"Uses\"}]
 :views [{:key \"context\" :level :context :description \"d\"}]}")

(deftest reads-a-valid-model
  (let [{:keys [model findings]} (read-text small)]
    (is (empty? findings))
    (is (= :sys (get-in model [:system :id])))))

(deftest room-model-is-read-whole
  (let [{:keys [model findings]} (model/read-model-file (fs/file room "dhf/spec/model.edn") "dhf/spec/model.edn")]
    (is (empty? findings) (pr-str findings))
    (is (map? model))))

(deftest refuses-what-is-not-edn-at-the-opening-line
  (let [{:keys [model findings]} (read-text (str ";; one\n;; two\n{:system {:id :x}\n :people []\n"))]
    (is (nil? model))
    (is (= 3 (:line (first findings))))
    (is (= "model-edn" (:rule (first findings))))))

(deftest refuses-an-undeclared-key
  (let [{:keys [model findings]} (read-text (str/replace small ":kind :runs" ":kind :runs :owner \"me\""))]
    (is (nil? model))
    (is (some #(and (= "model-shape" (:rule %)) (str/includes? (:message %) ":owner")
                    (str/includes? (:message %) "container :sys"))
              findings))))

(deftest refuses-an-id-used-twice-but-allows-the-system-container-pun
  (testing "the system and its one container may share an id"
    (is (empty? (:findings (read-text small)))))
  (let [{:keys [findings]} (read-text (str/replace small ":id :c/x" ":id :c"))]
    (is (some #(and (= "model-id" (:rule %)) (str/includes? (:message %) "context :c")
                    (str/includes? (:message %) "component :c"))
              findings))))

(deftest refuses-a-reference-to-nothing
  (let [{:keys [findings]} (read-text (str/replace small ":to :sys" ":to :archive"))]
    (is (some #(and (= "model-reference" (:rule %)) (str/includes? (:message %) ":archive")) findings))))

(deftest hash-follows-the-value-not-the-text
  (let [m1 (:model (read-text small))
        m2 (:model (read-text (str ";; a comment\n" (str/replace small "\n" "\n\n  "))))
        m3 (:model (read-text (str/replace small "\"Uses\"" "\"Runs\"")))]
    (is (= (model/model-hash m1) (model/model-hash m2)))
    (is (not= (model/model-hash m1) (model/model-hash m3)))))
