(ns rdm-spec.regeneration.fence-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.regeneration.fence :as fence]))

(def layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
             :component ["src/rdm_spec/{context}/{component}.clj"
                         "test/rdm_spec/{context}/{component}_test.clj"
                         "test/steps/{context}_{component}.clj"]})

(def model {:containers [{:contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}
                                                             {:id :spec/check :spec "spec/check"}]}
                                     {:id :verification :components [{:id :verification/status
                                                                      :spec "verification/status"}]}]}]
            :kernel [{:id :kernel/bus :spec "kernel/bus"}]})

(deftest under
  (is (fence/under? "dhf/spec/a.feature" "dhf/spec"))
  (is (fence/under? "dhf/spec" "dhf/spec/"))
  (is (not (fence/under? "dhf/specs/a" "dhf/spec")))
  (is (= ["dhf/wiki"] (fence/missing-allowed ["dhf/spec" "dhf/wiki" "x.md"] ["dhf/spec/a" "x.md"]))))

(deftest scope-from-options
  (is (nil? (fence/parse-scope {})))
  (is (nil? (fence/parse-scope {:context "" :events true})))
  (is (= {:contexts ["knowledge" "publishing"] :components ["spec/parser"]}
         (fence/parse-scope {:context "knowledge,publishing,knowledge" :component "spec/parser"}))))

(deftest scopes-the-model-names
  (let [known (fence/known-scopes model)]
    (is (= {"spec" #{"parser" "check"} "verification" #{"status"} "kernel" #{"bus"}} known))
    (is (empty? (fence/scope-problems known {:contexts ["kernel" "spec"] :components ["kernel/bus" "spec/parser"]})))
    (testing "an unknown context names it, every context and kernel"
      (let [[m & more] (fence/scope-problems known {:contexts ["archive"] :components []})]
        (is (nil? more))
        (is (every? #(str/includes? m %) ["archive" "spec" "verification" "kernel"]))))
    (testing "an unknown component, in a known or unknown context"
      (is (str/includes? (first (fence/scope-problems known {:components ["spec/frob"]})) "spec/parser"))
      (is (str/includes? (first (fence/scope-problems known {:components ["nope/x"]})) "nope"))
      (is (= 1 (count (fence/scope-problems known {:components ["spec"]})))))))

(deftest prefixes-and-kept-files
  (let [scope {:contexts ["verification"] :components ["spec/parser" "kernel/bus"]}
        ps (fence/scope-prefixes layout scope)
        files ["dhf/spec/x.feature" "bb.edn" "src/rdm_spec/verification/status.clj"
               "src/rdm_spec/spec/parser.clj" "src/rdm_spec/spec/parser_extra.clj"
               "test/steps/verification_status.clj" "test/steps/support.clj"
               "src/rdm_spec/kernel/bus.clj" "src/rdm_spec/kernel/command.clj"]]
    (is (some #{"test/steps/kernel_bus.clj"} ps))
    (is (= ["bb.edn" "src/rdm_spec/spec/parser_extra.clj" "test/steps/support.clj" "src/rdm_spec/kernel/command.clj"]
           (fence/kept-implementation files ["src" "test" "bb.edn"] scope ps)))
    (testing "the project scope keeps nothing"
      (is (= [] (fence/kept-implementation files ["src" "test" "bb.edn"] nil nil))))
    (testing "- is written _"
      (is (= ["src/rdm_spec/a_b/c_d.clj"]
             (take 1 (fence/scope-prefixes layout {:components ["a-b/c-d"]})))))))

;; ---------------------------------------------------------------------------
;; The task, on a throwaway repository

(defn- sh [d & args]
  (apply cut/git! d {:extra-env {"GIT_AUTHOR_NAME" "T" "GIT_AUTHOR_EMAIL" "t@x.invalid"
                                 "GIT_COMMITTER_NAME" "T" "GIT_COMMITTER_EMAIL" "t@x.invalid"}}
         "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null" args))

(defn- write! [d rel text]
  (fs/create-dirs (fs/parent (fs/file d rel)))
  (spit (str (fs/file d rel)) text))

(defn- repo! []
  (let [d (str (fs/create-temp-dir {:prefix "fence-test-"}))]
    (sh d "init" "-q")
    (write! d "src/rdm_spec/old.clj" "old")
    (sh d "add" "-A") (sh d "commit" "-q" "-m" "old")
    (fs/delete (fs/file d "src/rdm_spec/old.clj"))
    (write! d "dhf/harness/config.edn"
            (pr-str {:clean-room {:allow ["dhf/spec" "dhf/harness"]} :implementation ["src" "test"]
                     :code-layout layout :model "dhf/spec/model.edn"}))
    (write! d "dhf/spec/model.edn" (pr-str model))
    (write! d "dhf/cycles/01/score.edn" "{}")
    (write! d "src/rdm_spec/spec/parser.clj" "parser")
    (write! d "src/rdm_spec/spec/check.clj" "check")
    (sh d "add" "-A") (sh d "commit" "-q" "-m" "cut") (sh d "tag" "spec-1")
    (write! d "dhf/spec/later.md" "later")
    (sh d "add" "-A") (sh d "commit" "-q" "-m" "later")
    d))

(defn- files [d]
  (set (for [f (fs/glob d "**" {:hidden true})
             :let [r (str (fs/relativize d f))]
             :when (and (fs/regular-file? f) (not (str/starts-with? r ".git")))]
         r)))

(deftest fence-a-cut
  (let [d (repo!)
        room (str (fs/file (fs/create-temp-dir) "room"))]
    (testing "a commit with no spec-N tag, and a name that is no commit"
      (let [r (command/run-task ["fence" room "HEAD"] d nil)]
        (is (= 2 (:exit r)))
        (is (some #(str/includes? % "reads only a cut") (:err r))))
      (is (= 2 (:exit (command/run-task ["fence" room "nothing-here"] d nil))))
      (is (not (fs/exists? room))))
    (testing "an unknown scope"
      (let [r (command/run-task ["fence" room "spec-1" "--context" "archive"] d nil)]
        (is (= 2 (:exit r)))
        (is (some #(str/includes? % "archive") (:err r)))
        (is (not (fs/exists? room)))))
    (testing "the project scope"
      (let [r (command/run-task ["fence" room "spec-1"] d nil)]
        (is (= 0 (:exit r)) (pr-str r))
        (is (= #{"dhf/harness/config.edn" "dhf/spec/model.edn"} (files room)))
        (is (= 1 (count (cut/lines (cut/git! room "rev-list" "--all")))))))
    (testing "a room that is not empty is refused"
      (is (= 2 (:exit (command/run-task ["fence" room "spec-1"] d nil)))))
    (testing "a component scope keeps the rest of the implementation"
      (let [room2 (str (fs/file (fs/create-temp-dir) "room"))
            r (command/run-task ["fence" room2 "spec-1" "--component" "spec/parser"] d nil)]
        (is (= 0 (:exit r)) (pr-str r))
        (is (= #{"dhf/harness/config.edn" "dhf/spec/model.edn" "src/rdm_spec/spec/check.clj"} (files room2)))))
    (testing "an allowed path missing at the cut"
      (write! d "dhf/harness/config.edn"
              (pr-str {:clean-room {:allow ["dhf/spec" "dhf/wiki"]} :implementation ["src"]}))
      (sh d "add" "-A") (sh d "commit" "-q" "-m" "wiki allowed") (sh d "tag" "spec-2")
      (let [room3 (str (fs/file (fs/create-temp-dir) "room"))
            r (command/run-task ["fence" room3 "spec-2"] d nil)]
        (is (= 2 (:exit r)))
        (is (some #(str/includes? % "dhf/wiki") (:err r)))
        (is (not (fs/exists? room3)))))))
