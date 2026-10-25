(ns rdm-spec.architecture.model-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.model :as model]))

(def small
  {:system {:id :s :name "S" :description "A system"}
   :people [{:id :p :name "P" :description "A person" :actor #{:human :agent}}]
   :external [{:id :x :name "X" :description "An external system"}]
   :containers [{:id :app :name "App" :kind :runs :technology "t" :description "Runs"
                 :contexts [{:id :one :name "one" :description "First"
                             :components [{:id :one/a :name "A" :spec "one/a" :description "a"}
                                          {:id :one/b :name "B" :spec "one/b" :description "b"}]}
                            {:id :two :name "two" :description "Second"
                             :components [{:id :two/c :name "C" :spec "two/c" :description "c"}]}]}
                {:id :db :name "DB" :kind :stores :technology "files" :description "Stores"}]
   :kernel [{:id :kernel/k :name "K" :spec "kernel/k" :description "k"}]
   :relationships [{:from :p :to :app :description "Uses"}
                   {:from :app :to :x :description "Calls"}
                   {:from :one/a :to :one/b :description "Asks"}
                   {:from :one/b :to :two/c :description "Publishes" :event :E}
                   {:from :two/c :to :db :description "Writes to"}
                   {:from :x :to :db :description "Feeds"}
                   {:from :one/a :to :kernel/k :description "Runs on"}]
   :views [{:key "ctx" :level :context :description "Context"}
           {:key "cont" :level :container :description "Containers"}
           {:key "one" :level :component :context :one :description "One"}
           {:key "dyn" :level :dynamic :description "Dyn" :steps [[:p :app "1. a"] [:one/b :two/c "2. b"]]}]})

;; ---------------------------------------------------------------------------
;; The model hash

(deftest the-hash-ignores-key-and-set-order-and-formatting
  (let [a (edn/read-string "{:b 1 :a #{:z :y {:k 1 :j 2}}}")
        b (edn/read-string ";; c\n{:a #{{:j 2 :k 1} :y :z}\n\n :b 1}")]
    (is (= (model/model-hash a) (model/model-hash b)))
    (is (re-matches #"[0-9a-f]{64}" (model/model-hash a)))
    (is (not= (model/model-hash a) (model/model-hash (assoc a :b 2))))))

(deftest the-hash-is-the-sha256-of-the-sorted-print
  (is (= (str "{:a #{:x :y}, :b/c 1}")
         (binding [*print-namespace-maps* false] (pr-str (model/canonical {:b/c 1 :a #{:y :x}})))))
  (is (= (model/model-hash {:a 1})
         (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes "{:a 1}" "UTF-8"))]
           (apply str (map #(format "%02x" (bit-and % 0xff)) d))))))

(deftest a-recorded-hash-is-read-from-the-first-line-only
  (let [h (apply str (repeat 64 "a"))]
    (is (= h (model/recorded-hash (str "%% model-sha256: " h "; x\nC4Context"))))
    (is (= h (model/recorded-hash (str "<!-- model-sha256: " h " -->\n<svg/>"))))
    (is (nil? (model/recorded-hash (str "C4Context\n%% model-sha256: " h))))))

;; ---------------------------------------------------------------------------
;; Reading the text

(deftest an-unclosed-map-is-named-at-the-line-it-opens
  (is (= {:kind :unclosed :line 3 :shown "{"}
         (model/bracket-problem "{:a [{:x 1}\n     {:y 2}\n     {:z 3\n     {:w 4}]\n :b 1}")))
  (is (= {:kind :unclosed :line 1 :shown "{"} (model/bracket-problem "{:a 1\n:b 2"))))

(deftest a-closer-that-closes-nothing-is-named-at-its-line
  (is (= {:kind :stray :line 3 :shown "}"}
         (model/bracket-problem "{:a [{:x 1}\n     {:y 2}\n     }\n     {:w 4}]\n :b 1}")))
  (is (= {:kind :stray :line 2 :shown "]"} (model/bracket-problem "{:a 1}\n]"))))

(deftest brackets-in-strings-comments-and-characters-are-not-brackets
  (is (nil? (model/bracket-problem "{:a \"}{[\" ;; ]]]\n :b \\{ :c #{1}}")))
  (is (= [1 2 2] (map :line (filter :open? (model/brackets "{:a\n \"x\\\"]\" [#{"))))))

(deftest parse-text-names-the-file-and-line
  (let [r (model/parse-text "m.edn" ";; one\n{:a [1 2\n :b 3}\n")]
    (is (nil? (:model r)))
    (is (= [["m.edn" 2 "model-edn"]] (map (juxt :file :line :rule) (:findings r))))
    (is (str/includes? (:message (first (:findings r))) "\"[\" opened on line 2 is never closed")))
  (is (= ["model-edn"] (map :rule (:findings (model/parse-text "m.edn" "{:a 1} {:b 2}")))))
  (is (= ["model-edn"] (map :rule (:findings (model/parse-text "m.edn" ";; nothing")))))
  (let [r (model/parse-text "m.edn" "{:people\n [{:id :p}]}")]
    (is (= {:people [{:id :p}]} (:model r)))
    (is (= 2 (model/line-of (first (:people (:model r))))))))

;; ---------------------------------------------------------------------------
;; The shape, the ids, the ends

(defn- rules [fs] (set (map :rule fs)))

(deftest a-well-shaped-model-has-no-finding
  (is (= [] (model/check-model "m.edn" small))))

(deftest an-undeclared-key-is-named-with-its-element
  (let [m (update-in small [:containers 1] assoc :owner "me")
        [f :as fs] (model/check-model "m.edn" m)]
    (is (= 1 (count fs)))
    (is (= "model-shape" (:rule f)))
    (is (str/includes? (:message f) ":db"))
    (is (str/includes? (:message f) ":owner"))
    (is (not (contains? f ::model/undeclared-key))))
  (is (= #{"model-shape"} (rules (model/check-model "m.edn" (assoc small :colour "red"))))))

(deftest a-wrong-kind-of-value-stops-the-reading
  (is (= #{"model-shape"} (rules (model/check-model "m.edn" (assoc small :people {:id :p})))))
  (is (= #{"model-shape"} (rules (model/check-model "m.edn" (assoc-in small [:views 0 :level] :code)))))
  (is (= #{"model-shape"} (rules (model/check-model "m.edn" (assoc-in small [:views 3 :steps] [[:p]]))))))

(deftest an-id-used-twice-names-both-elements
  (let [m (update small :containers conj {:id :one/a :name "Again" :kind :stores :description "d"})
        [f] (model/check-model "m.edn" m)]
    (is (= "model-duplicate-id" (:rule f)))
    (is (str/includes? (:message f) ":one/a"))
    (is (str/includes? (:message f) "component \"A\""))
    (is (str/includes? (:message f) "container \"Again\""))))

(deftest a-relationship-to-nothing-is-named
  (let [[f] (model/check-model "m.edn" (update small :relationships conj {:from :one :to :archive :description "d"}))]
    (is (= "model-unknown-id" (:rule f)))
    (is (str/includes? (:message f) ":one -> :archive"))
    (is (str/includes? (:message f) "names :archive")))
  (is (= #{"model-unknown-id"}
         (rules (model/check-model "m.edn" (assoc-in small [:views 3 :steps 1] [:one/b :nowhere "2. b"])))))
  (is (= #{"model-unknown-id"}
         (rules (model/check-model "m.edn" (assoc-in small [:views 2 :context] :three))))))

(deftest owners-are-read-from-each-spec-path
  (is (= [["one/a" "dhf/spec/one/a.feature"] ["one/b" "dhf/spec/one/b.feature"]
          ["two/c" "dhf/spec/two/c.feature"] ["kernel/k" "dhf/spec/kernel/k.feature"]]
         (map (juxt :spec :file) (model/owners small "dhf/spec")))))

;; ---------------------------------------------------------------------------
;; What a view holds

(defn- content [k]
  (model/view-content small (first (filter #(= k (:key %)) (:views small)))))

(deftest the-context-view-holds-the-system-people-and-externals
  (let [c (content "ctx")]
    (is (= [:s :p :x] (map :id (:elements c))))
    (is (= [[:p :s] [:s :x] [:x :s]] (map (juxt :from :to) (:relationships c))))))

(deftest the-container-view-lifts-components-onto-their-container
  (let [c (content "cont")]
    (is (= [:app :db :p :x] (map :id (:elements c))))
    (is (= [:app :db] (map :id (filter :inside? (:elements c)))))
    (is (= [[:p :app] [:app :x] [:app :db] [:x :db]] (map (juxt :from :to) (:relationships c))))))

(deftest a-direct-relationship-wins-over-a-lifted-one
  (let [m (update small :relationships conj {:from :app :to :db :description "Direct"})
        c (model/view-content m {:key "cont" :level :container :description "c"})]
    (is (= "Direct" (:label (first (filter #(= [:app :db] [(:from %) (:to %)]) (:relationships c))))))))

(deftest the-component-view-draws-what-is-outside-as-external
  (let [c (content "one")]
    (is (= {:id :one :name "one" :kind :context} (:boundary c)))
    (is (= [:one/a :one/b] (map :id (filter :inside? (:elements c)))))
    (is (= [:two/c :kernel/k] (map :id (remove :inside? (:elements c)))))
    (is (every? :external? (remove :inside? (:elements c))))
    (is (= ["Asks" "Publishes E" "Runs on"] (map :label (:relationships c))))))

(deftest the-dynamic-view-holds-its-steps-in-order
  (let [c (content "dyn")]
    (is (= [:p :app :one/b :two/c] (map :id (:elements c))))
    (is (= ["1. a" "2. b"] (map :label (:relationships c))))))

;; ---------------------------------------------------------------------------
;; Loading

(deftest load-reads-once-and-names-a-missing-model
  (let [dir (str (fs/create-temp-dir))
        req {:root dir :config {:model "dhf/spec/model.edn" :events "dhf/spec/events.edn"} :state (atom {})}]
    (let [r (model/load! req)]
      (is (= ["model-edn"] (map :rule (:findings r))))
      (is (= ["model-catalogue"] (map :rule (:catalogue-findings r)))))
    (fs/create-dirs (fs/file dir "dhf/spec"))
    (spit (str (fs/file dir "dhf/spec/model.edn")) (pr-str small))
    (is (= ["model-edn"] (map :rule (:findings (model/load! req)))) "cached for the run")
    (let [r (model/load! (assoc req :state (atom {})))]
      (is (= small (:model r)))
      (is (= (model/model-hash small) (:hash r))))))
