(ns rdm-spec.kernel.gherkin
  "The kernel's Gherkin reader: the one reading of a feature file, shared by
  the Gherkin runner (rdm-spec.kernel.runner) and the spec context's Parser,
  so a feature file is never read two ways.

  It reads the declared subset (dhf/spec/spec/parser.feature): Feature,
  Background (of the feature or of a rule), Rule, Scenario, Scenario Outline,
  Examples, the steps Given, When, Then, And and But, tags, comments, doc
  strings and data tables, and descriptions under a feature, rule,
  background, scenario or examples. Anything else is refused, never skipped:
  a scenario the reader silently dropped would be a requirement nothing
  checks.

  `parse-feature` returns

    {:feature   {:line :name :tags [\"@t\"] :description} or nil
     :scenarios [{:keyword \"Scenario\" | \"Scenario Outline\"
                  :name :line
                  :tags      [tag]            ; its own
                  :all-tags  [tag]            ; the feature's, the rule's, its own
                  :rule      {:name :line :tags} or nil
                  :background [step]          ; the feature's, then the rule's
                  :steps     [step]
                  :examples  [{:line :tags :header [cell] :rows [{:line :cells}]}]}]
     :refusals  [{:file :line :rule :message :severity :error}]}

  a step being {:keyword :text :line} with :doc-string (its text, indented
  as written relative to its opening quotes) or :data-table ([[cell]]) when
  it has one.

  Refusals, each a stable rule name:
    gherkin-unknown-keyword   a line that starts a construct outside the subset
                              (Scenario Template:, Example:, Scenarios:, a * step ...)
    gherkin-one-feature       a second Feature in one file
    gherkin-no-feature        a construct before any Feature line
    gherkin-misplaced         a construct where the subset does not allow it
                              (a step outside a scenario or background, Examples
                              outside an outline, a table row or doc string with
                              nothing to belong to, text among steps)
    gherkin-tags              a tag line holding something that is not a tag, or
                              tags on nothing that can carry them
    gherkin-doc-string        a doc string never closed
    gherkin-table             a table row of another width than its table's first
    gherkin-outline-examples  a scenario outline with no example row"
  (:require [clojure.string :as str]))

(def step-keywords #{"Given" "When" "Then" "And" "But"})

(def block-keywords
  "The block keywords of the subset, longest first so Scenario Outline wins."
  ["Scenario Outline" "Background" "Scenario" "Examples" "Feature" "Rule"])

(def ^:private unknown-keyword-re
  "A line starting with a Gherkin keyword outside the subset: Gherkin's
  other English block keywords, or any Scenario, Feature, Rule, Background
  or Examples variant the subset does not name."
  #"^(Example|Scenarios|Scenario Template|Ability|Business Need|(?:Scenario|Feature|Rule|Background|Examples?)\s+[A-Za-z]+(?:\s+[A-Za-z]+)?)\s*:(?:\s.*)?$")

(defn- refusal [file line rule message]
  {:file file :line line :rule rule :message message :severity :error})

(defn- block-of
  "[keyword name] when `t` (a trimmed line) opens a block of the subset."
  [t]
  (some (fn [k]
          (let [p (str k ":")]
            (when (str/starts-with? t p)
              [k (str/trim (subs t (count p)))])))
        block-keywords))

(defn- step-of [t]
  (when-let [[_ k text] (re-matches #"^(Given|When|Then|And|But)(?:\s+(.*))?$" t)]
    [k (str/trim (or text ""))]))

(defn split-row
  "The cells of a table row `t` (trimmed, starting and ending with |); a
  backslash escapes |, \\ and n."
  [t]
  (let [inner (subs t 1 (max 1 (dec (count t))))
        n (count inner)]
    (loop [i 0 cur (StringBuilder.) cells []]
      (if (>= i n)
        (conj cells (str/trim (str cur)))
        (let [c (.charAt ^String inner i)]
          (cond
            (and (= c \\) (< (inc i) n))
            (let [d (.charAt ^String inner (inc i))]
              (.append cur (case d \n "\n" \| "|" \\ "\\" (str c d)))
              (recur (+ i 2) cur cells))
            (= c \|) (recur (inc i) (StringBuilder.) (conj cells (str/trim (str cur))))
            :else (do (.append cur c) (recur (inc i) cur cells))))))))

(defn- indent-of [l] (count (take-while #(= \space %) l)))

(defn- dedent [l n]
  (let [k (min n (indent-of l))] (subs l k)))

(defn parse-feature
  "Reads the feature file `file` (the path it is named by) holding `text`;
  see the namespace doc."
  [file text]
  (let [lines (vec (str/split-lines (str/replace (str text) "\r\n" "\n")))
        n (count lines)
        st (volatile! {:feature nil :feature-bg [] :rule nil :rule-bg []
                       :block nil      ; :feature :background :rule :scenario :examples
                       :scenario nil :scenarios [] :examples nil
                       :tags nil :tags-line nil
                       :refusals []
                       :description? true})
        refuse! (fn [line rule message] (vswap! st update :refusals conj (refusal file line rule message)))
        ;; the step list currently written to: a background's or a scenario's
        target (fn [s] (case (:block s)
                         :background (if (:rule s) :rule-bg :feature-bg)
                         :scenario :scenario
                         nil))
        close-examples (fn [s] (if-let [ex (:examples s)]
                                 (-> s (update-in [:scenario :examples] conj ex) (assoc :examples nil))
                                 s))
        close-scenario (fn [s]
                         (let [s (close-examples s)]
                           (if-let [sc (:scenario s)]
                             (-> s (update :scenarios conj sc) (assoc :scenario nil))
                             s)))
        take-tags (fn [s] [(vec (:tags s)) (assoc s :tags nil :tags-line nil)])
        last-step-path (fn [s]
                         (case (target s)
                           :scenario (when (seq (get-in s [:scenario :steps]))
                                       [:scenario :steps (dec (count (get-in s [:scenario :steps])))])
                           :feature-bg (when (seq (:feature-bg s)) [:feature-bg (dec (count (:feature-bg s)))])
                           :rule-bg (when (seq (:rule-bg s)) [:rule-bg (dec (count (:rule-bg s)))])
                           nil))]
    (loop [i 0]
      (when (< i n)
        (let [raw (nth lines i)
              line (inc i)
              t (str/trim raw)
              s @st]
          (cond
            (str/blank? t) (recur (inc i))

            (str/starts-with? t "#") (recur (inc i))

            ;; a doc string: everything up to its closing delimiter, verbatim
            (or (str/starts-with? t "\"\"\"") (str/starts-with? t "```"))
            (let [delim (subs t 0 3)
                  ind (indent-of raw)
                  close (loop [j (inc i)]
                          (cond (>= j n) nil
                                (= delim (str/trim (nth lines j))) j
                                :else (recur (inc j))))]
              (cond
                (nil? close)
                (do (refuse! line "gherkin-doc-string" (str "the doc string opened here (" delim ") is never closed"))
                    (recur n))

                (nil? (last-step-path s))
                (do (refuse! line "gherkin-misplaced" "a doc string must follow a step")
                    (recur (inc close)))

                :else
                (let [body (str/join "\n" (map #(dedent % ind) (subvec lines (inc i) close)))
                      p (last-step-path s)]
                  (if (or (get-in s (conj p :doc-string)) (get-in s (conj p :data-table)))
                    (refuse! line "gherkin-misplaced" "a step carries one doc string or one data table, not two")
                    (vswap! st assoc-in (conj p :doc-string) body))
                  (recur (inc close)))))

            (str/starts-with? t "@")
            (let [tokens (str/split (str/trim (first (str/split t #"\s#" 2))) #"\s+")
                  bad (remove #(re-matches #"@\S+" %) tokens)]
              (when (seq bad)
                (refuse! line "gherkin-tags" (str "a tag line holds " (str/join ", " (map pr-str bad))
                                                  ", which is not a tag")))
              (vswap! st (fn [s] (-> s
                                     (update :tags (fnil into []) (filter #(re-matches #"@\S+" %) tokens))
                                     (update :tags-line #(or % line)))))
              (recur (inc i)))

            (str/starts-with? t "|")
            (let [cells (split-row t)]
              (cond
                (not (str/ends-with? t "|"))
                (refuse! line "gherkin-table" "a table row ends with |")

                (and (= :examples (:block s)) (:examples s))
                (let [ex (:examples s)]
                  (if (nil? (:header ex))
                    (vswap! st assoc-in [:examples :header] cells)
                    (do (when (not= (count cells) (count (:header ex)))
                          (refuse! line "gherkin-table" (str "the row has " (count cells) " cells, its table's header "
                                                             (count (:header ex)))))
                        (vswap! st update-in [:examples :rows] conj {:line line :cells cells}))))

                (last-step-path s)
                (let [p (last-step-path s)
                      table (get-in s (conj p :data-table))]
                  (cond
                    (get-in s (conj p :doc-string))
                    (refuse! line "gherkin-misplaced" "a step carries one doc string or one data table, not two")
                    (and (seq table) (not= (count cells) (count (first table))))
                    (refuse! line "gherkin-table" (str "the row has " (count cells) " cells, its table's first row "
                                                       (count (first table))))
                    :else (vswap! st update-in (conj p :data-table) (fnil conj []) cells)))

                :else
                (refuse! line "gherkin-misplaced" "a table row must follow a step or an Examples line"))
              (recur (inc i)))

            :else
            (if-let [[k nm] (block-of t)]
              (let [[tags s] (take-tags s)
                    tags-ok? (contains? #{"Feature" "Rule" "Scenario" "Scenario Outline" "Examples"} k)]
                (when (and (seq tags) (not tags-ok?))
                  (refuse! (:tags-line s) "gherkin-tags" (str "tags on " k ", which carries none")))
                (vreset! st s)
                (case k
                  "Feature"
                  (if (:feature s)
                    (refuse! line "gherkin-one-feature"
                             (str "a second Feature in one file (the first is at line " (get-in s [:feature :line])
                                  "): a feature file holds exactly one feature"))
                    (vswap! st assoc :feature {:line line :name nm :tags tags :description nil}
                            :block :feature :description? true))

                  (if (nil? (:feature @st))
                    (refuse! line "gherkin-no-feature" (str k " comes before any Feature line"))
                    (case k
                      "Background"
                      (let [s (close-scenario @st)]
                        (cond
                          (and (:rule s) (seq (:rule-bg s)))
                          (refuse! line "gherkin-misplaced" "a rule has one Background")
                          (and (nil? (:rule s)) (seq (:feature-bg s)))
                          (refuse! line "gherkin-misplaced" "a feature has one Background")
                          (and (nil? (:rule s)) (seq (:scenarios s)))
                          (refuse! line "gherkin-misplaced" "the feature's Background comes before its scenarios")
                          :else nil)
                        (vreset! st (assoc s :block :background :description? true)))

                      "Rule"
                      (vswap! st (fn [s] (-> (close-scenario s)
                                             (assoc :rule {:name nm :line line :tags tags} :rule-bg []
                                                    :block :rule :description? true))))

                      ("Scenario" "Scenario Outline")
                      (vswap! st (fn [s]
                                   (let [s (close-scenario s)
                                         {:keys [feature rule]} s]
                                     (assoc s
                                            :scenario {:keyword k :name nm :line line
                                                       :tags tags
                                                       :all-tags (vec (distinct (concat (:tags feature) (:tags rule) tags)))
                                                       :rule rule
                                                       :background (vec (concat (:feature-bg s) (when rule (:rule-bg s))))
                                                       :steps []
                                                       :examples []}
                                            :block :scenario :description? true))))

                      "Examples"
                      (let [s @st]
                        (if (not= "Scenario Outline" (get-in s [:scenario :keyword]))
                          (refuse! line "gherkin-misplaced" "Examples belong to a Scenario Outline")
                          (vreset! st (-> (close-examples s)
                                          (assoc :examples {:line line :tags tags :name nm :header nil :rows []}
                                                 :block :examples :description? true))))))))
                (recur (inc i)))

              (if-let [[k text] (step-of t)]
                (let [s @st
                      tgt (when (#{:background :scenario} (:block s)) (target s))]
                  (when (seq (:tags s))
                    (refuse! (:tags-line s) "gherkin-tags" "tags on a step, which carries none")
                    (vswap! st assoc :tags nil :tags-line nil))
                  (if (nil? tgt)
                    (refuse! line "gherkin-misplaced"
                             (str "the step \"" t "\" is outside a scenario or background"))
                    (let [step {:keyword k :text text :line line}]
                      (case tgt
                        :scenario (vswap! st update-in [:scenario :steps] conj step)
                        (vswap! st update tgt conj step))
                      (vswap! st assoc :description? false)))
                  (recur (inc i)))

                (let [[_ kw] (re-matches unknown-keyword-re t)]
                  (cond
                    (or kw (str/starts-with? t "* ") (= t "*"))
                    (refuse! line "gherkin-unknown-keyword"
                             (str "the keyword " (pr-str (or kw "*")) " is not in the declared Gherkin subset"
                                  " (Feature, Background, Rule, Scenario, Scenario Outline, Examples,"
                                  " Given, When, Then, And, But)"))

                    (nil? (:feature s))
                    (refuse! line "gherkin-no-feature" "text before any Feature line")

                    (and (:description? s) (nil? (:tags s)))
                    (when (= :feature (:block s))
                      (vswap! st update-in [:feature :description] #(if % (str % "\n" t) t)))

                    :else
                    (refuse! line "gherkin-misplaced" (str "the line " (pr-str t) " is not a step, a table row or a comment")))
                  (recur (inc i)))))))))
    (let [s (close-scenario @st)
          refusals (cond-> (:refusals s)
                     (seq (:tags s))
                     (conj (refusal file (:tags-line s) "gherkin-tags" "tags on nothing: no block follows them")))
          outline-refusals (for [sc (:scenarios s)
                                 :when (and (= "Scenario Outline" (:keyword sc))
                                            (empty? (mapcat :rows (:examples sc))))]
                             (refusal file (:line sc) "gherkin-outline-examples"
                                      (str "the scenario outline \"" (:name sc) "\" has no example row")))]
      {:feature (:feature s)
       :scenarios (:scenarios s)
       :refusals (vec (sort-by :line (concat refusals outline-refusals)))})))

(defn outline-rows
  "The example rows of the outline `sc`: [{:line :values {header cell}}]."
  [sc]
  (vec (for [ex (:examples sc) row (:rows ex)]
         {:line (:line row) :values (zipmap (:header ex) (:cells row))})))

(defn substitute
  "`s` with every <name> of `values` replaced by its value."
  [s values]
  (when s
    (reduce-kv (fn [acc k v] (str/replace acc (str "<" k ">") (str v))) s values)))
