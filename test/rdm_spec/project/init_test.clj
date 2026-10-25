(ns rdm-spec.project.init-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.init :as init]))

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "init-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest the-id-prefix
  (is (nil? (init/prefix-problem "spec")))
  (is (nil? (init/prefix-problem "a-b2-c")))
  (is (str/includes? (init/prefix-problem "9lives") "starts with a letter"))
  (is (some? (init/prefix-problem "a--b")))
  (is (some? (init/prefix-problem "a-")))
  (is (str/includes? (init/prefix-problem "UN") "the prefix UN is taken by need tags")))

(deftest every-template-renders
  (let [vs (init/vars "Sauna 2" "sauna")]
    (is (= ":sauna-2" (get vs "system-id")))
    (doseq [e init/skeleton]
      (is (not (str/includes? (init/content e vs) "{{")) (:path e)))
    (let [cfg (edn/read-string (init/content {:template "config.edn.tmpl"} vs))]
      (is (= "sauna" (:id-prefix cfg)))
      (is (some #{"dhf/harness/stack.md"} (get-in cfg [:clean-room :allow]))))))

(deftest init-writes-the-skeleton-and-refuses-an-existing-path
  (with-tmp
    (fn [dir]
      (let [out (command/execute ["init" "sauna"] {:root dir})]
        (is (= 0 (:exit out)))
        (is (= (map :path init/skeleton) (get-in out [:result :init :wrote])))
        (is (fs/executable? (fs/file dir "sauna/.githooks/pre-commit"))))
      (let [out (command/execute ["init" "sauna"] {:root dir})]
        (is (= 2 (:exit out)))
        (is (str/includes? (:cannot-run out) "sauna")))
      (let [abs (str (fs/file dir "abs" "x"))
            out (command/execute ["init" abs "--id-prefix" "x"] {:root dir})]
        (is (= 0 (:exit out)))
        (is (fs/regular-file? (fs/file abs "dhf/harness/stack.md"))))
      (is (= 2 (:exit (command/execute ["init" "un" "--id-prefix" "UN"] {:root dir}))))
      (is (not (fs/exists? (fs/file dir "un")))))))
