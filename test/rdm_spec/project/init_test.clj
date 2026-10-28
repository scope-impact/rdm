(ns rdm-spec.project.init-test
  "One test per rule Init enforces (01-start-a-project/init.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [rdm-spec.project.init :as init]))

(deftest init-starts-a-project-from-nothing
  (let [files (into {} (init/skeleton "sauna" "spec"))]
    (is (= 15 (count files)))
    (is (= {:issued 0} (edn/read-string (files "dhf/spec/ids.edn"))))
    (is (= "spec" (:id-prefix (edn/read-string (files "dhf/harness/config.edn")))))
    (is (nil? (init/prefix-problem "sauna")))
    (is (init/prefix-problem "9lives"))
    (is (init/prefix-problem "UN"))))

(deftest a-new-project-is-green-before-anything-is-written-in-it
  (let [files (into {} (init/skeleton "sauna" "spec"))
        model (edn/read-string (files "dhf/spec/model.edn"))]
    (is (= #{:system} (set (keys model))))
    (is (:description (:system model)))
    (is (= [] (:implementation (edn/read-string (files "dhf/harness/config.edn")))))))
