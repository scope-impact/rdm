(ns rdm-spec.project.init-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.project.init :as init]))

(deftest prefixes
  (is (nil? (init/prefix-problem "spec")))
  (is (nil? (init/prefix-problem "my-app2")))
  (is (init/prefix-problem "9lives"))
  (is (init/prefix-problem "a--b"))
  (is (init/prefix-problem "UN")))

(deftest the-skeleton
  (let [files (init/skeleton "sauna" "spec")]
    (is (= 15 (count files)))
    (is (= {:issued 0} (edn/read-string (files "dhf/spec/ids.edn"))))
    (is (= "spec" (:id-prefix (edn/read-string (files "dhf/harness/config.edn")))))
    (is (str/includes? (files ".githooks/pre-commit") "rdm-spec check"))))
