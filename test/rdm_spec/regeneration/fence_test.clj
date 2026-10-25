(ns rdm-spec.regeneration.fence-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.fence :as fence]))

(def layout
  {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
   :component ["src/rdm_spec/{context}/{component}.clj"
               "test/rdm_spec/{context}/{component}_test.clj"
               "test/steps/{context}_{component}.clj"]})

(deftest paths-under
  (is (fence/under? "dhf/spec/a.feature" "dhf/spec"))
  (is (fence/under? "dhf/spec/a.feature" "dhf/spec/"))
  (is (fence/under? "test.clj" "test.clj"))
  (testing "a sibling sharing a prefix is not under"
    (is (not (fence/under? "dhf/specs/a" "dhf/spec")))
    (is (not (fence/under? "test.cljs" "test.clj")))))

(deftest prefixes-of-a-scope
  (is (= ["src/rdm_spec/verification/" "test/rdm_spec/verification/" "test/steps/verification_"]
         (fence/scope-prefixes layout {:contexts ["verification"]})))
  (testing "- is written _ in file names"
    (is (= ["src/rdm_spec/my_ctx/a_b.clj" "test/rdm_spec/my_ctx/a_b_test.clj" "test/steps/my_ctx_a_b.clj"]
           (fence/scope-prefixes layout {:components [["my-ctx" "a-b"]]}))))
  (testing "a list of contexts and components"
    (is (= 6 (count (fence/scope-prefixes layout {:contexts ["kernel"] :components [["spec" "parser"]]}))))))

(def files
  ["dhf/spec/a.feature" "dhf/harness/config.edn" "README.md" "dhf/cycles/01/score.edn"
   "src/rdm_spec/spec/parser.clj" "src/rdm_spec/spec/parser_helpers/x.clj" "src/rdm_spec/spec/stamper.clj"
   "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj" "test/steps/spec_support.clj"
   "test.clj" "bb.edn"])

(def config {:allow ["dhf/spec" "dhf/harness"] :implementation ["src" "test" "test.clj" "bb.edn"]})

(deftest what-the-room-holds
  (testing "the project scope keeps no implementation"
    (let [r (fence/room-paths files config)]
      (is (= ["dhf/spec/a.feature" "dhf/harness/config.edn"] (:allowed r)))
      (is (= [] (:kept r)))
      (is (= 8 (count (:removed r))))
      (is (= [] (:missing-allowed r)))))
  (testing "a component scope removes its files, and no file merely starting with its name in another directory"
    (let [r (fence/room-paths files (assoc config :prefixes (fence/scope-prefixes layout {:components [["spec" "parser"]]})))]
      (is (= #{"src/rdm_spec/spec/parser.clj" "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj"}
             (set (:removed r))))
      (is (some #{"src/rdm_spec/spec/parser_helpers/x.clj"} (:kept r)))
      (is (not-any? #{"README.md" "dhf/cycles/01/score.edn"} (concat (:allowed r) (:kept r))))))
  (testing "a context scope removes its steps too"
    (let [r (fence/room-paths files (assoc config :prefixes (fence/scope-prefixes layout {:contexts ["spec"]})))]
      (is (= #{"test.clj" "bb.edn"} (set (:kept r))))))
  (testing "an allowed path the cut does not hold"
    (is (= ["dhf/wiki"] (:missing-allowed (fence/room-paths files (update config :allow conj "dhf/wiki")))))))

(def model
  {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}]}
                                   {:id :verification :components [{:id :verification/status}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"}]})

(deftest scopes-the-model-names
  (is (= {:contexts ["spec" "verification" "kernel"]
          :components #{"spec/parser" "verification/status" "kernel/bus"}}
         (fence/model-scopes model)))
  (is (nil? (fence/scope-of {} (fn [] (throw (ex-info "the model is not read for the project" {}))) "spec-3")))
  (is (= {:contexts ["kernel"] :components [["spec" "parser"]]}
         (fence/scope-of {:context "kernel" :component "spec/parser"} (constantly model) "spec-3")))
  (is (= {:contexts ["spec" "verification"] :components []}
         (fence/scope-of {:context "spec,verification"} (constantly model) "spec-3")))
  (testing "a scope the model does not name cannot run, naming the model's contexts and kernel"
    (let [e (try (fence/scope-of {:context "archive"} (constantly model) "spec-3") nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (::command/cannot-run (ex-data e)))
      (is (every? #(str/includes? (ex-message e) %) ["archive" "spec" "verification" "kernel"])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (fence/scope-of {:component "spec/stamper"} (constantly model) "spec-3")))))

;; --- end to end, in a throwaway repository --------------------------------------

(defn- sh [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_AUTHOR_NAME" "T" "GIT_AUTHOR_EMAIL" "t@example.invalid"
                                      "GIT_COMMITTER_NAME" "T" "GIT_COMMITTER_EMAIL" "t@example.invalid"}}
                 "git" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (is (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(defn- write! [dir rel content]
  (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit f content)))

(defn- repo! []
  (let [dir (str (fs/create-temp-dir {:prefix "fence-test-"}))]
    (sh dir "init" "-q" "-b" "main")
    (write! dir "secret.txt" "old\n")
    (sh dir "add" "-A") (sh dir "commit" "-q" "-m" "old")
    (fs/delete (fs/file dir "secret.txt"))
    (write! dir "dhf/harness/config.edn"
            (pr-str {:clean-room {:allow ["dhf/spec" "dhf/harness"]} :implementation ["src"]
                     :model "dhf/spec/model.edn" :code-layout layout}))
    (write! dir "dhf/spec/model.edn" (pr-str model))
    (write! dir "dhf/spec/.gitattributes" "* text eol=crlf\n")
    (write! dir "dhf/spec/crlf.txt" "a\r\nb\n")
    (write! dir "dhf/spec/.gitignore" "*.txt\n")
    (write! dir "dhf/spec/empty" "")
    (write! dir "src/rdm_spec/spec/parser.clj" "(ns p)\n")
    (write! dir "src/rdm_spec/spec/stamper.clj" "(ns s)\n")
    (write! dir "other.md" "no\n")
    (sh dir "add" "-A" "-f") (sh dir "commit" "-q" "-m" "cut") (sh dir "tag" "spec-3")
    (sh dir "commit" "-q" "--allow-empty" "-m" "after")
    dir))

(defn- run [dir & argv] (command/run-task (vec argv) dir nil))

(deftest building-a-room
  (let [dir (repo!)
        room (str (fs/file (fs/create-temp-dir) "room"))]
    (testing "a commit that is no cut, and a name that is no commit"
      (is (= 2 (:exit (run dir "fence" room "HEAD"))))
      (is (str/includes? (first (:err (run dir "fence" room "nope"))) "regeneration reads only a cut"))
      (is (not (fs/exists? room))))
    (testing "a component scope"
      (let [r (run dir "fence" room "spec-3" "--component" "spec/parser")]
        (is (zero? (:exit r)) (pr-str r))
        (is (= (sh dir "ls-tree" "-r" "spec-3" "--" "dhf" "src/rdm_spec/spec/stamper.clj")
               (sh room "ls-tree" "-r" "HEAD")))
        (is (= "1" (sh room "rev-list" "--all" "--count")))
        (is (not (fs/exists? (fs/file room "other.md"))))
        (is (= "" (sh room "status" "--porcelain")))))
    (testing "a room that is not empty is refused"
      (is (= 2 (:exit (run dir "fence" room "spec-3")))))
    (testing "the cut's commit given by its id is a cut"
      (let [room2 (str (fs/file (fs/create-temp-dir) "room"))
            r (run dir "fence" room2 (sh dir "rev-parse" "spec-3"))]
        (is (zero? (:exit r)) (pr-str r))
        (is (str/includes? (:out r) ":cut \"spec-3\""))))))
