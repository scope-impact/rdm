(ns rdm-spec.architecture.model-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.model :as model]))

(def m
  {:system {:id :s :name "S" :description "A system"}
   :people [{:id :p :name "P" :description "A person" :actor #{:human}}]
   :external []
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The program"
                 :contexts [{:id :c :name "c" :description "A context"
                             :components [{:id :c/one :name "One" :spec "c/one" :description "One"}]}]}]
   :relationships [{:from :p :to :app :description "Uses"}]
   :views [{:key "context" :level :context :description "Context"}]})

(defn- read-text [text]
  (let [root (str (fs/create-temp-dir {:prefix "model-test-"}))]
    (try (spit (str (fs/file root "model.edn")) text)
         (model/read-model root "model.edn")
         (finally (fs/delete-tree root)))))

(deftest brackets
  (is (nil? (model/bracket-error "{:a [1 2] :b \"}\" :c #{3} :d \\}} ; }\n")))
  (is (= 3 (:line (model/bracket-error "{:a 1}\n\n}\n"))) "a closer that closes nothing names its line")
  (is (str/includes? (:message (model/bracket-error "{:a 1}\n\n}\n")) "\"}\""))
  (is (= 2 (:line (model/bracket-error "{:a\n [{:b 1\n  {:c 2}\n ]}\n"))) "an unclosed map names the line it opened on")
  (is (= 2 (:line (model/bracket-error "{:a 1\n :b {:c 1\n")))))

(defn- refusals [model] (set (map :rule (:findings (read-text (pr-str model))))))

(deftest reading
  (is (= m (:model (read-text (pr-str m)))))
  (is (= "model-unreadable" (:rule (first (:findings (read-text "{:a 1} {:b 2}"))))))
  (is (= #{"model-key-undeclared"} (refusals (update-in m [:containers 0] assoc :owner "x"))))
  (is (= #{"model-id-duplicate"} (refusals (update m :external conj {:id :p :name "X" :description "d"}))))
  (is (= #{"model-unknown-element"} (refusals (update m :relationships conj {:from :p :to :archive :description "x"})))))

(deftest the-model-hash-ignores-formatting
  (is (= (model/model-hash m) (model/model-hash (read-string (str/replace (pr-str m) ", " " ; a comment\n  ")))))
  (is (not= (model/model-hash m) (model/model-hash (assoc-in m [:system :description] "Another"))))
  (testing "maps are printed with their entries sorted"
    (is (= "{:a 1, :b #{:x :y}}" (model/canonical {:b #{:y :x} :a 1})))))
