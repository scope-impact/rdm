(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.project.init :as init]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "init-test-"})))

(deftest the-skeleton-is-the-spec-s-list
  (is (= ["dhf/spec/model.edn" "dhf/spec/events.edn" "dhf/spec/needs.edn" "dhf/spec/ids.edn"
          "dhf/spec/glossary.md" "dhf/spec/README.md" "dhf/harness/config.edn" "dhf/raw/README.md"
          "dhf/raw/sources.edn" "dhf/wiki/index.md" "dhf/decisions/README.md" ".githooks/pre-commit"
          ".github/workflows/cut.yml" ".gitignore"]
         (init/skeleton-paths))))

(deftest gitignore-lines
  (is (= ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"]
         (init/gitignore-lines))))

(deftest vars-name-the-project
  (let [v (init/vars-for "Sauna Room" "sauna")]
    (is (= "Sauna Room" (:name v)))
    (is (= "\"Sauna Room\"" (:name-edn v)))
    (is (= ":sauna-room" (:system-id v)))
    (is (= "sauna" (:prefix v)))
    (is (= "\"sauna\"" (:prefix-edn v))))
  (is (= ":system" (:system-id (init/vars-for "___" "spec")))))

(deftest the-id-prefix-is-checked
  (is (init/valid-prefix? "spec"))
  (is (init/valid-prefix? "sauna-2"))
  (doseq [bad ["" "2x" "a b" "@spec" "spec-" "a--b" "a\"b"]]
    (testing bad (is (not (init/valid-prefix? bad))))))

(deftest render-fills-every-placeholder
  (is (= "a sauna b" (init/render "a {{name}} b" {:name "sauna"})))
  (is (thrown? clojure.lang.ExceptionInfo (init/render "{{nope}}" {}))))

(deftest the-written-files-read-back
  (let [dir (tmp)
        {:keys [wrote kept]} (init/write-skeleton! dir (init/vars-for "sauna" "loyly"))
        config (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn"))))
        model (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn"))))]
    (is (= (init/skeleton-paths) wrote))
    (is (= [] kept))
    (is (= "loyly" (:id-prefix config)))
    (is (= init/clean-room-allow (get-in config [:clean-room :allow])))
    (is (= [] (:implementation config)))
    (is (= {:id :sauna :name "sauna"} (select-keys (:system model) [:id :name])))
    (is (fs/executable? (fs/file dir ".githooks/pre-commit")))
    (is (str/includes? (slurp (str (fs/file dir "dhf/spec/README.md"))) "@loyly-NNNN"))))

(deftest an-existing-destination-is-kept
  (let [dir (tmp)
        glossary (fs/file dir "dhf/spec/glossary.md")
        link (fs/file dir "dhf/harness/config.edn")]
    (fs/create-dirs (fs/parent glossary))
    (spit glossary "ours\n")
    (fs/create-dirs (fs/parent link))
    (fs/create-sym-link link "nowhere.edn")
    (let [{:keys [wrote kept]} (init/write-skeleton! dir (init/vars-for "sauna" "spec"))]
      (is (= ["dhf/spec/glossary.md" "dhf/harness/config.edn"] kept))
      (is (not-any? #{"dhf/spec/glossary.md" "dhf/harness/config.edn"} wrote))
      (is (= "ours\n" (slurp glossary)))
      (is (fs/sym-link? link)))))

(deftest a-destination-under-a-file-is-skipped
  (let [dir (tmp)]
    (spit (str (fs/file dir ".githooks")) "a file, not a directory\n")
    (let [{:keys [skipped findings]} (init/write-skeleton! dir (init/vars-for "sauna" "spec"))]
      (is (= [".githooks/pre-commit"] skipped))
      (is (= ["skeleton-skipped"] (map :rule findings))))))

(deftest next-steps
  (is (str/starts-with? (first (init/next-steps "sauna")) "cd sauna"))
  (is (not-any? #(str/starts-with? % "cd ") (init/next-steps nil))))

(deftest init-refuses-an-existing-path-and-a-bad-prefix
  (let [dir (tmp)]
    (fs/create-dirs (fs/file dir "sauna"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sauna already exists"
                          (init/init {:root dir :args ["sauna"] :opts {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"id prefix"
                          (init/init {:root dir :args ["other"] :opts {:id-prefix "a b"}})))
    (is (not (fs/exists? (fs/file dir "other"))))))
