(ns rdm-spec.spec.parser
  "Component Parser (spec context): reads the declared Gherkin subset and
  refuses anything outside it; reads the spec at a revision, every scenario
  with its id, tags, steps and owning component, and publishes SpecParsed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]))

;; ------------------------------------------------------------------ one file

(def unsupported-re #"^(Scenario Template|Scenarios|Example|Business Need|Ability|Background Outline)\s*:")

(defn- split-row [t]
  (let [inner (-> t str/trim (subs 1))
        inner (if (str/ends-with? inner "|") (subs inner 0 (dec (count inner))) inner)]
    (loop [cs (seq inner) cell (StringBuilder.) cells []]
      (if (empty? cs)
        (conj cells (str/trim (str cell)))
        (let [c (first cs)]
          (cond
            (and (= c \\) (second cs))
            (let [n (second cs)]
              (.append cell (case n \| "|" \n "\n" \\ "\\" (str "\\" n)))
              (recur (nnext cs) cell cells))
            (= c \|) (recur (next cs) (StringBuilder.) (conj cells (str/trim (str cell))))
            :else (do (.append cell c) (recur (next cs) cell cells))))))))

(defn- classify [line]
  (let [t (str/trim line)]
    (cond
      (str/blank? t) [:blank]
      (str/starts-with? t "#") [:comment]
      (str/starts-with? t "@") [:tags (vec (str/split (str/trim (str/replace t #"\s#.*$" "")) #"\s+"))]
      (str/starts-with? t "Feature:") [:feature (str/trim (subs t 8))]
      (str/starts-with? t "Background:") [:background (str/trim (subs t 11))]
      (str/starts-with? t "Rule:") [:rule (str/trim (subs t 5))]
      (str/starts-with? t "Scenario Outline:") [:outline (str/trim (subs t 17))]
      (str/starts-with? t "Scenario:") [:scenario (str/trim (subs t 9))]
      (str/starts-with? t "Examples:") [:examples (str/trim (subs t 9))]
      (re-find unsupported-re t) [:unknown (second (re-find unsupported-re t))]
      (re-find #"^(Given|When|Then|And|But) " t) (let [[_ k x] (re-find #"^(Given|When|Then|And|But) (.*)$" t)] [:step k (str/trim x)])
      (str/starts-with? t "|") [:row (split-row t)]
      (str/starts-with? t "\"\"\"") [:docstring "\"\"\""]
      (str/starts-with? t "```") [:docstring "```"]
      (re-find #"^\*\s" t) [:unknown "*"]
      :else [:text t])))

(defn- indent-of [line] (count (re-find #"^\s*" line)))

(defn parse-feature
  "Parses the Gherkin TEXT of FILE: {:feature {:name :line :tags} :scenarios
  [...] :refusals [{:file :line :rule :message}]}. A construct outside the
  subset is refused with its line, never skipped."
  [text file]
  (let [lines (str/split-lines (str text))
        refuse (fn [st n rule msg] (update st :refusals conj (project/finding file n rule msg)))
        close (fn [st]
                (let [cur (:current st)]
                  (cond-> (assoc st :current nil)
                    (= :scenario (:type cur)) (update :scenarios conj cur)
                    (= :background (:type cur))
                    (as-> s (if (:rule s)
                              (assoc-in s [:rule :background] (:steps cur))
                              (assoc s :background (:steps cur)))))))
        add-step-part (fn [st f]
                        (let [cur (:current st)]
                          (if (and cur (seq (:steps cur)) (not (:examples-started cur)))
                            (update-in st [:current :steps (dec (count (:steps cur)))] f)
                            nil)))
        st0 {:feature nil :rule nil :current nil :tags [] :tags-line nil :desc? false
             :scenarios [] :refusals [] :background nil :doc nil :after-table? false}
        st (reduce
            (fn [st [n line]]
              (if-let [{:keys [delim indent buf]} (:doc st)]
                (if (str/starts-with? (str/trim line) delim)
                  (-> (or (add-step-part st #(assoc % :docstring (str/join "\n" buf))) st)
                      (assoc :doc nil))
                  (assoc-in st [:doc :buf]
                            (conj buf (let [i (min indent (indent-of line))] (subs line (min i (count line)))))))
                (let [[kind a b] (classify line)
                      pending? (seq (:tags st))
                      take-tags (fn [st] (assoc st :tags [] :tags-line nil))
                      tags (:tags st)]
                  (if (and (nil? (:feature st)) (not (#{:blank :comment :tags :feature} kind)))
                    (refuse st n "gherkin-before-feature" (str "a line before the Feature: " (str/trim line)))
                    (case kind
                      (:blank :comment) st
                      :tags (if-let [bad (first (remove #(re-matches #"@[^\s@]+" %) a))]
                              (refuse st n "gherkin-tag-malformed" (str "the tag " bad " is not a tag"))
                              (-> st (update :tags into a) (update :tags-line #(or % n))))
                      :feature (if (:feature st)
                                 (refuse st n "gherkin-feature-twice"
                                         (str "a feature file holds exactly one feature; a second Feature is on line " n))
                                 (-> st (assoc :feature {:name a :line n :tags tags} :desc? true) take-tags))
                      :background (-> st close (assoc :current {:type :background :line n :steps []} :desc? true)
                                      (cond-> pending? (refuse n "gherkin-tag-misplaced" "a Background carries no tag"))
                                      take-tags)
                      :rule (-> st close (assoc :rule {:name a :line n :tags tags} :desc? true) take-tags)
                      (:scenario :outline)
                      (-> st close
                          (assoc :current {:type :scenario :title a :name a :line n :tags tags
                                           :keyword (if (= kind :outline) "Scenario Outline" "Scenario")
                                           :outline? (= kind :outline)
                                           :rule (:rule st) :feature-tags (get-in st [:feature :tags])
                                           :steps [] :examples []}
                                 :desc? true)
                          take-tags)
                      :examples (if (get-in st [:current :outline?])
                                  (-> st
                                      (update-in [:current :examples] conj {:line n :name a :tags tags :header nil :rows []})
                                      (assoc-in [:current :examples-started] true)
                                      (assoc :desc? true)
                                      take-tags)
                                  (refuse st n "gherkin-examples-misplaced" "Examples belong to a Scenario Outline"))
                      :unknown (cond-> (refuse st n "gherkin-keyword-unknown"
                                               (str "the keyword " a (when-not (= a "*") ":") " is not in the declared Gherkin subset"))
                                 (not= a "*") (-> close (assoc :current {:type :ignored :line n :steps []} :desc? true) take-tags))
                      :step (cond
                              pending? (-> st (refuse n "gherkin-tag-misplaced" "a step carries no tag") take-tags)
                              (nil? (:current st)) (refuse st n "gherkin-step-misplaced" (str "a step outside a scenario: " (str/trim line)))
                              (get-in st [:current :examples-started]) (refuse st n "gherkin-step-misplaced" "a step after Examples")
                              :else (-> st
                                        (update-in [:current :steps] conj {:keyword a :text b :line n})
                                        (assoc :desc? false)))
                      :row (let [cur (:current st)]
                             (cond
                               (:examples-started cur)
                               (let [i (dec (count (:examples cur)))
                                     ex (get-in cur [:examples i])]
                                 (if (nil? (:header ex))
                                   (-> st (assoc-in [:current :examples i :header] a) (assoc :desc? false))
                                   (if (not= (count a) (count (:header ex)))
                                     (refuse st n "gherkin-table-ragged" "a row with another number of cells than its heading")
                                     (update-in st [:current :examples i :rows] conj {:line n :cells a}))))
                               (and cur (seq (:steps cur)) (not (:desc? st)))
                               (let [r (add-step-part st (fn [s] (update s :table (fnil conj []) a)))
                                     t (:table (peek (get-in r [:current :steps])))]
                                 (if (and (> (count t) 1) (not= (count (first t)) (count a)))
                                   (refuse st n "gherkin-table-ragged" "a row with another number of cells than the table's first")
                                   r))
                               :else (refuse st n "gherkin-table-misplaced" "a table row outside a step or Examples")))
                      :docstring (if (and (get-in st [:current :steps]) (seq (get-in st [:current :steps])) (not (:desc? st)))
                                   (assoc st :doc {:delim a :indent (indent-of line) :buf [] :line n})
                                   (refuse st n "gherkin-docstring-misplaced" "a doc string outside a step"))
                      :text (if (and (:desc? st) (not pending?))
                              st
                              (refuse st n "gherkin-line-unexpected" (str "a line the Gherkin subset does not allow here: " (str/trim line)))))))))
            st0
            (map-indexed (fn [i l] [(inc i) l]) lines))
        st (cond-> st
             (:doc st) (refuse (get-in st [:doc :line]) "gherkin-docstring-unclosed" "a doc string is never closed")
             (seq (:tags st)) (refuse (:tags-line st) "gherkin-tag-misplaced" "tags that tag nothing"))
        st (close st)
        st (if (and (nil? (:feature st)) (not (str/blank? text)))
             (refuse st 1 "gherkin-feature-missing" "a feature file holds exactly one Feature, and this one holds none")
             st)
        bg (:background st)]
    {:file file
     :feature (:feature st)
     :refusals (:refusals st)
     :scenarios (vec (for [sc (:scenarios st)]
                       (let [rows (vec (for [ex (:examples sc) r (:rows ex)]
                                         {:line (:line r) :values (zipmap (:header ex) (:cells r))
                                          :cells (:cells r) :header (:header ex)}))]
                         (-> sc
                             (dissoc :type :examples-started)
                             (assoc :file file
                                    :feature-name (get-in st [:feature :name])
                                    :background (vec (concat bg (get-in sc [:rule :background])))
                                    :rows rows)))))}))

(defn substitute [s values]
  (reduce (fn [s [k v]] (str/replace s (str "<" k ">") (str v))) (str s) values))

(defn row-steps
  "An outline's steps, background first, for one example ROW."
  [scenario row]
  (let [vs (:values row)]
    (vec (for [st (concat (:background scenario) (:steps scenario))]
           (cond-> (update st :text substitute vs)
             (:table st) (update :table (fn [t] (mapv (fn [r] (mapv #(substitute % vs) r)) t)))
             (:docstring st) (update :docstring substitute vs))))))

(defn scenario-steps [scenario] (vec (concat (:background scenario) (:steps scenario))))

;; ------------------------------------------------------------------ the spec

(def levels #{"@e2e" "@contract"})

(defn need-tag? [t] (boolean (re-matches #"@UN-\d+" t)))

(defn id-re [prefix] (re-pattern (str "^@" (java.util.regex.Pattern/quote prefix) "-(\\d{4,})$")))

(defn feature-files
  "Every feature file of SPEC at TREE, found by looking, as paths relative to the tree."
  [tree spec]
  (let [d (project/abs tree (:dir spec))]
    (if (fs/directory? d)
      (->> (fs/glob d "**.feature")
           (map #(project/rel tree %))
           sort vec)
      [])))

(defn enrich
  "A parsed scenario with what the spec says of it: its ids, level, needs,
  planned mark and owning component."
  [cfg spec model sc]
  (let [re (id-re (:id-prefix spec))
        own (:tags sc)
        ids (vec (keep #(second (re-find re %)) own))
        ids (mapv #(str (:id-prefix spec) "-" %) ids)
        lvls (filterv levels own)
        needs (vec (distinct (map #(subs % 1) (filter need-tag? (concat (:feature-tags sc) (get-in sc [:rule :tags]) own)))))
        owner (when model (project/owner cfg spec model (:file sc)))]
    (assoc sc
           :id (when (= 1 (count ids)) (first ids))
           :ids ids
           :levels (mapv #(subs % 1) lvls)
           :level (when (= 1 (count lvls)) (subs (first lvls) 1))
           :needs needs
           :wip? (boolean (some #{"@wip"} own))
           :spec (:name spec)
           :slice (project/slice-of (:dir spec) (:file sc))
           :context (:context owner)
           :component (:id owner)
           :context-name (:context-name owner)
           :component-name (:name owner)
           :rule (get-in sc [:rule :name])
           :rule-tags (get-in sc [:rule :tags])
           :examples (vec (map (fn [r] (:values r)) (:rows sc))))))

(defn read-needs
  "The needs of SPEC at TREE: {:needs [{:id :text :sources}] :findings [...]}."
  [tree spec]
  (let [r (project/read-edn-file tree (:needs spec))]
    (cond
      (:missing r) {:needs [] :findings [(project/finding (:needs spec) "needs-unreadable" "the needs file is missing")]}
      (:error r) {:needs [] :findings [(project/finding (:needs spec) (get-in r [:error :line]) "needs-unreadable"
                                                        (str "the needs are not readable EDN: " (get-in r [:error :message])))]}
      (not (map? (:value r))) {:needs [] :findings [(project/finding (:needs spec) "needs-unreadable" "the needs are not a map")]}
      :else {:needs (vec (for [[k v] (sort-by key (:value r))]
                           {:id (str k) :text (:text v) :sources (:sources v)}))
             :findings []})))

(defn read-spec
  "Reads SPEC of the project at TREE: {:spec :files :features :scenarios :refusals :needs :needs-findings}."
  [tree cfg spec]
  (let [model (project/read-model tree spec)
        files (feature-files tree spec)
        parsed (vec (for [f files] (parse-feature (project/read-text tree f) f)))
        {:keys [needs findings]} (read-needs tree spec)]
    {:spec spec
     :model model
     :files files
     :features parsed
     :scenarios (vec (for [p parsed sc (:scenarios p)] (enrich cfg spec model sc)))
     :refusals (vec (mapcat :refusals parsed))
     :needs needs
     :needs-findings findings}))

(defn read-specs
  "Every spec the configuration declares, read at TREE."
  [tree cfg]
  (mapv #(read-spec tree cfg %) (project/specs cfg)))

(defn event-scenario [sc]
  (select-keys sc [:id :ids :title :name :keyword :file :line :level :needs :wip? :outline? :rows :examples
                   :background :rule :steps :context :component :context-name :component-name :spec :slice :tags]))

(defn publish!
  "Publishes SpecParsed for the specs read at REVISION."
  [revision read]
  (bus/publish! :spec/parser :SpecParsed
                {:revision revision
                 :scenarios (vec (for [r read sc (:scenarios r)] (event-scenario sc)))
                 :needs (vec (mapcat :needs read))
                 :refusals (vec (mapcat :refusals read))}))

(defn read-specs-once
  "Every spec read at TREE, once per run."
  [tree cfg]
  (project/cached [::specs tree] #(read-specs tree cfg)))

(defn parse-stage
  "Reads the spec in the working tree, refusing what is outside the Gherkin
  subset, and publishes SpecParsed."
  [{:keys [cfg]}]
  (let [read (read-specs-once (project/root) cfg)]
    (publish! :working-tree read)
    {:result {:spec {:scenarios (count (mapcat :scenarios read)) :feature-files (count (mapcat :files read))}}
     :findings (vec (mapcat :refusals read))
     :warnings []}))
