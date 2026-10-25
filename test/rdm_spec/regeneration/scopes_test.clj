(ns rdm-spec.regeneration.scopes-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model {:containers [{:contexts [{:id :architecture
                                      :components [{:id :architecture/views :spec "architecture/views"}
                                                   {:id :architecture/lint :spec "architecture/lint"}]}
                                     {:id :regeneration
                                      :components [{:id :regeneration/fence :spec "regeneration/fence"}]}]}]
            :kernel [{:id :kernel/bus :spec "kernel/bus"}]})

(def config {:spec-dir "dhf/spec" :model "dhf/spec/model.edn"
             :clean-room {:allow ["dhf/spec" "dhf/harness" "dhf/wiki"
                                  "dhf/decisions/adr-0002-spec-first-regeneration.md"]}})

;; ---------------------------------------------------------------------------
;; Pure parts

(deftest owners-come-from-the-model
  (let [owners (scopes/owners model "dhf/spec")]
    (is (= :architecture/views (get owners "dhf/spec/architecture/views.feature")))
    (is (= :architecture/views (get owners "dhf/spec/architecture/views.md")) "the design prose is the component's")
    (is (= :kernel/bus (get owners "dhf/spec/kernel/bus.feature")))
    (is (nil? (get owners "dhf/spec/events.edn")))
    (testing "a spec dir written with a trailing slash"
      (is (= :kernel/bus (get (scopes/owners model "dhf/spec/") "dhf/spec/kernel/bus.feature"))))))

(deftest changed-files-split-into-scopes-and-shared
  (let [owners (scopes/owners model "dhf/spec")
        allow (get-in config [:clean-room :allow])]
    (testing "components whose files changed are scopes, once each, sorted"
      (is (= {:scopes [{:component :architecture/views} {:component :kernel/bus}] :shared []}
             (scopes/classify owners allow ["dhf/spec/kernel/bus.feature" "dhf/spec/architecture/views.feature"
                                            "dhf/spec/architecture/views.md"]))))
    (testing "a changed file in the clean room that no component owns is shared"
      (is (= {:scopes [] :shared ["dhf/spec/events.edn" "dhf/spec/glossary.md" "dhf/wiki/pages/x.md"]}
             (scopes/classify owners allow ["dhf/spec/glossary.md" "dhf/spec/events.edn" "dhf/wiki/pages/x.md"]))))
    (testing "a file no regeneration reads (outside the clean room) is neither"
      (is (= {:scopes [] :shared []}
             (scopes/classify owners allow ["src/rdm_spec/kernel/bus.clj" "dhf/cycles/02/score.edn" "README.md"]))))
    (testing "a feature file of a component the model does not name is shared"
      (is (= {:scopes [] :shared ["dhf/spec/archive/old.feature"]}
             (scopes/classify owners allow ["dhf/spec/archive/old.feature"]))))))

;; ---------------------------------------------------------------------------
;; Through git

(def git-id {"GIT_AUTHOR_NAME" "T" "GIT_AUTHOR_EMAIL" "t@example.invalid"
             "GIT_COMMITTER_NAME" "T" "GIT_COMMITTER_EMAIL" "t@example.invalid"})

(defn- git! [dir & args]
  (apply cut/git! dir {:extra-env git-id} "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false"
         "-c" "tag.gpgsign=false" args))

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit f text)))

(defn- commit-and-tag! [dir tag]
  (git! dir "add" "-A" ".")
  (git! dir "commit" "-q" "--allow-empty" "-m" (str "at " tag))
  (when tag (git! dir "tag" tag)))

(defn- repo!
  "A repository whose spec-3 holds the model, the configuration and a few
  spec files, with `change!` applied and committed as spec-4."
  [change!]
  (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-scopes-test-"}))]
    (git! dir "init" "-q")
    (write! dir "dhf/harness/config.edn" (pr-str config))
    (write! dir "dhf/spec/model.edn" (pr-str model))
    (doseq [f ["architecture/views.feature" "architecture/lint.feature" "kernel/bus.feature"
               "regeneration/fence.feature"]]
      (write! dir (str "dhf/spec/" f) "Feature: X\n"))
    (write! dir "dhf/spec/events.edn" "{}\n")
    (write! dir "dhf/spec/glossary.md" "# Glossary\n")
    (write! dir "src/rdm_spec/kernel/bus.clj" "(ns x)\n")
    (commit-and-tag! dir "spec-3")
    (change! dir)
    (commit-and-tag! dir "spec-4")
    dir))

(defn- append! [dir rel] (spit (fs/file dir rel) "# changed\n" :append true))

(defn- run [dir & args]
  (scopes/scopes {:root dir :args (vec args) :opts {} :config nil}))

(defn- refused [dir & args]
  (try (apply run dir args) nil
       (catch Exception e (is (command/cannot-run? e)) (ex-message e))))

(deftest lists-the-components-whose-files-changed
  (let [dir (repo! #(do (append! % "dhf/spec/architecture/views.feature")
                        (append! % "dhf/spec/kernel/bus.feature")
                        (append! % "src/rdm_spec/kernel/bus.clj")))]
    (try
      (let [{:keys [result findings]} (run dir "spec-3" "spec-4")]
        (is (= [{:component :architecture/views} {:component :kernel/bus}] (:scopes result)))
        (is (= [] (:shared result)))
        (is (= "spec-3" (:from result)))
        (is (= "spec-4" (:to result)))
        (is (empty? findings)))
      (testing "the cuts in either order give the same scopes"
        (is (= [{:component :architecture/views} {:component :kernel/bus}]
               (:scopes (:result (run dir "spec-4" "spec-3"))))))
      (testing "a cut and itself: nothing changed"
        (is (= {:scopes [] :shared []} (select-keys (:result (run dir "spec-4" "spec-4")) [:scopes :shared]))))
      (finally (fs/delete-tree dir)))))

(deftest names-changed-shared-files-for-a-person
  (let [dir (repo! #(do (append! % "dhf/spec/events.edn") (append! % "dhf/spec/glossary.md")))]
    (try
      (let [{:keys [result findings]} (run dir "spec-3" "spec-4")
            lines (map command/format-finding findings)]
        (is (= [] (:scopes result)))
        (is (= ["dhf/spec/events.edn" "dhf/spec/glossary.md"] (:shared result)))
        (is (every? command/warning? findings) "a shared file is a warning: the task still passes")
        (is (= 2 (count findings)))
        (is (some #(str/starts-with? % "dhf/spec/events.edn: warning: shared-file-changed: ") lines))
        (is (some #(str/starts-with? % "dhf/spec/glossary.md: warning: shared-file-changed: ") lines)))
      (finally (fs/delete-tree dir)))))

(deftest a-new-or-deleted-component
  (testing "a component added with its feature file is a scope; the model's change is shared"
    (let [m2 (update-in model [:containers 0 :contexts 1 :components] conj
                        {:id :regeneration/scopes :spec "regeneration/scopes"})
          dir (repo! #(do (write! % "dhf/spec/model.edn" (pr-str m2))
                          (write! % "dhf/spec/regeneration/scopes.feature" "Feature: S\n")))]
      (try
        (let [{:keys [result]} (run dir "spec-3" "spec-4")]
          (is (= [{:component :regeneration/scopes}] (:scopes result)))
          (is (= ["dhf/spec/model.edn"] (:shared result))))
        (finally (fs/delete-tree dir)))))
  (testing "a component the later cut's model no longer names is shared, not a scope"
    (let [m2 (assoc-in model [:containers 0 :contexts 1 :components] [])
          dir (repo! #(do (write! % "dhf/spec/model.edn" (pr-str m2))
                          (fs/delete (fs/file % "dhf/spec/regeneration/fence.feature"))))]
      (try
        (let [{:keys [result]} (run dir "spec-3" "spec-4")]
          (is (= [] (:scopes result)))
          (is (= ["dhf/spec/model.edn" "dhf/spec/regeneration/fence.feature"] (:shared result))))
        (finally (fs/delete-tree dir))))))

(deftest refuses-what-is-not-two-cuts
  (let [dir (repo! #(append! % "dhf/spec/kernel/bus.feature"))]
    (try
      (testing "a name that is no commit"
        (is (str/includes? (str (refused dir "spec-3" "spec-9")) "spec-9")))
      (testing "a commit that carries no spec-N tag"
        (write! dir "x.md" "x\n")
        (commit-and-tag! dir nil)
        (is (str/includes? (str (refused dir "spec-3" "HEAD")) "no spec-N tag")))
      (testing "a commit carrying a cut tag may be named by its hash"
        (let [c (cut/rev dir "spec-4")]
          (is (= "spec-4" (:to (:result (run dir "spec-3" c)))))))
      (finally (fs/delete-tree dir))))
  (testing "a directory that is no git repository"
    (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-scopes-test-"}))]
      (try (is (refused dir "spec-3" "spec-4"))
           (finally (fs/delete-tree dir))))))

(deftest reads-the-configuration-and-model-of-the-later-cut
  (testing "a later cut with no configuration cannot run, as fence cannot"
    (let [dir (repo! #(fs/delete (fs/file % "dhf/harness/config.edn")))]
      (try (is (str/includes? (str (refused dir "spec-3" "spec-4")) "config.edn"))
           (finally (fs/delete-tree dir)))))
  (testing "a later cut with no model cannot run"
    (let [dir (repo! #(fs/delete (fs/file % "dhf/spec/model.edn")))]
      (try (is (str/includes? (str (refused dir "spec-3" "spec-4")) "model"))
           (finally (fs/delete-tree dir))))))
