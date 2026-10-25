(ns rdm-spec.spec.check
  "The Spec check component of the spec context (dhf/spec/spec/check.feature):
  the rules a spec keeps, as `rdm-spec check`'s spec stage. It reads the spec
  with the Parser (rdm-spec.spec.parser) in the working tree, publishes
  SpecParsed for the stages after it, holds the spec to its rules and
  publishes SpecGated.

  The rules, each a stable name on the error stream (the parser's refusals
  keep their own):
    scenario-no-id       a scenario with no id: run rdm-spec stamp
    scenario-two-ids     a scenario with more than one id
    id-duplicate         an id carried by more than one scenario
    id-not-issued        an id above every id the ledger has issued
    ledger-unreadable    the ledger is missing or records no :issued number
    scenario-no-level    a scenario with no level (@e2e or @contract)
    scenario-two-levels  a scenario with more than one level
    tag-unknown          a tag that is not an id, a level, a need or @wip
    tag-misplaced        a known tag where it may not go: on a feature or a
                         rule only a need tag; on examples none
    scenario-no-need     a scenario that traces to no need
    need-undeclared      a need tag naming no declared need
    needs-unreadable     the needs file is missing or not a map of needs
    need-no-source       a declared need citing no source
    spec-stack-word      a file of the spec naming a configured stack word
  A need's citations are resolved by the knowledge context (Sources)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]))

;; ---------------------------------------------------------------------------
;; Naming

(defn- describe
  "How a finding names a scenario: its id, else its title."
  [sc]
  (if (:id sc)
    (str "the scenario " (:id sc) " (\"" (:title sc) "\")")
    (str "the scenario \"" (:title sc) "\"")))

(defn- f [sc rule message] (command/finding (:file sc) (:line sc) rule message))

(defn allowed-tags
  "What a scenario may carry, as the findings list it."
  [prefix]
  (str "a scenario may carry one id (@" prefix "-NNNN), one level (@e2e or @contract), "
       "need tags (@UN-nnn) and @wip; a feature or a rule only need tags"))

;; ---------------------------------------------------------------------------
;; Ids

(defn- id-number [prefix id]
  (some-> (re-matches (re-pattern (str (java.util.regex.Pattern/quote prefix) "-(\\d+)")) (str id))
          second parse-long))

(defn id-findings
  "Pure: one id per scenario, never one id for two, never one the ledger
  did not issue (`issued` the ledger's :issued, nil when unreadable)."
  [prefix scenarios issued]
  (let [by-id (group-by identity (mapcat :ids scenarios))
        where (fn [sc] (str (:file sc) ":" (:line sc)))]
    (vec
     (concat
      (for [sc scenarios :when (empty? (:ids sc))]
        (f sc "scenario-no-id"
           (str (describe sc) " carries no id (@" prefix "-NNNN): run \"rdm-spec stamp\" to give it one")))
      (for [sc scenarios :when (> (count (:ids sc)) 1)]
        (f sc "scenario-two-ids"
           (str (describe sc) " carries " (count (:ids sc)) " ids, " (str/join " and " (:ids sc))
                ": a scenario carries exactly one")))
      (for [[id _] by-id
            :let [holders (filter #(some #{id} (:ids %)) scenarios)]
            :when (> (count holders) 1)
            sc (rest holders)]
        (f sc "id-duplicate"
           (str "the id " id " is carried by " (count holders) " scenarios, in "
                (str/join " and " (map where holders)) ": an id names one scenario")))
      (when issued
        (for [sc scenarios
              id (:ids sc)
              :let [n (id-number prefix id)]
              :when (and n (> n issued))]
          (f sc "id-not-issued"
             (str "the id " id " is above every id the ledger has issued (" (format "%s-%04d" prefix issued)
                  "): the ledger never issued it; give the scenario no id and run \"rdm-spec stamp\""))))))))

;; ---------------------------------------------------------------------------
;; Levels and tags

(defn level-findings
  "Pure: exactly one level per scenario."
  [scenarios]
  (vec (for [sc scenarios
             :let [ls (:levels sc)]
             :when (not= 1 (count ls))]
         (if (empty? ls)
           (f sc "scenario-no-level"
              (str (describe sc) " carries no level: tag it with one of the two levels, @e2e or @contract"))
           (f sc "scenario-two-levels"
              (str (describe sc) " carries the levels " (str/join " and " ls)
                   ": a scenario carries exactly one of @e2e and @contract"))))))

(defn- kind-of [prefix tag]
  (cond (re-matches (parser/id-pattern prefix) tag) :id
        (parser/level-tags tag) :level
        (re-matches parser/need-pattern tag) :need
        (= parser/wip-tag tag) :wip
        :else nil))

(defn tag-findings
  "Pure: only known tags, each where it may go."
  [prefix features scenarios]
  (let [allowed (allowed-tags prefix)]
    (vec
     (concat
      (for [sc scenarios
            t (:tags sc)
            :when (nil? (kind-of prefix t))]
        (f sc "tag-unknown" (str (describe sc) " carries the tag " t ", which is not allowed: " allowed)))
      (for [ft features
            t (:tags ft)
            :let [k (kind-of prefix t)]
            :when (not= :need k)]
        (command/finding (:file ft) (:line ft) (if k "tag-misplaced" "tag-unknown")
                         (str "the feature \"" (:name ft) "\" carries the tag " t ": " allowed)))
      (distinct
       (for [sc scenarios
             :when (:rule-line sc)
             t (:rule-tags sc)
             :let [k (kind-of prefix t)]
             :when (not= :need k)]
         (command/finding (:file sc) (:rule-line sc) (if k "tag-misplaced" "tag-unknown")
                          (str "the rule \"" (:rule sc) "\" carries the tag " t ": " allowed))))
      (for [sc scenarios
            ex (:examples sc)
            t (:tags ex)]
        (command/finding (:file sc) (:line ex) "tag-misplaced"
                         (str "the examples of " (describe sc) " carry the tag " t ": examples carry no tag")))))))

;; ---------------------------------------------------------------------------
;; Needs

(defn need-findings
  "Pure: every scenario traces to a declared need, every need cites a
  source. `needs` [{:id :sources :line}], `needs-file` its path."
  [scenarios needs needs-file needs-error]
  (let [declared (set (map :id needs))]
    (vec
     (concat
      (when needs-error
        [(command/finding needs-file nil "needs-unreadable" needs-error)])
      (for [sc scenarios :when (empty? (:needs sc))]
        (f sc "scenario-no-need"
           (str (describe sc) " traces to no need: tag it, its rule or its feature with a declared @UN-nnn")))
      (when-not needs-error
        (for [sc scenarios
              n (:needs sc)
              :when (not (declared n))]
          (f sc "need-undeclared"
             (str (describe sc) " traces to " n ", which " needs-file " does not declare"))))
      (for [n needs :when (empty? (:sources n))]
        (command/finding needs-file (:line n) "need-no-source"
                         (str "the need " (:id n) " cites no source: a need rests on a raw source, "
                              "<source>:<path>@<commit>")))))))

;; ---------------------------------------------------------------------------
;; Stack words

(defn stack-word-findings
  "Pure: a finding for each line of each file ({:file :text}) naming one of
  `words`, as a whole word, whatever its case."
  [files words]
  (let [res (for [w words :when (and (string? w) (not (str/blank? w)))]
              [w (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w)
                                  "(?![\\p{L}\\p{N}_])"))])]
    (vec (for [{:keys [file text]} files
               [i l] (map-indexed vector (str/split-lines (or text "")))
               [w re] res
               :when (re-find re l)]
           (command/finding file (inc i) "spec-stack-word"
                            (str "names the stack word \"" w "\": the spec never names its stack, "
                                 "which lives in dhf/harness/stack.md"))))))

;; ---------------------------------------------------------------------------
;; The ledger

(defn read-issued
  "{:issued n} or {:finding f}: the ledger's :issued."
  [req]
  (let [rel (or (get-in req [:config :ledger])
                (str (str/replace (str (get-in req [:config :spec-dir] "dhf/spec")) #"/+$" "") "/ids.edn"))
        file (command/path req rel)
        bad #(hash-map :finding (command/finding rel nil "ledger-unreadable" (str "the ledger " rel " " %)))]
    (if-not (fs/regular-file? file)
      (bad "does not exist: ids are handed out by rdm-spec stamp, which records them there")
      (let [v (try (edn/read-string (slurp (str file))) (catch Exception _ ::bad))]
        (if (and (map? v) (nat-int? (:issued v)))
          {:issued (:issued v)}
          (bad "records no :issued number"))))))

;; ---------------------------------------------------------------------------
;; The stage

(defn check-spec
  "Pure: every finding of the spec check on the parsed `spec`."
  [spec {:keys [prefix issued stack-words]}]
  (let [scs (:scenarios spec)]
    (vec (concat (parser/refusal-findings spec)
                 (id-findings prefix scs issued)
                 (level-findings scs)
                 (tag-findings prefix (:features spec) scs)
                 (need-findings scs (:needs spec) (:needs-file spec) (:needs-error spec))
                 (stack-word-findings (for [[file text] (:texts spec)] {:file file :text text}) stack-words)))))

(defn check
  "The spec stage of `rdm-spec check`: reads the spec, publishes SpecParsed,
  checks it and publishes SpecGated. Result {:spec {:passed? :scenarios n}}."
  [req]
  (let [config (:config req)
        spec-dir (command/config-value req :spec-dir)
        _ (when-not (fs/directory? (command/path req spec-dir))
            (command/cannot-run! (str "no spec directory " spec-dir)))
        prefix (or (:id-prefix config) "spec")
        spec (parser/read-spec (:root req) {:spec-dir spec-dir :id-prefix prefix
                                            :model (:model config) :needs (:needs config)})
        _ (parser/publish! req spec)
        {:keys [issued finding]} (read-issued req)
        findings (vec (concat (when finding [finding])
                              (check-spec spec {:prefix prefix :issued issued :stack-words (:stack-words config)})))
        passed? (empty? (remove command/warning? findings))]
    (command/publish! req :spec/check :SpecGated
                      {:revision (:revision spec)
                       :passed? passed?
                       :findings (mapv #(select-keys % [:file :line :rule :message]) findings)})
    {:result {:spec {:passed? passed? :scenarios (count (:scenarios spec))}}
     :findings findings}))
