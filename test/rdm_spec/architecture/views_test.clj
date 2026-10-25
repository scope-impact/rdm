(ns rdm-spec.architecture.views-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as command]))

(def room
  (-> (io/resource "rdm_spec/kernel/command.clj") io/file fs/absolutize
      fs/parent fs/parent fs/parent fs/parent str))

(def config (edn/read-string (slurp (str (fs/file room "dhf/harness/config.edn")))))

(def room-model (:model (model/read-model-file (fs/file room "dhf/spec/model.edn") "dhf/spec/model.edn")))

(defn- view [k] (first (filter #(= k (:key %)) (:views room-model))))

(defn- ids [els] (set (map :id els)))

(def small
  {:system {:id :sys :name "Sys" :description "The system"}
   :people [{:id :alice :name "Alice" :description "Uses it"}]
   :external [{:id :ext :name "Ext" :description "Another system"}
              {:id :lonely :name "Lonely" :description "Relates to nothing"}]
   :containers [{:id :app :name "App" :kind :runs :technology "t" :description "Runs"
                 :contexts [{:id :a :name "a" :description "A"
                             :components [{:id :a/one :name "One" :description "1" :spec "a/one"}
                                          {:id :a/two :name "Two" :description "2" :spec "a/two"}]}
                            {:id :b :name "b" :description "B"
                             :components [{:id :b/three :name "Three" :description "3" :spec "b/three"}]}]}
                {:id :db :name "Db" :kind :stores :technology "git" :description "Stores"}]
   :kernel [{:id :kernel/bus :name "Bus" :description "Bus" :spec "kernel/bus"}]
   :relationships [{:from :alice :to :app :description "Uses"}
                   {:from :alice :to :ext :description "Talks to"}
                   {:from :a/one :to :db :description "Writes to"}
                   {:from :a/two :to :db :description "Also writes to"}
                   {:from :a/one :to :a/two :description "Calls"}
                   {:from :b/three :to :a/one :description "Publishes" :event :Ev}
                   {:from :a/two :to :kernel/bus :description "Publishes on"}
                   {:from :app :to :ext :description "Calls"}]
   :views [{:key "ctx" :level :context :description "Context"}
           {:key "cont" :level :container :description "Containers"}
           {:key "a-comp" :level :component :context :a :description "A"}
           {:key "dyn" :level :dynamic :description "Dyn"
            :steps [[:alice :app "1. Starts"] [:a/one :db "2. Writes"] [:alice :app "3. Again"]]}]})

(defn- small-view [k] (first (filter #(= k (:key %)) (:views small))))

(deftest a-context-view-holds-the-system-people-and-external-systems
  (let [{:keys [inside outside rels boundary]} (views/view-content small (small-view "ctx"))]
    (is (nil? boundary))
    (is (= #{:sys} (ids inside)))
    (is (= #{:alice :ext :lonely} (ids outside)))
    (testing "ends inside the system are drawn as the system, once per pair"
      (is (= #{[:alice :sys] [:alice :ext] [:sys :ext]} (set (map (juxt :from :to) rels))))
      (is (= (count rels) (count (set (map (juxt :from :to) rels))))))))

(deftest a-container-view-holds-every-container-person-and-external-system
  (let [{:keys [inside outside rels boundary]} (views/view-content small (small-view "cont"))]
    (is (= :sys (:id boundary)))
    (is (= #{:app :db} (ids inside)))
    (is (= #{:alice :ext :lonely} (ids outside)))
    (testing "a direct relationship keeps its arrows; lifted ones join a pair once"
      (is (= #{[:alice :app] [:alice :ext] [:app :db] [:app :ext]} (set (map (juxt :from :to) rels))))
      (is (= 1 (count (filter #(= [:app :db] [(:from %) (:to %)]) rels)))))
    (is (= 5 (views/element-count small (small-view "cont"))))))

(deftest a-component-view-holds-one-context-and-what-it-relates-to
  (let [{:keys [inside outside rels boundary]} (views/view-content small (small-view "a-comp"))]
    (is (= :a (:id boundary)))
    (is (= #{:a/one :a/two} (ids inside)))
    (is (= #{:db :b/three :kernel/bus} (ids outside)))
    (is (= 5 (count rels)))
    (is (not-any? #(= :app (:from %)) rels))))

(deftest a-dynamic-view-holds-its-steps-in-order
  (let [{:keys [inside rels]} (views/view-content small (small-view "dyn"))]
    (is (= [:alice :app :a/one :db] (mapv :id inside)))
    (is (= ["1. Starts" "2. Writes" "3. Again"] (mapv :description rels)))))

(deftest the-source-records-both-hashes-on-its-first-line
  (let [h {:bytes-hash (apply str (repeat 64 "a")) :value-hash (apply str (repeat 64 "b")) :model-file "m.edn"}
        src (views/source small (small-view "a-comp") h)
        lines (str/split-lines src)]
    (is (str/includes? (first lines) (str "model-sha256: " (:bytes-hash h))))
    (is (= {:bytes (:bytes-hash h) :value (:value-hash h)} (views/recorded-hashes src)))
    (is (= "C4Component" (second lines)))
    (is (some #(str/starts-with? % "Boundary(boundary__a, \"a\"") lines))
    (is (some #(str/starts-with? % "ContainerDb_Ext(db,") lines))
    (is (some #(str/starts-with? % "Component_Ext(b__three,") lines))
    (is (some #(= % "Rel(b__three, a__one, \"Publishes Ev\")") lines))))

(deftest a-quote-in-a-description-never-breaks-the-source
  (let [m (assoc-in small [:people 0 :description] "Says \"hi\"\nand more")
        src (views/source m (small-view "ctx") {:bytes-hash "x" :value-hash "y" :model-file "m"})]
    (is (str/includes? src "Person(alice, \"Alice\", \"Says 'hi' and more\")"))))

(deftest freshness-takes-either-recorded-hash
  (let [h {:bytes-hash (apply str (repeat 64 "1")) :value-hash (apply str (repeat 64 "2"))}
        line (fn [b v] (str "%% " (views/hash-line-text b v) "\nC4Context\n"))
        other (apply str (repeat 64 "3"))]
    (is (views/fresh? (line (:bytes-hash h) other) h))
    (is (views/fresh? (line other (:value-hash h)) h) "a reformatted model keeps its value hash")
    (is (not (views/fresh? (line other other) h)))
    (is (not (views/fresh? (str "C4Context\n%% " (views/hash-line-text (:bytes-hash h) (:value-hash h))) h))
        "a hash anywhere but the first line is not recorded")))

(deftest element-count-counts-no-boundary
  (is (= 8 (views/element-count room-model (view "context"))))
  (is (= (+ 2 5 2) (views/element-count room-model (view "containers")))))

(deftest every-view-of-the-room-stays-under-the-limit
  (doseq [v (:views room-model)]
    (is (<= (views/element-count room-model v) (get-in config [:c4 :view-element-limit])) (:key v))))

(deftest views-and-check-agree-on-a-project
  (when (fs/which views/renderer)
    (let [dir (str (fs/create-temp-dir {:prefix "views-test-"}))
          w (fn [rel text] (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit f text)))]
      (w "dhf/harness/config.edn" (pr-str config))
      (w "dhf/spec/model.edn" (pr-str small))
      (w "dhf/spec/events.edn" "{:Ev {:published-by :b/three :carries []}}")
      (w "dhf/views/gone.mmd" "%% generated by rdm-spec views; old\n")
      (w "dhf/views/mine.mmd" "hand-written\n")
      (let [req (fn [] {:root dir :config config :state (atom {})})
            out (views/views (req))]
        (is (empty? (:findings out)) (pr-str (:findings out)))
        (is (= ["ctx" "cont" "a-comp" "dyn"] (mapv :key (get-in out [:result :views]))))
        (is (= ["dhf/views/gone.mmd"] (get-in out [:result :removed])))
        (is (fs/exists? (fs/file dir "dhf/views/mine.mmd")) "a file views did not write is never removed")
        (is (str/starts-with? (slurp (str (fs/file dir "dhf/views/ctx.svg"))) "<!-- model-sha256: "))
        (is (empty? (:findings (views/check (req)))))
        (testing "reformatting keeps the views fresh"
          (spit (str (fs/file dir "dhf/spec/model.edn")) (str ";; a comment\n" (pr-str small) "\n"))
          (is (empty? (:findings (views/check (req))))))
        (testing "a changed value makes every view stale, naming each output"
          (spit (str (fs/file dir "dhf/spec/model.edn")) (pr-str (assoc-in small [:system :description] "Changed")))
          (let [fs* (:findings (views/check (req)))]
            (is (= 8 (count fs*)))
            (is (every? #(= "views-stale" (:rule %)) fs*))
            (is (str/includes? (command/format-finding (first fs*)) "view \"ctx\""))))
        (testing "a missing output is named"
          (fs/delete (fs/file dir "dhf/views/dyn.svg"))
          (is (some #(and (= "views-missing" (:rule %)) (= "dhf/views/dyn.svg" (:file %)))
                    (:findings (views/check (req))))))))))

(deftest a-refused-model-writes-nothing
  (let [dir (str (fs/create-temp-dir {:prefix "views-test-"}))]
    (spit (str (fs/file (doto (fs/file dir "dhf/spec") fs/create-dirs) "model.edn")) "{:views [")
    (let [out (views/views {:root dir :config config :state (atom {})})]
      (is (seq (:findings out)))
      (is (not (fs/exists? (fs/file dir "dhf/views")))))
    (is (nil? (views/check {:root dir :config config :state (atom {})})))))
