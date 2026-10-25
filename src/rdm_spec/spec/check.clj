(ns rdm-spec.spec.check
  "Spec check (dhf/spec/spec/check.feature): the rules a spec keeps. A stage
  of `rdm-spec check` (and, quietly, of publish): it reads the spec with the
  Parser, publishes SpecParsed (from the Parser) and SpecGated, and reports
  every finding, FILE:LINE: RULE: what.

  The rules, each a stable name on the error stream:
    gherkin-*            the Parser's refusals, relayed (rdm-spec.kernel.gherkin)
    scenario-no-id       a scenario with no id tag: run rdm-spec stamp
    scenario-ids         a scenario with more than one id
    id-duplicate         an id carried by more than one scenario
    id-not-issued        an id above the highest the ledger has issued
    ledger-unreadable    the ledger missing, not EDN, or holding no :issued
    scenario-no-level    a scenario with no level (@e2e or @contract)
    scenario-levels      a scenario with more than one level
    tag-unknown          a tag that is not an id, a level, a need or @wip
    tag-misplaced        an id, a level or @wip on a feature or a rule, or any
                         tag on Examples
    scenario-no-need     a scenario with no need tag on it, its rule or its feature
    need-undeclared      a need tag naming no declared need
    needs-unreadable     the needs file missing or not an EDN map
    need-no-source       a declared need citing no source
    spec-stack-word      a file of the spec naming a configured stack word

  Planned work, a scenario tagged @wip, passes the check."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]))

;; ---------------------------------------------------------------------------
;; Naming a scenario

(defn- named [sc]
  (if (:id sc)
    (str (:id sc) " (\"" (:title sc) "\")")
    (str "the scenario \"" (:title sc) "\"")))

(defn- at [sc rule msg] (command/finding (:file sc) (:line sc) rule msg))

(defn allowed-text
  "The tags a scenario may carry, as a finding lists them."
  [prefix]
  (str "a scenario may carry one id (@" prefix "-NNNN), one level (@e2e or @contract), need tags (@UN-nnn) and @wip"))

;; ---------------------------------------------------------------------------
;; The ledger

(defn read-ledger
  "The ledger `rel` holding `text` (nil: missing): {:issued n} or {:finding f}."
  [rel text]
  (let [v (when text (try {:ok (edn/read-string text)} (catch Exception e {:error (ex-message e)})))]
    (cond
      (nil? text) {:finding (command/finding rel "ledger-unreadable" (str "there is no ledger " rel))}
      (:error v) {:finding (command/finding rel "ledger-unreadable" (str "the ledger is not readable EDN: " (:error v)))}
      (not (and (map? (:ok v)) (nat-int? (:issued (:ok v)))))
      {:finding (command/finding rel "ledger-unreadable" "the ledger is not {:issued n}, the highest id issued")}
      :else {:issued (:issued (:ok v))})))

(defn id-number [id] (some-> (re-find #"\d+$" (str id)) parse-long))

;; ---------------------------------------------------------------------------
;; Rules (pure)

(defn id-findings
  "One id per scenario, each id on one scenario, and only ids the ledger issued."
  [scenarios issued]
  (let [by-id (group-by identity (mapcat :ids scenarios))
        carriers (fn [id] (filter #(some #{id} (:ids %)) scenarios))]
    (vec (concat
          (for [sc scenarios :when (empty? (:ids sc))]
            (at sc "scenario-no-id" (str (named sc) " carries no id: run \"rdm-spec stamp\" to give it one")))
          (for [sc scenarios :when (< 1 (count (:ids sc)))]
            (at sc "scenario-ids" (str "the scenario \"" (:title sc) "\" carries " (count (:ids sc)) " ids, "
                                       (str/join " and " (:ids sc)) ": a scenario carries exactly one")))
          (for [[id occ] (sort-by key by-id) :when (< 1 (count occ))
                :let [cs (carriers id)]
                sc cs]
            (at sc "id-duplicate" (str id " is carried by " (count cs) " scenarios, at "
                                       (str/join ", " (map #(str (:file %) ":" (:line %)) cs))
                                       ": an id names one scenario")))
          (when issued
            (for [sc scenarios id (:ids sc) :when (> (or (id-number id) 0) issued)]
              (at sc "id-not-issued" (str id " is above " issued ", the highest id the ledger has issued:"
                                          " the ledger never issued it; ids come from \"rdm-spec stamp\""))))))))

(defn level-findings
  "Exactly one level per scenario."
  [scenarios]
  (vec (concat
        (for [sc scenarios :when (empty? (:levels sc))]
          (at sc "scenario-no-level" (str (named sc) " carries no level: a scenario carries exactly one, @e2e or @contract")))
        (for [sc scenarios :when (< 1 (count (:levels sc)))]
          (at sc "scenario-levels" (str (named sc) " carries " (count (:levels sc)) " levels, "
                                        (str/join " and " (map #(str "@" (name %)) (:levels sc)))
                                        ": a scenario carries exactly one, @e2e or @contract"))))))

(defn- known-scenario-tag? [prefix t]
  (or (re-matches (parser/id-re prefix) t) (some #{t} parser/levels) (re-matches parser/need-re t) (= "@wip" t)))

(defn tag-findings
  "Only known tags, each where it may go."
  [scenarios features prefix]
  (let [first-of (fn [k] (vals (into {} (for [sc scenarios :when (k sc)] [[(:file sc) (k sc)] sc]))))
        misplaced (fn [file line where tags]
                    (for [t tags :when (not (re-matches parser/need-re t))]
                      (command/finding file line (if (known-scenario-tag? prefix t) "tag-misplaced" "tag-unknown")
                                       (str where " carries " t ": a feature or a rule carries only need tags (@UN-nnn); "
                                            (allowed-text prefix)))))]
    (vec (concat
          (for [sc scenarios t (:tags sc) :when (not (known-scenario-tag? prefix t))]
            (at sc "tag-unknown" (str (named sc) " carries " t ", which is not an id, a level, a need or @wip: "
                                      (allowed-text prefix))))
          (for [{:keys [file feature]} features :when feature
                f (misplaced file (:line feature) (str "the feature \"" (:name feature) "\"") (:tags feature))]
            f)
          (for [sc (first-of :rule-line)
                f (misplaced (:file sc) (:rule-line sc) (str "the rule \"" (:rule sc) "\"") (:rule-tags sc))]
            f)
          (for [{:keys [file examples-tags]} features {:keys [line tags]} examples-tags]
            (command/finding file line "tag-misplaced"
                             (str "Examples carry " (str/join " " tags) ": tags go on the scenario outline; " (allowed-text prefix))))))))

(defn need-findings
  "Every scenario traces to a declared need; every need rests on a source."
  [scenarios needs needs-lines needs-rel readable?]
  (let [declared (set (map :id needs))]
    (vec (concat
          (for [sc scenarios :when (empty? (:needs sc))]
            (at sc "scenario-no-need" (str (named sc) " traces to no user need: tag it, its rule or its feature @UN-nnn")))
          (when readable?
            (for [sc scenarios n (:needs sc) :when (not (declared n))]
              (at sc "need-undeclared" (str (named sc) " is tagged @" n ", and " n " is not declared in " needs-rel))))
          (for [n needs :when (empty? (:sources n))]
            (command/finding needs-rel (get needs-lines (:id n)) "need-no-source"
                             (str "the need " (:id n) " cites no source: every need rests on a source,"
                                  " cited <source>:<path>@<commit>")))))))

(defn stack-word-findings
  "A finding for each line of `text` (the file `file`) naming one of
  `words`, as a whole word, whatever its case."
  [file text words]
  (let [res (for [w words :when (and (string? w) (not (str/blank? w)))]
              [w (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}_])"))])]
    (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
               [w re] res
               :when (re-find re l)]
           (command/finding file (inc i) "spec-stack-word"
                            (str "names the stack word \"" w "\"; the spec never names an implementation,"
                                 " which lives in dhf/harness/stack.md"))))))

(defn findings
  "Pure: every finding of the spec check on a read spec (parser/read-spec),
  the ledger read (read-ledger) and the configuration's `stack-words`."
  [spec ledger prefix stack-words needs-rel]
  (let [scs (:scenarios spec)]
    (vec (concat
          (for [{:keys [file line rule message]} (:refusals spec)] (command/finding file line rule message))
          (when-let [f (:finding ledger)] [f])
          (id-findings scs (:issued ledger))
          (level-findings scs)
          (tag-findings scs (:features spec) prefix)
          (:needs-findings spec)
          (need-findings scs (:needs spec) (:needs-lines spec) needs-rel (empty? (:needs-findings spec)))
          (for [{:keys [file text]} (:files spec)
                f (stack-word-findings file text stack-words)]
            f)))))

;; ---------------------------------------------------------------------------
;; The stage

(defn check
  "The spec check stage of `rdm-spec check`."
  [req]
  (let [spec (parser/read-spec (:root req) (:config req))
        _ (parser/publish! req spec)
        ledger-rel (command/config-value req :ledger)
        ledger (read-ledger ledger-rel (some (fn [{:keys [file text]}] (when (= file ledger-rel) text)) (:files spec)))
        ledger (if (or (:issued ledger) (some #(= ledger-rel (:file %)) (:files spec)))
                 ledger
                 (let [f (command/path req ledger-rel)]
                   (read-ledger ledger-rel (when (.isFile (java.io.File. (str f))) (slurp (str f))))))
        fs* (findings spec ledger (command/config-value req :id-prefix) (:stack-words (:config req))
                      (command/config-value req :needs))]
    (command/publish! req :spec/check :SpecGated
                      {:revision (:revision spec) :passed? (empty? fs*)
                       :findings (mapv #(select-keys % [:file :line :rule :message]) fs*)})
    {:result {:spec {:revision (:revision spec) :scenarios (count (:scenarios spec)) :passed? (empty? fs*)}}
     :findings fs*}))
