(ns rdm-spec.spec.parser
  "The parser (dhf/spec/spec/parser.feature): reads the spec at a revision, in
  the declared subset of Gherkin, refusing anything outside it, and publishes
  SpecParsed: every scenario with its id, tags, steps and owning component."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.kernel.runner :as runner]))

(def levels #{"e2e" "contract"})

(defn id-re [prefix] (re-pattern (str (java.util.regex.Pattern/quote prefix) "-\\d{4,}")))

(defn read-model
  "The model at a revision, or nil when it is missing or not EDN."
  [root rev config]
  (try (cmd/read-edn (cmd/read-at root rev (:model config "dhf/spec/model.edn")))
       (catch Exception _ nil)))

(defn owners
  "{feature-file {:context :component :context-name :component-name}} from the model."
  [model spec-dir]
  (into {}
        (concat
         (for [cn (:containers model) cx (:contexts cn) c (:components cx) :when (:spec c)]
           [(str spec-dir "/" (:spec c) ".feature")
            {:context (:id cx) :component (:id c) :context-name (:name cx) :component-name (:name c)}])
         (for [c (:kernel model) :when (:spec c)]
           [(str spec-dir "/" (:spec c) ".feature")
            {:context :kernel :component (:id c) :context-name "kernel" :component-name (:name c)}]))))

(defn read-needs
  "The declared needs, [{:id :text :sources}], in id order; nil when unreadable."
  [root rev config]
  (try
    (when-let [m (cmd/read-edn (cmd/read-at root rev (:needs config "dhf/spec/needs.edn")))]
      (->> m (map (fn [[k v]] {:id (name k) :text (:text v) :sources (vec (:sources v))})) (sort-by :id) vec))
    (catch Exception _ nil)))

(defn scenarios-of
  "The scenarios of one read feature, in the shape SpecParsed carries."
  [feature owner prefix]
  (let [re (id-re prefix)]
    (vec
     (for [sc (:scenarios feature)]
       (let [own (map :tag (:tags sc))
             all (runner/tags-of feature sc)
             ids (filterv #(re-matches re %) own)
             lv (filterv levels own)]
         (merge
          {:id (when (= 1 (count ids)) (first ids))
           :ids ids
           :title (:name sc) :name (:name sc) :keyword (:keyword sc)
           :file (:file feature) :line (:line sc)
           :level (when (= 1 (count lv)) (keyword (first lv)))
           :levels lv
           :tags (vec own)
           :tag-lines (mapv (fn [t] [(:tag t) (:line t)]) (:tags sc))
           :feature-tags (mapv :tag (get-in feature [:feature :tags]))
           :rule-tags (mapv :tag (get-in sc [:rule :tags]))
           :needs (vec (filter #(re-matches #"UN-\d+" %) all))
           :wip? (boolean (some #{"wip"} own))
           :outline? (= "Scenario Outline" (:keyword sc))
           :rows (vec (for [ex (:examples sc) r (:rows ex)] (:cells r)))
           :row-lines (vec (for [ex (:examples sc) r (:rows ex)] (:line r)))
           :examples (mapv #(select-keys % [:headings :rows :line]) (:examples sc))
           :background (mapv #(select-keys % [:keyword :text]) (:background sc))
           :rule (get-in sc [:rule :name])
           :feature (get-in feature [:feature :name])
           :steps (mapv #(select-keys % [:keyword :text]) (:steps sc))}
          (or owner {:context nil :component nil :context-name nil :component-name nil})))))))

(defn parse
  "Reads the spec at `rev` (nil for the working tree). Returns {:revision
  :scenarios :needs :refusals :features}."
  [root rev config]
  (let [spec-dir (:spec-dir config "dhf/spec")
        files (filter #(str/ends-with? % ".feature") (cmd/files-at root rev spec-dir))
        own (owners (read-model root rev config) spec-dir)
        features (mapv #(runner/read-feature % (cmd/read-at root rev %)) files)]
    {:revision (if (cmd/working-tree? rev) :working-tree rev)
     :features features
     :scenarios (vec (mapcat #(scenarios-of % (own (:file %)) (:id-prefix config "spec")) features))
     :needs (or (read-needs root rev config) [])
     :refusals (vec (mapcat :refusals features))}))

(defn stage
  "The parser's stage: reads the spec at the context's revision and publishes SpecParsed."
  [{:keys [root config revision]}]
  (let [p (parse root revision config)]
    (bus/publish! :spec/parser :SpecParsed (select-keys p [:revision :scenarios :needs :refusals]))
    {:findings (:refusals p)}))
