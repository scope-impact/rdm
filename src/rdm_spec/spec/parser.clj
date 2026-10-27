(ns rdm-spec.spec.parser
  "The parser (dhf/spec/spec/parser.feature): reads the declared subset of
  Gherkin (Feature, Background, Rule, Scenario, Scenario Outline, Examples,
  Given, When, Then, And, But, tags, comments, doc strings and data tables)
  and refuses anything outside it, never skipping it. It reads the spec at a
  revision (a cut) or in the working tree, each spec the project keeps from
  its own folder, and publishes SpecParsed."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def subset "Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But")

(def header-re #"^(Feature|Background|Rule|Scenario Outline|Scenario|Examples):\s*(.*)$")
(def step-re #"^(Given|When|Then|And|But) (.*)$")
(def keyword-like-re #"^([A-Z][A-Za-z]*(?: [A-Za-z]+){0,2}):(?:\s.*)?$")
(def gherkin-words #{"Feature" "Background" "Rule" "Scenario" "Scenarios" "Example" "Examples" "Ability"
                     "Business" "Given" "When" "Then" "And" "But"})

(defn ids-of
  "The scenario ids among tags: <prefix>-NNNN."
  [tags prefix]
  (let [re (re-pattern (str (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}"))]
    (vec (filter #(re-matches re %) tags))))

(defn- cells [t]
  (let [inner (-> t str/trim (subs 1))
        inner (if (str/ends-with? inner "|") (subs inner 0 (dec (count inner))) inner)]
    (mapv #(str/replace (str/trim %) "\\|" "|") (str/split inner #"(?<!\\)\|" -1))))

(defn- refusal [file line rule message] (cmd/finding file line rule message))

(defn- finish-scenario
  "A scenario as the rest of rdm-spec reads it."
  [s feature rule background prefix]
  (let [own (:tags s)
        all (vec (distinct (concat (:tags feature) (:tags rule) own)))
        ids (ids-of own prefix)
        levels (filterv #{"e2e" "contract"} own)
        outline? (or (= "Scenario Outline" (:keyword s)) (boolean (seq (:examples s))))]
    (assoc s
           :id (when (= 1 (count ids)) (first ids))
           :ids ids
           :name (:title s)
           :level (when (= 1 (count levels)) (keyword (first levels)))
           :levels levels
           :needs (vec (distinct (filter #(re-matches #"UN-\d+" %) all)))
           :all-tags all
           :wip? (boolean (some #{"wip"} own))
           :outline? outline?
           :rows (vec (mapcat :rows (:examples s)))
           :rule (:name rule)
           :rule-tags (:tags rule)
           :feature (:name feature)
           :feature-tags (:tags feature)
           :background (vec (concat (:background feature) (:background rule))))))

(defn parse-feature
  "Reads one feature file's text: {:file :feature :scenarios :refusals}."
  ([file text] (parse-feature file text "spec"))
  ([file text prefix]
   (let [lines (str/split-lines (str text))
         refuse (fn [st line rule msg] (update st :refusals conj (refusal file line rule msg)))
         st0 {:feature nil :rule nil :scenario nil :scenarios [] :refusals [] :tags [] :tag-line nil
              :mode :start :doc nil}
         close-scenario (fn [st] (if-let [s (:scenario st)]
                                   (-> st (update :scenarios conj (assoc s ::rule (:rule st))) (assoc :scenario nil))
                                   st))
         ;; where steps go: the scenario's, or a background's
         add-step (fn [st step]
                    (case (:mode st)
                      (:background :background-steps)
                      (if (:rule st)
                        (-> st (update-in [:rule :background] (fnil conj []) step) (assoc :mode :background-steps))
                        (-> st (update-in [:feature :background] (fnil conj []) step) (assoc :mode :background-steps)))
                      (:scenario-desc :steps)
                      (-> st (update-in [:scenario :steps] conj step) (assoc :mode :steps))
                      nil))
         last-step-path (fn [st]
                          (case (:mode st)
                            :background-steps (if (:rule st)
                                                [:rule :background (dec (count (get-in st [:rule :background])))]
                                                [:feature :background (dec (count (get-in st [:feature :background])))])
                            :steps [:scenario :steps (dec (count (get-in st [:scenario :steps])))]
                            nil))
         st (reduce
             (fn [st [i raw]]
               (let [n (inc i)
                     t (str/trim raw)]
                 (if-let [doc (:doc st)]
                   (if (= t (:delim doc))
                     (let [indent (:indent doc)
                           body (str/join "\n" (map #(if (str/blank? (subs % 0 (min indent (count %))))
                                                       (subs % (min indent (count %)))
                                                       (str/triml %))
                                                    (:lines doc)))]
                       (-> st (assoc-in (conj (:path doc) :doc) body) (assoc :doc nil)))
                     (update-in st [:doc :lines] conj raw))
                   (cond
                     (str/blank? t) st
                     (str/starts-with? t "#") st
                     (str/starts-with? t "@")
                     (let [ws (str/split (str/replace t #"\s+#.*$" "") #"\s+")]
                       (if (every? #(re-matches #"@\S+" %) ws)
                         (-> st (update :tags into (map #(subs % 1) ws)) (update :tag-line #(or % n)))
                         (refuse st n "gherkin-tag-malformed" (str "the tag line \"" t "\" holds a word that is not a tag"))))
                     :else
                     (let [tags (:tags st) tag-line (:tag-line st)
                           st' (assoc st :tags [] :tag-line nil)]
                       (if-let [[_ kw title] (re-matches header-re t)]
                         (case kw
                           "Feature"
                           (if (:feature st')
                             (refuse st' n "gherkin-feature-twice"
                                     (str "a feature file holds exactly one feature: a second Feature on line " n))
                             (assoc st' :feature {:name title :tags tags :line n :background []} :mode :feature-desc))
                           "Background"
                           (if (:feature st')
                             (-> (close-scenario st') (assoc :mode :background))
                             (refuse st' n "gherkin-outside-feature" "a Background before any Feature"))
                           "Rule"
                           (if (:feature st')
                             (-> (close-scenario st') (assoc :rule {:name title :tags tags :line n :background []} :mode :rule-desc))
                             (refuse st' n "gherkin-outside-feature" "a Rule before any Feature"))
                           ("Scenario" "Scenario Outline")
                           (if (:feature st')
                             (-> (close-scenario st')
                                 (assoc :scenario {:file file :line n :keyword kw :title title :tags tags :tag-line tag-line
                                                   :steps [] :examples []}
                                        :mode :scenario-desc))
                             (refuse st' n "gherkin-outside-feature" (str "a " kw " before any Feature")))
                           "Examples"
                           (if (:scenario st')
                             (-> st' (update-in [:scenario :examples] conj {:line n :tags tags :tag-line tag-line :headings nil :rows []})
                                 (assoc :mode :examples))
                             (refuse st' n "gherkin-examples-outside" "Examples outside a scenario outline")))
                         (if-let [[_ kw text] (re-matches step-re t)]
                           (or (add-step st' {:keyword kw :text text :line n})
                               (refuse st' n "gherkin-step-outside" (str "the step \"" t "\" is outside a scenario or a background")))
                           (cond
                             (str/starts-with? t "|")
                             (case (:mode st')
                               :examples
                               (let [k (dec (count (get-in st' [:scenario :examples])))]
                                 (if (nil? (get-in st' [:scenario :examples k :headings]))
                                   (assoc-in st' [:scenario :examples k :headings] (cells t))
                                   (update-in st' [:scenario :examples k :rows] conj (cells t))))
                               (:steps :background-steps)
                               (update-in st' (conj (last-step-path st') :table) (fnil conj []) (cells t))
                               (refuse st' n "gherkin-table-outside" "a table row that belongs to no step and no Examples"))
                             (or (str/starts-with? t "\"\"\"") (str/starts-with? t "```"))
                             (if-let [p (last-step-path st')]
                               (assoc st' :doc {:delim (subs t 0 3) :indent (- (count raw) (count (str/triml raw)))
                                                :lines [] :path p})
                               (refuse st' n "gherkin-doc-string-outside" "a doc string that belongs to no step"))
                             :else
                             (let [kl (re-matches keyword-like-re t)
                                   word (when kl (first (str/split (second kl) #" ")))]
                               (cond
                                 (and kl (or (gherkin-words word) (#{:steps :examples :background-steps} (:mode st'))))
                                 (refuse st' n "gherkin-keyword-unknown"
                                         (str "the keyword \"" (second kl) "\" is not in the declared subset of Gherkin: " subset))
                                 (#{:feature-desc :rule-desc :scenario-desc :background} (:mode st'))
                                 st'
                                 :else
                                 (refuse st' n "gherkin-line-unknown"
                                         (str "the line \"" t "\" is not a step, a table, a doc string, a tag or a comment of the declared subset of Gherkin"))))))))))))
             st0
             (map-indexed vector lines))
         st (cond-> (close-scenario st)
              (:doc st) (refuse (count lines) "gherkin-doc-string-open" "a doc string is never closed"))
         feature (:feature st)]
     {:file file
      :feature feature
      :scenarios (vec (for [s (:scenarios st)]
                        (dissoc (finish-scenario s feature (::rule s) nil prefix) ::rule)))
      :refusals (vec (concat (when (and (nil? feature) (seq (remove str/blank? lines)))
                               [(refusal file nil "gherkin-no-feature" "the file holds no Feature")])
                             (:refusals st)))})))

;; ---------------------------------------------------------------------------
;; The spec at a revision

(defn read-model [root rev config]
  (try (let [m (cmd/read-edn (cmd/read-at root rev (:model config "dhf/spec/model.edn")))] (when (map? m) m))
       (catch Exception _ nil)))

(defn component-index
  "{feature-path {:context :component :context-name :component-name}} from the model."
  [model spec-dir]
  (into {}
        (concat
         (for [c (:containers model) cx (:contexts c) k (:components cx) :when (:spec k)]
           [(str spec-dir "/" (:spec k) ".feature")
            {:context (:id cx) :component (:id k) :context-name (str (:name cx (name (:id cx)))) :component-name (:name k)}])
         (for [k (:kernel model) :when (:spec k)]
           [(str spec-dir "/" (:spec k) ".feature")
            {:context :kernel :component (:id k) :context-name "kernel" :component-name (:name k)}]))))

(defn- by-path [file spec-dir]
  (let [rel (-> file (subs (inc (count spec-dir))) (str/replace #"\.feature$" ""))
        [c k] (str/split rel #"/" 2)
        k (or k c)]
    {:context (keyword c) :component (keyword c k) :context-name c :component-name k}))

(defn read-needs [root rev config]
  (let [m (try (cmd/read-edn (cmd/read-at root rev (:needs config "dhf/spec/needs.edn"))) (catch Exception _ nil))]
    (if (map? m)
      (vec (for [[id v] (sort-by (comp str key) m)]
             {:id (name id) :text (:text v) :sources (vec (:sources v))}))
      [])))

(defn read-spec
  "The spec at a revision (nil: the working tree), as the configuration
  names it: {:scenarios :refusals :files :needs :model}."
  [root rev config]
  (let [spec-dir (:spec-dir config "dhf/spec")
        prefix (:id-prefix config "spec")
        files (filterv #(str/ends-with? % ".feature") (cmd/files-at root rev spec-dir))
        model (read-model root rev config)
        index (component-index model spec-dir)
        parsed (for [f files] (parse-feature f (cmd/read-at root rev f) prefix))]
    {:files files
     :model model
     :features (vec parsed)
     :scenarios (vec (for [p parsed s (:scenarios p)]
                       (merge s (or (index (:file s)) (by-path (:file s) spec-dir)) {:spec (:name config)})))
     :refusals (vec (mapcat :refusals parsed))
     :needs (read-needs root rev config)}))

(defn parse!
  "Reads the spec at a revision and publishes SpecParsed."
  [root rev config]
  (let [spec (read-spec root rev config)]
    (bus/publish! :spec/parser :SpecParsed
                  {:revision (if (cmd/working-tree? rev) :working-tree rev)
                   :scenarios (:scenarios spec) :needs (:needs spec) :refusals (:refusals spec)})
    spec))

(defn stage
  "Reads the spec; its refusals are the spec check's findings."
  [{:keys [root config revision]}]
  (parse! root revision config)
  {})

(defn line-in
  "The line (from 1) of text that first holds s, or nil."
  [text s]
  (some (fn [[i l]] (when (str/includes? l s) (inc i))) (map-indexed vector (str/split-lines (str text)))))
