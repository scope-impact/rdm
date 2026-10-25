(ns steps.spec-support
  "Fixtures for the spec context's step definitions (spec_parser, spec_stamper,
  spec_check): small projects the scenario makes, never the room.

  A spec project is a directory holding the room's configuration (without
  :oracle and :regulated), a model whose kernel components own the fixture's
  feature files (dhf/spec/kernel/<name>.feature: no bounded context, so no
  view is needed for the whole check to pass), five declared needs citing a
  recorded source, a ledger, and the feature files the scenario writes.

  Context keys set by this context's steps:
    :dir        the project
    :file       the feature file a step wrote or changed (relative to :dir)
    :line       the line a step is about
    :scenario   {:id :title :file :line} of the scenario a step is about
    :ledger-before  the ledger's text before the run
    :title      the title of the scenario a later step looks for"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [steps.support :as s]))

(def spec-ns ['rdm-spec.spec.parser 'rdm-spec.spec.stamper 'rdm-spec.spec.check])

(defn needs-spec! [] (apply s/needs-built! spec-ns))

(def default-needs
  (into (sorted-map)
        (for [i (range 1 6)]
          [(format "UN-%03d" i) {:text (str "A fixture need, number " i ".")
                                 :sources ["demo:README.md@abc1234"]}])))

(defn- model-text [components]
  (pr-str {:system {:id :sauna :name "Sauna" :description "Books sauna turns"}
           :containers [{:id :app :name "App" :kind :runs :technology "A program"
                         :description "Takes the bookings"}]
           :kernel (vec (for [c components]
                          {:id (keyword c) :name (str/capitalize c) :description (str "The " c " of the fixture")
                           :spec (str "kernel/" c)}))}))

(defn feature-rel [component] (str "dhf/spec/kernel/" component ".feature"))

(defn scenario-text
  "A scenario: {:tags [\"@spec-0001\" \"@e2e\"] :title} as feature text, two
  spaces in."
  [{:keys [tags title]}]
  (str (when (seq tags) (str "  " (str/join " " tags) "\n"))
       "  Scenario: " title "\n"
       "    Given a fixture\n"
       "    When \"rdm-spec check\" is run\n"
       "    Then it passes\n"))

(defn feature-text
  "A feature file: its tags (default @UN-001), name and scenarios."
  ([nm scenarios] (feature-text nm ["@UN-001"] scenarios))
  ([nm tags scenarios]
   (str "# A fixture feature file.\n\n"
        (when (seq tags) (str (str/join " " tags) "\n"))
        "Feature: " nm "\n  A fixture.\n\n"
        (str/join "\n" (map scenario-text scenarios)))))

(defn write-feature!
  "Writes the feature file of `component` in the project `dir`."
  [dir component text]
  (s/write! dir (feature-rel component) text))

(defn write-model!
  "The project's model, its kernel components owning `components`' feature files."
  [dir components]
  (s/write! dir "dhf/spec/model.edn" (str (model-text components) "\n")))

(defn write-needs! [dir needs] (s/write! dir "dhf/spec/needs.edn" (str (pr-str needs) "\n")))

(defn write-ledger! [dir n] (s/write! dir "dhf/spec/ids.edn" (str ";; The id ledger.\n{:issued " n "}\n")))

(defn ledger-of [dir] (:issued (edn/read-string (slurp (str (fs/file dir "dhf/spec/ids.edn"))))))

(defn spec-project!
  "A new spec project: `features` {component text}, the ledger at `ledger`,
  the declared `needs` and configuration `overrides`."
  [{:keys [features ledger needs overrides] :or {ledger 10 needs default-needs}}]
  (needs-spec!)
  (let [dir (s/project! (merge {:stack-words (:stack-words (s/room-config))} overrides))
        cfg (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn"))))]
    (s/write! dir "dhf/harness/config.edn" (pr-str (dissoc cfg :regulated)))
    (write-model! dir (sort (keys features)))
    (s/write! dir "dhf/spec/events.edn" "{}\n")
    (write-needs! dir needs)
    (write-ledger! dir ledger)
    (s/write! dir "dhf/raw/sources.edn"
              (pr-str {:demo {:repository "https://example.invalid/demo.git" :record "demo.md"
                              :pins ["abc1234"] :fenced []}}))
    (s/write! dir "dhf/raw/demo.md" "# demo\n\nA fixture source.\n")
    (doseq [[c text] features] (write-feature! dir c text))
    dir))

(defn parse
  "The feature file `rel` of the project `dir`, read by the kernel's reader."
  [dir rel]
  (gherkin/parse-feature rel (slurp (str (fs/file dir rel)))))

(defn scenarios-of
  "Every scenario of every feature file under the project's dhf/spec:
  [{:file :line :title :tags}]."
  [dir]
  (vec (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
             :let [rel (str (fs/relativize dir f))]
             sc (:scenarios (parse dir rel))]
         {:file rel :line (:line sc) :title (:name sc) :tags (:tags sc)})))

(defn scenario-titled!
  "The scenario titled `title` in the project `dir`; the step fails when
  there is not exactly one."
  [dir title]
  (let [scs (filter #(= title (:title %)) (scenarios-of dir))]
    (s/check! (= 1 (count scs)) (str (count scs) " scenarios are titled " (pr-str title)))
    (first scs)))

(defn line-of
  "The line number of the first line of `text` that, trimmed, starts with `start`."
  [text start]
  (some (fn [[i l]] (when (str/starts-with? (str/trim l) start) (inc i)))
        (map-indexed vector (str/split-lines text))))

(defn finding-naming
  "The first finding line of the run naming every one of `parts`, or nil."
  [lines parts]
  (first (filter (fn [l] (every? #(str/includes? l (str %)) parts)) lines)))
