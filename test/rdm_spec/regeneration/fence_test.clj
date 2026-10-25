(ns rdm-spec.regeneration.fence-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.fence :as fence]))

(def model {:containers [{:contexts [{:id :spec :components [{:id :spec/parser} {:id :spec/check}]}
                                     {:id :verification :components [{:id :verification/status}]}]}]
            :kernel [{:id :kernel/bus}]})

(def layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
             :component ["src/rdm_spec/{context}/{component}.clj"
                         "test/rdm_spec/{context}/{component}_test.clj"
                         "test/steps/{context}_{component}.clj"]})

(defn- refusal [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (when (command/cannot-run? e) (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Pure parts

(deftest the-scope-comes-from-the-options
  (is (= :project (fence/parse-scope {})))
  (is (= {:contexts ["knowledge" "publishing"] :components ["spec/parser"]}
         (fence/parse-scope {:context "knowledge,publishing" :component "spec/parser"})))
  (is (= {:contexts ["spec"] :components []} (fence/parse-scope {:context "spec,spec"})) "a scope named twice is one")
  (is (re-find #"empty scope" (refusal #(fence/parse-scope {:context "spec,"}))))
  (is (re-find #"CONTEXT/NAME" (refusal #(fence/parse-scope {:component "parser"})))))

(deftest a-scope-must-be-in-the-model
  (is (= ["spec" "verification"] (fence/model-contexts model)))
  (is (= ["spec/parser" "spec/check" "verification/status" "kernel/bus"] (fence/model-components model)))
  (is (= [] (fence/unknown-scopes model :project)))
  (is (= [] (fence/unknown-scopes model {:contexts ["kernel" "spec"] :components ["kernel/bus"]}))
      "the kernel is a scope like a bounded context")
  (let [[why & more] (fence/unknown-scopes model {:contexts ["archive"] :components []})]
    (is (empty? more))
    (is (re-find #"archive" why))
    (is (re-find #"spec, verification, and kernel" why)))
  (is (re-find #"spec/nope.*spec/parser" (first (fence/unknown-scopes model {:contexts [] :components ["spec/nope"]})))))

(deftest the-layout-gives-each-scope-its-prefixes
  (is (= [] (fence/layout-prefixes layout :project)))
  (is (= ["src/rdm_spec/verification/" "test/rdm_spec/verification/" "test/steps/verification_"]
         (fence/layout-prefixes layout {:contexts ["verification"] :components []})))
  (is (= ["src/rdm_spec/kernel/bus.clj" "test/rdm_spec/kernel/bus_test.clj" "test/steps/kernel_bus.clj"]
         (fence/layout-prefixes layout {:contexts [] :components ["kernel/bus"]})))
  (testing "{component} is written with - as _"
    (is (= "src/rdm_spec/x/a_b.clj" (first (fence/layout-prefixes layout {:contexts [] :components ["x/a-b"]}))))))

(deftest the-room-holds-the-allowed-files-and-what-the-scope-keeps
  (let [files ["README.md" "dhf/spec/a.feature" "dhf/specs-other/x" "dhf/harness/config.edn"
               "dhf/harness/oracle/g.edn" "src/rdm_spec/spec/parser.clj" "src/rdm_spec/spec/check.clj"
               "test/rdm_spec/spec/parser_test.clj" "test.clj" "bb.edn"]
        opts {:allow ["dhf/spec/" "dhf/harness/config.edn"] :implementation ["src" "test" "test.clj" "bb.edn"]}]
    (testing "an allowed path is a directory or a file, never a name prefix"
      (is (= ["dhf/harness/config.edn" "dhf/spec/a.feature"]
             (:allowed (fence/select-files (assoc opts :scope :project) files)))))
    (testing "the project scope keeps no implementation"
      (is (= [] (:kept (fence/select-files (assoc opts :scope :project) files)))))
    (testing "a component scope keeps every other implementation file"
      (let [scope {:contexts [] :components ["spec/parser"]}]
        (is (= ["bb.edn" "src/rdm_spec/spec/check.clj" "test.clj"]
               (:kept (fence/select-files (assoc opts :scope scope :removed (fence/layout-prefixes layout scope))
                                          files))))))))

;; ---------------------------------------------------------------------------
;; Against a repository

(defn- sh [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "T" "GIT_AUTHOR_EMAIL" "t@example.invalid"
                                      "GIT_COMMITTER_NAME" "T" "GIT_COMMITTER_EMAIL" "t@example.invalid"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" args)]
    (is (zero? (:exit r)) (str "git " (str/join " " args) ": " (:err r)))
    (str/trim (:out r))))

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit f text)))

(def config
  {:model "dhf/spec/model.edn"
   :implementation ["src" "test.clj"]
   :code-layout layout
   :clean-room {:allow ["dhf/spec" "dhf/harness/config.edn"]}})

(defn- project!
  "A repository whose commit tagged spec-1 holds the spec, the configuration,
  an oracle, a measurement and an implementation; the previous build was
  deleted before it, and a later commit is not a cut."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-fence-test-"}))]
    (sh dir "init" "-q")
    (write! dir "src/old_build.clj" "the previous build")
    (sh dir "add" "-A") (sh dir "commit" "-qm" "old")
    (fs/delete (fs/file dir "src/old_build.clj"))
    (write! dir "dhf/harness/config.edn" (pr-str config))
    (write! dir "dhf/spec/model.edn" (pr-str model))
    (write! dir "dhf/spec/spec/parser.feature" "Feature: Parser\n")
    (write! dir "dhf/spec/.gitignore" "*.feature\n")
    (write! dir "dhf/harness/oracle/goldens/x.edn" "{:golden true}")
    (write! dir "dhf/cycles/01/score.edn" "{:score 1}")
    (write! dir "src/rdm_spec/spec/parser.clj" "(ns parser)")
    (write! dir "src/rdm_spec/spec/check.clj" "(ns check)")
    (write! dir "src/rdm_spec/verification/status.clj" "(ns status)")
    (write! dir "test.clj" "tests")
    (sh dir "add" "-A" "-f") (sh dir "commit" "-qm" "cut")
    (sh dir "tag" "spec-1")
    (write! dir "dhf/spec/later.md" "later")
    (sh dir "add" "-A") (sh dir "commit" "-qm" "later")
    dir))

(defn- room-files [room]
  (sort (for [f (fs/glob room "**" {:hidden true})
              :let [rel (str (fs/relativize room f))]
              :when (and (fs/regular-file? f) (not (str/starts-with? rel ".git/")))]
          rel)))

(defn- run [dir & argv] (command/execute (vec (cons "fence" argv)) {:root dir :env {}}))

(deftest fencing-a-cut
  (let [dir (project!)
        room (str (fs/file (fs/create-temp-dir {:prefix "rdm-spec-fence-room-"}) "room"))]
    (try
      (testing "the project scope: the allowed files, one root commit"
        (let [r (run dir room "spec-1")]
          (is (= 0 (:exit r)) (pr-str r))
          (is (= ["dhf/harness/config.edn" "dhf/spec/.gitignore" "dhf/spec/model.edn" "dhf/spec/spec/parser.feature"]
                 (room-files room))
              "a file the cut's .gitignore names is still committed")
          (is (= "1" (sh room "rev-list" "--count" "--all")))
          (is (= "" (sh room "status" "--porcelain")))
          (is (= :project (get-in r [:result :scope])))
          (is (= "spec-1" (get-in r [:result :cut])))))
      (testing "a room that is not empty is refused"
        (let [r (run dir room "spec-1")]
          (is (= 2 (:exit r)))
          (is (re-find #"not empty" (:cannot-run r)))))
      (testing "the same cut and scope build the same commit"
        (let [room2 (str (fs/create-temp-dir {:prefix "rdm-spec-fence-room-"}))
              r (run dir room2 "spec-1")]
          (is (= 0 (:exit r)) "an empty directory is a room to build in")
          (is (= (sh room "rev-parse" "HEAD") (get-in r [:result :room-commit])))))
      (testing "a component scope keeps the rest of the implementation"
        (let [room3 (str (fs/create-temp-dir {:prefix "rdm-spec-fence-room-"}) "/r")
              r (run dir room3 "spec-1" "--component" "spec/parser" "--context" "verification")]
          (is (= 0 (:exit r)) (pr-str r))
          (is (= ["dhf/harness/config.edn" "dhf/spec/.gitignore" "dhf/spec/model.edn" "dhf/spec/spec/parser.feature"
                  "src/rdm_spec/spec/check.clj" "test.clj"]
                 (room-files room3)))))
      (testing "a commit that is not a cut is refused, and nothing is built"
        (let [room4 (str (fs/create-temp-dir {:prefix "rdm-spec-fence-room-"}) "/r")]
          (doseq [c ["HEAD" "no-such-commit"]]
            (let [r (run dir room4 c)]
              (is (= 2 (:exit r)))
              (is (re-find #"regeneration reads only a cut" (:cannot-run r)))
              (is (not (fs/exists? room4)))))))
      (testing "a scope the cut's model does not name is refused"
        (let [r (run dir (str room "-x") "spec-1" "--context" "archive")]
          (is (= 2 (:exit r)))
          (is (re-find #"archive.*spec, verification, and kernel" (:cannot-run r)))))
      (testing "the configuration is the cut's, not the working tree's"
        (write! dir "dhf/harness/config.edn" (pr-str (assoc-in config [:clean-room :allow] ["dhf/missing"])))
        (let [room5 (str (fs/create-temp-dir {:prefix "rdm-spec-fence-room-"}) "/r")]
          (is (= 0 (:exit (run dir room5 "spec-1"))))))
      (testing "an allowed path missing at the cut is refused, naming it"
        (write! dir "dhf/harness/config.edn" (pr-str (assoc-in config [:clean-room :allow] ["dhf/spec" "dhf/wiki"])))
        (sh dir "add" "-A") (sh dir "commit" "-qm" "allow the wiki") (sh dir "tag" "spec-2")
        (let [room6 (str (fs/create-temp-dir {:prefix "rdm-spec-fence-room-"}) "/r")
              r (run dir room6 "spec-2")]
          (is (= 2 (:exit r)))
          (is (re-find #"holds no dhf/wiki" (:cannot-run r)))
          (is (not (fs/exists? room6)))))
      (finally (fs/delete-tree dir)))))
