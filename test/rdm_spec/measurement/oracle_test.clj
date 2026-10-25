(ns rdm-spec.measurement.oracle-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.oracle :as oracle]))

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "oracle-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest differences-name-the-place-and-both-values
  (is (= [] (oracle/differences {:a [1 2]} {:a [1 2]})))
  (is (= [{:at [:items "spec-0002" :verdict] :golden :pass :actual :fail}]
         (oracle/differences {:items [{:id "spec-0001" :verdict :pass} {:id "spec-0002" :verdict :pass}]}
                             {:items [{:id "spec-0001" :verdict :pass} {:id "spec-0002" :verdict :fail}]})))
  (is (= [{:at [:better] :golden 0 :actual 1}] (oracle/differences {:better 0} {:better 1})))
  (is (= [{:at [:items "spec-0003"] :golden :rdm-spec.measurement.oracle/absent :actual {:id "spec-0003"}}]
         (oracle/differences {:items [{:id "spec-0001"}]} {:items [{:id "spec-0001"} {:id "spec-0003"}]}))))

(deftest the-shape-is-checked
  (is (empty? (oracle/shape-findings {:project {"a.txt" "a"} :cycles {"01" {:cycle {:cut "spec-1"}}}
                                      :checklist {} :scorer {} :scoreboard {"01-02" {}} :recorded []})))
  (is (= #{"oracle-malformed"}
         (set (map :rule (oracle/shape-findings {:project {"../escape" "x"} :cycles {"01" {}}
                                                 :scoreboard {"0102" {}} :recorded {}}))))))

;; --- one fixture cycle, read from and recorded into a local layout ----------

(def ^:private project
  {"dhf/harness/config.edn" (pr-str {:id-prefix "spec" :spec-dir "dhf/spec" :model "dhf/spec/model.edn"
                                     :needs "dhf/spec/needs.edn" :cycles-dir "dhf/cycles"})
   "dhf/spec/model.edn" (pr-str {:system {:id :s :name "S" :description "d"} :people [] :external []
                                 :containers [{:id :c :name "c" :kind :runs :technology "t" :description "d"
                                               :contexts [{:id :demo :name "demo" :description "d"
                                                           :components [{:id :demo/alpha :name "Alpha"
                                                                         :spec "demo/alpha" :description "d"}]}]}]
                                 :relationships [] :views []})
   "dhf/spec/needs.edn" "{\"UN-001\" {:text \"t\" :sources []}}\n"
   "dhf/spec/demo/alpha.feature" (str "@UN-001\nFeature: Alpha\n  A fixture.\n\n"
                                      "  @spec-0001 @e2e\n  Scenario: First\n    Given a fixture\n")})

(def ^:private roles {:builder {:name "b" :actor :agent} :grader {:name "g" :actor :agent}
                      :spec-owner {:name "o" :actor :human}})

(def ^:private fixture
  {:project project
   :cycles {"01" {:cycle (merge {:cut "spec-1" :scope :project} roles)
                  :ratified {:cut "spec-1" :room-tree "t" :outcome :passed}
                  :verdicts [{:id "spec-0001" :verdict :pass :evidence "seen"}]}}
   :checklist {"01" {:cycle "01" :cut "spec-1" :planned []
                     :items [{:id "spec-0001" :title "First" :context "demo" :component "Alpha" :level "e2e"}]}}
   :scorer {"01" (merge {:cycle "01" :cut "spec-1" :scope :project :room-tree "t"} roles
                        {:items [{:id "spec-0001" :verdict :pass :evidence "seen"}] :passed 1 :scored 1})}
   :scoreboard {}
   :recorded []})

(defn- push! [layout dir m]
  (spit (str (fs/file dir "oracle.edn")) (pr-str m))
  (let [r (p/shell {:dir dir :out :string :err :string} "oras" "push" "--oci-layout" layout
                   "--artifact-type" oracle/artifact-type "oracle.edn:application/edn")]
    (second (re-find #"Digest: (sha256:[0-9a-f]{64})" (:out r)))))

(defn- project-with! [dir layout m]
  (let [src (str (fs/file dir "src")) proj (str (fs/file dir "proj"))]
    (fs/create-dirs src)
    (fs/create-dirs (fs/file proj "dhf/harness"))
    (let [d (push! layout src m)]
      (spit (str (fs/file proj command/config-file))
            (str ";; kept\n" (pr-str {:oracle {:repository "registry.invalid/o" :digest d}}) "\n"))
      {:dir proj :digest d})))

(defn- env [layout] (assoc (into {} (System/getenv)) "ORACLE_LAYOUT" layout))

(deftest judging-and-recording-a-local-oracle
  (with-tmp
    (fn [dir]
      (let [layout (str (fs/file dir "layout"))
            {proj :dir d :digest} (project-with! dir layout fixture)]
        (testing "every golden reproduced: a pass naming each golden"
          (let [r (command/execute ["oracle"] {:root proj :env (env layout)})]
            (is (= 0 (:exit r)) (pr-str r))
            (is (= [[:checklist "01"] [:scorer "01"]] (get-in r [:result :oracle :compared])))))
        (testing "a gate before fence passes nothing on when the oracle passes"
          (is (= {:findings []} (oracle/gate {:root proj :env (env layout) :config (command/read-config proj)}))))
        (testing "a digest the layout does not hold cannot run, naming it"
          (let [r (command/execute ["oracle"] {:root proj :env (assoc (env layout) "ORACLE_LAYOUT" (str (fs/file dir "none")))})]
            (is (= 2 (:exit r)))
            (is (str/includes? (:cannot-run r) d))
            (is (str/includes? (:cannot-run r) "registry.invalid/o"))))
        (testing "recording needs a reason"
          (is (re-find #"a reason is required" (:cannot-run (command/execute ["oracle" "record"] {:root proj :env (env layout)})))))
        (testing "nothing changed: nothing is recorded"
          (let [r (command/execute ["oracle" "record" "--reason" "check"] {:root proj :env (env layout)})]
            (is (= 0 (:exit r)))
            (is (= d (get-in (command/read-config proj) [:oracle :digest])))))))))

(deftest recording-rewrites-what-the-instrument-no-longer-gives
  (with-tmp
    (fn [dir]
      (let [layout (str (fs/file dir "layout"))
            stale (assoc-in fixture [:scorer "01" :passed] 0)
            {proj :dir d :digest} (project-with! dir layout stale)
            judged (command/execute ["oracle"] {:root proj :env (env layout)})]
        (is (= 1 (:exit judged)))
        (is (some #(and (= "golden-differs" (:rule %)) (str/includes? (:message %) ":passed")) (:findings judged)))
        (let [r (command/execute ["oracle" "record" "--reason" "the scorer counts passes"] {:root proj :env (env layout)})
              d2 (get-in (command/read-config proj) [:oracle :digest])
              out (str (fs/file dir "pulled"))]
          (is (= 0 (:exit r)) (pr-str r))
          (is (not= d d2))
          (is (str/starts-with? (slurp (str (fs/file proj command/config-file))) ";; kept"))
          (p/shell {:out :string :err :string} "oras" "pull" "--oci-layout" (str layout "@" d2) "-o" out)
          (let [m (edn/read-string (slurp (str (fs/file out "oracle.edn"))))]
            (is (= 1 (get-in m [:scorer "01" :passed])))
            (is (= [{:date (str (java.time.LocalDate/now)) :reason "the scorer counts passes" :changed [:scorer]}]
                   (:recorded m))))
          (is (= 0 (:exit (command/execute ["oracle"] {:root proj :env (env layout)})))))))))

(deftest no-oracle-is-no-oracle
  (with-tmp
    (fn [dir]
      (fs/create-dirs (fs/file dir "dhf/harness"))
      (spit (str (fs/file dir command/config-file)) "{}")
      (let [r (command/run-task ["oracle"] dir {})]
        (is (= 0 (:exit r)))
        (is (some #(str/includes? % "no oracle") (:err r))))
      (is (= {:findings []} (oracle/gate {:root dir :config {}}))))))
