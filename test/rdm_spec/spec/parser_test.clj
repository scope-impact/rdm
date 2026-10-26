(ns rdm-spec.spec.parser-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.parser :as parser]))

(def model "{:containers [{:id :app :contexts [{:id :architecture :name \"architecture\" :components [{:id :architecture/lint :name \"Lint\" :spec \"architecture/lint\"}]}]}] :kernel [{:id :kernel/bus :name \"Event bus\" :spec \"kernel/bus\"}]}")

(def lint "@UN-005\nFeature: Lint\n\n  @UN-002\n  Rule: Views\n\n    @spec-0012 @e2e\n    Scenario: Names the view\n      Given a view\n\n    @spec-0013 @contract @wip @UN-003\n    Scenario Outline: Rows <a>\n      Given <a>\n\n      Examples:\n        | a |\n        | 1 |\n")

(defn- project []
  (let [d (str (fs/create-temp-dir {:dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))}))]
    (fs/create-dirs (fs/path d "dhf/spec/architecture"))
    (fs/create-dirs (fs/path d "dhf/spec/kernel"))
    (spit (str d "/dhf/spec/model.edn") model)
    (spit (str d "/dhf/spec/needs.edn") "{\"UN-005\" {:text \"t\" :sources [\"a:b@c\"]}}")
    (spit (str d "/dhf/spec/architecture/lint.feature") lint)
    (spit (str d "/dhf/spec/kernel/bus.feature") "Feature: Bus\n  @e2e\n  Scenario: Unstamped\n    Given x\n")
    d))

(def config {:spec-dir "dhf/spec" :model "dhf/spec/model.edn" :needs "dhf/spec/needs.edn" :id-prefix "spec"})

(deftest parses-scenarios-with-their-owners
  (let [d (project)]
    (try
      (bus/start!)
      (let [r (parser/stage {:root d :config config})
            {:keys [scenarios needs]} (bus/latest :SpecParsed)
            [s12 s13 bus-s] (sort-by :file (sort-by :line scenarios))]
        (is (empty? (:findings r)))
        (is (= 3 (count scenarios)))
        (is (= [{:id "UN-005" :text "t" :sources ["a:b@c"]}] needs))
        (let [s12 (first (filter #(= "spec-0012" (:id %)) scenarios))
              s13 (first (filter #(= "spec-0013" (:id %)) scenarios))
              sb (first (filter #(nil? (:id %)) scenarios))]
          (is (= {:title "Names the view" :file "dhf/spec/architecture/lint.feature" :line 8 :level :e2e
                  :needs ["UN-005" "UN-002"] :wip? false :outline? false :rule "Views"
                  :context :architecture :component :architecture/lint :context-name "architecture" :component-name "Lint"}
                 (select-keys s12 [:title :file :line :level :needs :wip? :outline? :rule :context :component :context-name :component-name])))
          (is (= [{:keyword "Given" :text "a view"}] (:steps s12)))
          (is (:wip? s13))
          (is (:outline? s13))
          (is (= [["1"]] (:rows s13)))
          (is (= ["UN-005" "UN-002" "UN-003"] (:needs s13)))
          (is (= :kernel (:context sb)))
          (is (= [] (:ids sb)))))
      (finally (fs/delete-tree d)))))

(deftest reads-at-a-revision
  (let [d (project)
        g (fn [& a] (apply p/shell {:dir d :out :string :err :string :extra-env {"GIT_CEILING_DIRECTORIES" (str (fs/parent d))}} "git" "-c" "user.name=x" "-c" "user.email=x@x" "-c" "core.hooksPath=/dev/null" a))]
    (try
      (g "init" "-q") (g "add" "-A") (g "commit" "-q" "--no-verify" "-m" "one") (g "tag" "spec-1")
      (spit (str d "/dhf/spec/kernel/bus.feature") "Feature: Bus\n")
      (bus/start!)
      (parser/stage {:root d :config config :revision "spec-1"})
      (is (= 3 (count (:scenarios (bus/latest :SpecParsed)))))
      (is (= "spec-1" (:revision (bus/latest :SpecParsed))))
      (bus/start!)
      (parser/stage {:root d :config config})
      (is (= 2 (count (:scenarios (bus/latest :SpecParsed)))))
      (finally (fs/delete-tree d)))))
