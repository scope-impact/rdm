(ns rdm-spec.kernel.gherkin
  "Reads the declared Gherkin subset (dhf/spec/spec/parser.feature) into data.

  Kernel code: the Gherkin runner (kernel/runner) needs it, and the spec
  context's parser (spec/parser) may build on it rather than read Gherkin a
  second way. A construct outside the subset is refused, never skipped: each
  refusal is returned with its file, line and rule, and the rest of the file is
  still read.

  The subset: Feature (exactly one), Background (in a feature or a rule), Rule,
  Scenario, Scenario Outline, Examples, the steps Given, When, Then, And, But,
  tags, comments, doc strings (\"\"\" or ```) and data tables.

  `parse` returns
    {:file \"dhf/spec/kernel/runner.feature\"
     :feature {:name :line :tags [\"@UN-003\"] :description}
     :scenarios [scenario ...]           ; in file order
     :refusals [{:file :line :rule :message} ...]}
  where a scenario is
    {:name :line :keyword \"Scenario\"|\"Scenario Outline\"
     :tags [own tags] :all-tags [feature, rule and own tags, in that order]
     :rule {:name :line :tags} or nil
     :background [step ...]               ; feature background, then rule background
     :steps [step ...]
     :examples [{:line :tags :header [..] :rows [{:line :cells [..]}]}]} ; outlines only
  and a step is {:keyword \"Given\" :text :line :doc-string s? :data-table [[..]]?}.
  `expand` turns an outline into one concrete scenario per example row."
  (:require [clojure.string :as str]))

(def step-keywords ["Given" "When" "Then" "And" "But"])

(def ^:private block-keywords
  {"Feature" :feature "Background" :background "Rule" :rule
   "Scenario" :scenario "Scenario Outline" :outline "Examples" :examples})

(defn- refusal [file line rule message]
  {:file file :line line :rule rule :message message})

(defn- keyword-line
  "[keyword rest] when the trimmed line starts with Word: or Word Word: (a line
  that looks like a Gherkin keyword), else nil."
  [s]
  (when-let [[_ kw rest] (re-matches #"([A-Z][a-z]+(?: [A-Z][a-z]+)?):(?:\s+(.*)|\s*)" s)]
    [kw (str/trim (or rest ""))]))

(defn- step-line [s]
  (some (fn [kw]
          (when (and (str/starts-with? s kw)
                     (> (count s) (count kw))
                     (Character/isWhitespace (.charAt ^String s (count kw))))
            [kw (str/trim (subs s (count kw)))]))
        step-keywords))

(defn split-cells
  "The cells of a table row `| a | b \\| c |`, unescaping \\|, \\\\ and \\n."
  [s]
  (let [body (-> s str/trim (subs 1))
        body (if (str/ends-with? body "|") (subs body 0 (dec (count body))) body)]
    (loop [cs (seq body) cur (StringBuilder.) cells []]
      (if-not cs
        (mapv str/trim (conj cells (str cur)))
        (let [c (first cs)]
          (cond
            (and (= c \\) (second cs))
            (let [n (second cs)]
              (.append cur (case n \| \| \\ \\ \n \newline (str \\ n)))
              (recur (nnext cs) cur cells))
            (= c \|) (recur (next cs) (StringBuilder.) (conj cells (str cur)))
            :else (do (.append cur c) (recur (next cs) cur cells))))))))

(defn- strip-indent [line indent]
  (let [lead (count (take-while #(Character/isWhitespace ^char %) (take indent line)))]
    (subs line lead)))

(defn parse
  "Parses the Gherkin text of one feature file. `file` is how the file is named
  in refusals and in the result (a path relative to the project root)."
  [file text]
  (let [lines (str/split-lines text)
        n (count lines)]
    (loop [i 0
           st {:file file :feature nil :scenarios [] :refusals []
               :pending-tags [] :where :top ; :top :feature :background :rule :scenario :examples
               :rule nil :feature-bg [] :rule-bg [] :current nil :after-steps? false
               :last-step-target nil}]
      (if (>= i n)
        (let [st (cond-> st (:current st) (update :scenarios conj (:current st)))
              st (if (:feature st)
                   st
                   (update st :refusals conj (refusal file nil "gherkin-no-feature" "the file holds no Feature line")))]
          (select-keys st [:file :feature :scenarios :refusals]))
        (let [raw (nth lines i)
              line (inc i)
              s (str/trim raw)
              refuse (fn [st rule msg] (update st :refusals conj (refusal file line rule msg)))
              flush (fn [st] (cond-> (assoc st :current nil)
                               (:current st) (update :scenarios conj (:current st))))]
          (cond
            (str/blank? s) (recur (inc i) st)

            (str/starts-with? s "#") (recur (inc i) st)

            ;; doc string: read to its closing delimiter
            (or (str/starts-with? s "\"\"\"") (str/starts-with? s "```"))
            (let [delim (subs s 0 3)
                  indent (- (count raw) (count (str/triml raw)))
                  close (loop [j (inc i)]
                          (cond (>= j n) nil
                                (= delim (str/trim (nth lines j))) j
                                :else (recur (inc j))))]
              (if-not close
                (recur n (refuse st "gherkin-doc-string" "a doc string that is never closed"))
                (let [body (str/join "\n" (map #(strip-indent % indent) (subvec (vec lines) (inc i) close)))
                      target (:last-step-target st)]
                  (if (and target (= :scenario (:where st)) (:current st))
                    (recur (inc close)
                           (update-in st (into [:current] target)
                                      (fn [step]
                                        (if (or (:doc-string step) (:data-table step))
                                          (assoc step ::bad true)
                                          (assoc step :doc-string body)))))
                    (if (and target (#{:background} (:where st)))
                      (let [k (if (:rule st) :rule-bg :feature-bg)]
                        (recur (inc close) (update st k #(update % (dec (count %)) assoc :doc-string body))))
                      (recur (inc close) (refuse st "gherkin-doc-string" "a doc string that follows no step")))))))

            (str/starts-with? s "@")
            (let [s (str/trim (first (str/split s #"\s#" 2)))
                  tags (str/split s #"\s+")
                  bad (remove #(re-matches #"@[^@\s]+" %) tags)]
              (recur (inc i)
                     (cond-> (update st :pending-tags into (filter #(re-matches #"@[^@\s]+" %) tags))
                       (seq bad) (refuse "gherkin-tag" (str "not a tag: " (str/join " " bad))))))

            (str/starts-with? s "|")
            (let [cells (split-cells s)]
              (case (:where st)
                :examples
                (recur (inc i)
                       (update-in st [:current :examples]
                                  (fn [exs]
                                    (let [k (dec (count exs))
                                          ex (nth exs k)]
                                      (assoc exs k (if (:header ex)
                                                     (update ex :rows conj {:line line :cells cells})
                                                     (assoc ex :header cells)))))))
                (:scenario :background)
                (if-let [target (:last-step-target st)]
                  (if (= :scenario (:where st))
                    (recur (inc i) (update-in st (into [:current] target)
                                              #(if (:doc-string %) (assoc % ::bad true)
                                                   (update % :data-table (fnil conj []) cells))))
                    (let [k (if (:rule st) :rule-bg :feature-bg)]
                      (recur (inc i) (update st k #(update % (dec (count %)) update :data-table (fnil conj []) cells)))))
                  (recur (inc i) (refuse st "gherkin-table" "a table row that follows no step")))
                (recur (inc i) (refuse st "gherkin-table" "a table row outside a step or Examples"))))

            :else
            (if-let [[kw text] (step-line s)]
              (case (:where st)
                :scenario
                (let [idx (count (:steps (:current st)))]
                  (recur (inc i) (-> st
                                     (update-in [:current :steps] conj {:keyword kw :text text :line line})
                                     (assoc :last-step-target [:steps idx] :after-steps? true))))
                :background
                (let [k (if (:rule st) :rule-bg :feature-bg)]
                  (recur (inc i) (-> st (update k conj {:keyword kw :text text :line line})
                                     (assoc :last-step-target [:bg] :after-steps? true))))
                (recur (inc i) (refuse st "gherkin-step-outside-scenario"
                                       (str "the step \"" kw " " text "\" is outside a scenario or background"))))
              (if-let [[kw rest] (keyword-line s)]
                (let [kind (block-keywords kw)
                      tags (:pending-tags st)
                      st (assoc st :pending-tags [] :last-step-target nil :after-steps? false)]
                  (case kind
                    nil (recur (inc i) (refuse st "gherkin-unknown-keyword"
                                               (str "unknown keyword \"" kw ":\"; the subset is Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But")))
                    :feature
                    (if (:feature st)
                      (recur (inc i) (refuse (flush st) "gherkin-one-feature"
                                             (str "a second Feature line; a feature file holds exactly one feature (the first is on line "
                                                  (:line (:feature st)) ")")))
                      (recur (inc i) (assoc st :feature {:name rest :line line :tags tags :description nil}
                                            :where :feature)))
                    :background
                    (if (or (not (:feature st)) (some #(= :scenario %) [(:where st)]) (:current st)
                            (and (:rule st) (seq (:rule-bg st))) (and (not (:rule st)) (seq (:feature-bg st))))
                      (recur (inc i) (refuse (flush st) "gherkin-background" "a Background must come before the scenarios of its feature or rule, once"))
                      (recur (inc i) (assoc (flush st) :where :background)))
                    :rule
                    (if-not (:feature st)
                      (recur (inc i) (refuse st "gherkin-rule" "a Rule before the Feature line"))
                      (recur (inc i) (assoc (flush st) :where :rule :rule {:name rest :line line :tags tags} :rule-bg [])))
                    (:scenario :outline)
                    (if-not (:feature st)
                      (recur (inc i) (refuse st "gherkin-scenario" "a Scenario before the Feature line"))
                      (let [st (flush st)
                            rule (:rule st)]
                        (recur (inc i)
                               (assoc st :where :scenario
                                      :current {:name rest :line line :keyword kw :tags tags
                                                :all-tags (vec (distinct (concat (:tags (:feature st)) (:tags rule) tags)))
                                                :rule rule
                                                :background (vec (concat (:feature-bg st) (:rule-bg st)))
                                                :steps []
                                                :examples (when (= kind :outline) [])}))))
                    :examples
                    (if (and (:current st) (= "Scenario Outline" (:keyword (:current st))))
                      (recur (inc i) (-> st (assoc :where :examples)
                                         (update-in [:current :examples] conj {:line line :tags tags :header nil :rows []})))
                      (recur (inc i) (refuse st "gherkin-examples" "Examples outside a Scenario Outline")))))
                ;; free text: a description, allowed only before the first step of a block
                (cond
                  (:after-steps? st)
                  (recur (inc i) (refuse st "gherkin-unknown-line" (str "a line that is no step, table, tag or comment: \"" s "\"")))
                  (= :top (:where st))
                  (recur (inc i) (refuse st "gherkin-unknown-line" (str "text before the Feature line: \"" s "\"")))
                  (= :examples (:where st))
                  (recur (inc i) (refuse st "gherkin-unknown-line" (str "text in Examples: \"" s "\"")))
                  (= :feature (:where st))
                  (recur (inc i) (update-in st [:feature :description] #(if % (str % "\n" s) s)))
                  (= :rule (:where st))
                  (recur (inc i) (update-in st [:rule :description] #(if % (str % "\n" s) s)))
                  ;; In a scenario or a background every line is a step, a
                  ;; table, a doc string, a tag or a comment: free text there
                  ;; could be a step the parser would otherwise silently drop.
                  :else
                  (recur (inc i) (refuse st "gherkin-unknown-line"
                                         (str "a line that is no step, table, tag or comment: \"" s "\""))))))))))))

(defn- post-check
  "Refusals the line reader cannot see: a step given both a doc string and a
  table, an outline with no examples, an Examples block with no rows."
  [{:keys [file scenarios] :as parsed}]
  (let [extra (for [sc scenarios
                    r (concat
                       (for [st (concat (:background sc) (:steps sc)) :when (::bad st)]
                         (refusal file (:line st) "gherkin-step-argument" "a step may carry one doc string or one table, not more"))
                       (when (and (:examples sc) (empty? (mapcat :rows (:examples sc))))
                         [(refusal file (:line sc) "gherkin-outline" "a Scenario Outline with no example rows")])
                       (when (and (nil? (:examples sc)) false) []))]
                r)]
    (-> parsed
        (update :refusals into extra)
        (update :scenarios (fn [scs] (mapv (fn [sc] (-> sc
                                                         (update :steps (partial mapv #(dissoc % ::bad)))
                                                         (update :background (partial mapv #(dissoc % ::bad)))))
                                           scs))))))

(defn parse-feature
  "Parses one feature file's text; see the namespace doc for the shape."
  [file text]
  (-> (parse file text) post-check (update :refusals #(vec (sort-by (fn [r] (or (:line r) 0)) %)))))

(defn- substitute [s row]
  (when s
    (reduce-kv (fn [acc k v] (str/replace acc (str "<" k ">") v)) s row)))

(defn expand
  "The concrete scenarios of one parsed scenario: itself, or for an outline one
  per example row, each with :example {:index n :line :values {header value}}
  and its name, steps, doc strings and tables substituted."
  [sc]
  (if-not (:examples sc)
    [sc]
    (vec
     (map-indexed
      (fn [idx [ex row]]
        (let [values (zipmap (:header ex) (:cells row))
              sub-step (fn [st] (cond-> (update st :text substitute values)
                                  (:doc-string st) (update :doc-string substitute values)
                                  (:data-table st) (update :data-table (fn [t] (mapv (fn [r] (mapv #(substitute % values) r)) t)))))]
          (-> sc
              (assoc :name (substitute (:name sc) values)
                     :example {:index (inc idx) :line (:line row) :values values
                               :header (:header ex)}
                     :all-tags (vec (distinct (concat (:all-tags sc) (:tags ex)))))
              (update :steps (partial mapv sub-step))
              (dissoc :examples))))
      (for [ex (:examples sc) row (:rows ex)] [ex row])))))
