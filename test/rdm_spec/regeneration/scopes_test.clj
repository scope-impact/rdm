(ns rdm-spec.regeneration.scopes-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:id :c :contexts [{:id :architecture
                                    :components [{:id :architecture/views :spec "architecture/views"}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"}]})

(def rules {:owner-map (scopes/owners model) :spec-dir "dhf/spec"
            :allow ["dhf/spec" "dhf/wiki" "dhf/harness/config.edn"]})

(deftest a-components-files-are-its-scope
  (is (= :architecture/views (scopes/owner (:owner-map rules) "dhf/spec" "dhf/spec/architecture/views.feature")))
  (is (= :architecture/views (scopes/owner (:owner-map rules) "dhf/spec" "dhf/spec/architecture/views.md")))
  (is (nil? (scopes/owner (:owner-map rules) "dhf/spec" "dhf/spec/architecture/views.edn")))
  (is (= {:scopes [{:component :architecture/views} {:component :kernel/bus}] :shared []}
         (scopes/classify rules ["dhf/spec/architecture/views.feature" "dhf/spec/architecture/views.md"
                                 "dhf/spec/kernel/bus.feature"]))))

(deftest shared-files-and-other-files
  (is (= {:scopes [] :shared ["dhf/spec/events.edn" "dhf/spec/glossary.md" "dhf/wiki/index.md"]}
         (scopes/classify rules ["dhf/spec/events.edn" "dhf/spec/glossary.md" "dhf/wiki/index.md"
                                 "src/rdm_spec/x.clj" "dhf/cycles/03/cycle.edn"]))))
