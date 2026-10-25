(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.project.init :as init]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "rdm-spec-init-test-"})))

(deftest a-prefix-starts-with-a-letter-and-holds-letters-digits-and-single-hyphens
  (doseq [ok ["spec" "sauna" "loyly" "a" "A1" "sauna-2" "x-y-z" "Spec9"]]
    (is (init/valid-prefix? ok) ok))
  (doseq [bad ["9lives" "" "-a" "a-" "a--b" "a_b" "a b" "sä" "a.b" nil]]
    (is (not (init/valid-prefix? bad)) (pr-str bad))))

(deftest the-system-id-is-a-keyword-made-from-the-name
  (is (= :sauna (:system-id-kw (init/vars-for "sauna" "spec"))))
  (is (= :my-sauna (:system-id-kw (init/vars-for "My Sauna!" "spec"))))
  (is (= :system (:system-id-kw (init/vars-for "___" "spec"))))
  (is (= :system-2 (:system-id-kw (init/vars-for "2" "spec")))))

(deftest rendering-fills-every-placeholder
  (let [vars (init/vars-for "sauna" "loyly")]
    (doseq [rel (init/skeleton-paths)]
      (let [text (init/render rel vars)]
        (is (string? text) rel)
        (is (not (re-find #"\{\{" text)) rel)))
    (is (= "loyly" (:id-prefix (edn/read-string (init/render "dhf/harness/config.edn" vars)))))
    (is (= {:id :sauna :name "sauna" :description "Describe what the system does"}
           (:system (edn/read-string (init/render "dhf/spec/model.edn" vars)))))))

(deftest a-name-with-quotes-still-renders-readable-edn
  (let [vars (init/vars-for "the \"best\" sauna" "spec")]
    (is (= "the \"best\" sauna" (get-in (edn/read-string (init/render "dhf/spec/model.edn" vars)) [:system :name])))))

(deftest the-allow-list-never-admits-the-oracle
  (let [c (edn/read-string (init/render "dhf/harness/config.edn" (init/vars-for "sauna" "spec")))]
    (is (= ["dhf/spec" "dhf/harness/config.edn" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"]
           (get-in c [:clean-room :allow])))
    (is (= [] (:implementation c)))))

(deftest the-gitignore-lines-are-the-template-s
  (is (= ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"]
         (init/gitignore-lines))))

(deftest next-steps-start-in-the-new-project-when-one-was-made
  (is (str/includes? (first (init/next-steps "sauna")) "cd sauna"))
  (is (not-any? #(str/includes? % "cd ") (init/next-steps nil)))
  (is (some #(str/includes? % "rdm-spec check") (init/next-steps nil))))

(deftest write-skeleton-writes-every-path-and-keeps-what-exists
  (let [dir (tmp)
        vars (init/vars-for "sauna" "spec")]
    (fs/create-dirs (fs/file dir "dhf/spec"))
    (spit (str (fs/file dir "dhf/spec/glossary.md")) "ours\n")
    (let [{:keys [wrote kept skipped findings]} (init/write-skeleton! dir vars)]
      (is (= ["dhf/spec/glossary.md"] kept))
      (is (= (sort (remove #{"dhf/spec/glossary.md"} (init/skeleton-paths))) (sort wrote)))
      (is (empty? skipped))
      (is (empty? findings))
      (is (= "ours\n" (slurp (str (fs/file dir "dhf/spec/glossary.md")))))
      (is (fs/executable? (fs/file dir ".githooks/pre-commit"))))))

(deftest write-skeleton-never-writes-through-a-file-or-a-link
  (testing "a file where a directory should be"
    (let [dir (tmp)]
      (spit (str (fs/file dir "dhf")) "a file\n")
      (let [{:keys [wrote skipped findings]} (init/write-skeleton! dir (init/vars-for "x" "spec"))]
        (is (not-any? #(str/starts-with? % "dhf/") wrote))
        (is (every? #(str/starts-with? % "dhf/") skipped))
        (is (seq findings))
        (is (every? #(= :warning (:severity %)) findings))
        (is (= "a file\n" (slurp (str (fs/file dir "dhf"))))))))
  (testing "a directory that is a link"
    (let [dir (tmp)
          elsewhere (tmp)]
      (fs/create-sym-link (fs/file dir ".github") elsewhere)
      (let [{:keys [skipped]} (init/write-skeleton! dir (init/vars-for "x" "spec"))]
        (is (= [".github/workflows/cut.yml"] skipped))
        (is (empty? (fs/list-dir elsewhere)))))))
