(ns rdm-spec.spec.check
  "The Spec check component of the spec context (dhf/spec/spec/check.feature):
  the rules a spec must keep. The spec is read by the Parser component; this
  component holds what it read to the rules and publishes SpecGated.

  The rules, each a stable finding name:

    check-id-count       a scenario carrying more than one id
    check-id-missing     a scenario carrying no id (run `rdm-spec stamp`)
    check-id-duplicate   an id carried by two scenarios
    check-id-unissued    an id above the ledger's highest issued id
    check-level          a scenario carrying no level, or both
    check-tag            a scenario tag that is not an id, a level, a need or @wip
    check-tag-placement  a feature, rule or examples tag that is not a need tag
    check-need-missing   a scenario tracing to no need (on it, its rule or its feature)
    check-need-unknown   a need tag naming no declared need
    check-need-source    a declared need citing no source
    check-needs-file     a needs file that cannot be read
    check-stack-word     a spec file naming a configured stack word

  plus the parser's refusals, relayed with their own rules and lines. Whether
  a cited source resolves is the knowledge context's to say.

  The stage `check` (of `rdm-spec check` and `rdm-spec publish`) reads the
  spec in the working tree, publishes SpecParsed (as the parser) so the
  stages after it read the same spec, then SpecGated with every finding."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.spec.stamper :as stamper]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- named
  "How a finding names a scenario: its id, else its title."
  [sc]
  (if (:id sc)
    (str (:id sc) " (\"" (:title sc) "\")")
    (str "the scenario \"" (:title sc) "\"")))

(defn- at [sc rule message]
  (command/finding (:file sc) (:line sc) rule message))

(defn- id-number
  "The number of the id `id` (\"spec-0042\" -> 42)."
  [id]
  (some-> (re-find #"-(\d+)$" (str id)) second parse-long))

(defn allowed-tags
  "The tags a scenario may carry, said in words."
  [prefix]
  (str "one id (@" prefix "-NNNN), one level (@e2e or @contract), need tags (@UN-nnn) and @wip"))

;; ---------------------------------------------------------------------------
;; Ids

(defn id-findings
  "One id per scenario, each id carried once, and every id issued by the
  ledger (`issued`: the highest id it records; nil when not checked)."
  [prefix ledger-rel issued scenarios]
  (concat
   (for [sc scenarios
         :let [ids (:ids sc)]
         :when (> (count ids) 1)]
     (at sc "check-id-count"
         (str "the scenario \"" (:title sc) "\" carries " (count ids) " ids, "
              (str/join " and " (map #(str "@" %) ids)) "; a scenario carries exactly one")))
   (for [sc scenarios
         :when (empty? (:ids sc))]
     (at sc "check-id-missing"
         (str "the scenario \"" (:title sc) "\" carries no @" prefix "- id; run \"rdm-spec stamp\" to give it one")))
   (let [carriers (reduce (fn [m sc] (reduce #(update %1 %2 (fnil conj []) sc) m (:ids sc)))
                          (sorted-map) scenarios)]
     (for [[id scs] carriers
           :when (> (count scs) 1)
           :let [first-sc (first scs)]
           sc (rest scs)]
       (at sc "check-id-duplicate"
           (str "@" id " is carried by two scenarios: \"" (:title sc) "\" here and \"" (:title first-sc)
                "\" at " (:file first-sc) ":" (:line first-sc) "; an id names one scenario"))))
   (when issued
     (for [sc scenarios
           id (:ids sc)
           :let [n (id-number id)]
           :when (and n (> n issued))]
       (at sc "check-id-unissued"
           (str id " is above the highest id the ledger " ledger-rel " records as issued, "
                (stamper/format-id prefix issued) ": the ledger never issued it; ids are handed out by"
                " \"rdm-spec stamp\", never by hand"))))))

;; ---------------------------------------------------------------------------
;; Levels and tags

(defn level-findings
  "Exactly one level per scenario."
  [scenarios]
  (for [sc scenarios
        :let [levels (:levels sc)]
        :when (not= 1 (count levels))]
    (at sc "check-level"
        (if (empty? levels)
          (str (named sc) " carries no level; a scenario carries exactly one: @e2e or @contract")
          (str (named sc) " carries " (count levels) " levels, " (str/join " and " levels)
               "; a scenario carries exactly one: @e2e or @contract")))))

(defn- tag-kind [id-re tag]
  (cond (re-matches id-re tag) :id
        (parser/level-tags tag) :level
        (re-matches parser/need-pattern tag) :need
        (= parser/wip-tag tag) :wip
        :else nil))

(defn tag-findings
  "Only known tags on a scenario."
  [prefix scenarios]
  (let [id-re (parser/id-pattern prefix)]
    (for [sc scenarios
          tag (distinct (:tags sc))
          :when (nil? (tag-kind id-re tag))]
      (at sc "check-tag"
          (str (named sc) " carries the tag " tag ", which is not an id, a level, a need or @wip;"
               " a scenario may carry " (allowed-tags prefix))))))

(defn placement-findings
  "Only need tags on a feature, a rule or examples."
  [prefix features scenarios]
  (let [id-re (parser/id-pattern prefix)
        bad (fn [tag] (not= :need (tag-kind id-re tag)))
        say (fn [where tag]
              (str "the tag " tag " is on " where "; a feature or a rule carries only need tags (@UN-nnn),"
                   " and an id, a level or @wip goes on a scenario"))]
    (concat
     (for [f features
           tag (distinct (:tags f))
           :when (bad tag)]
       (command/finding (:file f) (:line f) "check-tag-placement" (say (str "the feature \"" (:name f) "\"") tag)))
     (distinct
      (for [sc scenarios
            :when (:rule sc)
            tag (distinct (:rule-tags sc))
            :when (bad tag)]
        (command/finding (:file sc) (:rule-line sc) "check-tag-placement" (say (str "the rule \"" (:rule sc) "\"") tag))))
     (distinct
      (for [sc scenarios
            ex (:examples sc)
            tag (distinct (:tags ex))]
        (command/finding (:file sc) (:line ex) "check-tag-placement"
                         (str "the tag " tag " is on the examples of " (named sc)
                              "; examples carry no tags")))))))

;; ---------------------------------------------------------------------------
;; Needs

(defn need-findings
  "Every scenario traces to a declared need."
  [scenarios needs]
  (let [declared (set (map :id needs))]
    (concat
     (for [sc scenarios
           :when (empty? (:needs sc))]
       (at sc "check-need-missing"
           (str (named sc) " traces to no user need: tag it, its rule or its feature with one (@UN-nnn)")))
     (for [sc scenarios
           n (:needs sc)
           :when (not (contains? declared n))]
       (at sc "check-need-unknown"
           (str (named sc) " traces to " n ", which no user need declares"))))))

(defn source-findings
  "Every declared need cites a source."
  [needs-rel needs]
  (for [n needs
        :when (empty? (remove #(str/blank? (str %)) (:sources n)))]
    (command/finding needs-rel (:line n) "check-need-source"
                     (str "the user need " (:id n) " cites no source; every need rests on a raw source,"
                          " cited as <source>:<path>@<commit>"))))

;; ---------------------------------------------------------------------------
;; The stack

(defn- word-pattern [w]
  (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}_])")))

(defn stack-word-findings
  "A finding for each line of each file naming a configured stack word,
  matched as a whole word, whatever its case. `files`: [{:file :text}]."
  [files words]
  (let [res (for [w words :when (and (string? w) (not (str/blank? w)))] [w (word-pattern w)])]
    (for [{:keys [file text]} files
          [i l] (map-indexed vector (str/split-lines (or text "")))
          [w re] res
          :when (re-find re l)]
      (command/finding file (inc i) "check-stack-word"
                       (str "names the stack word \"" w "\"; the spec never names its stack,"
                            " which lives in dhf/harness/stack.md")))))

;; ---------------------------------------------------------------------------
;; The whole check

(defn check-spec
  "Every finding of the spec check on the spec `spec` (parser/read-spec).
  `opts`: :id-prefix, :ledger (its path relative to the root), :issued (the
  ledger's highest issued id, nil to skip that rule), :stack-words."
  [spec {:keys [id-prefix ledger issued stack-words]}]
  (let [prefix (or id-prefix "spec")
        scs (:scenarios spec)]
    (vec (concat (parser/refusal-findings spec)
                 (id-findings prefix ledger issued scs)
                 (level-findings scs)
                 (tag-findings prefix scs)
                 (placement-findings prefix (:features spec) scs)
                 (need-findings scs (:needs spec))
                 (when-let [e (:needs-error spec)]
                   [(command/finding (:needs-file spec) nil "check-needs-file" e)])
                 (source-findings (:needs-file spec) (:needs spec))
                 (stack-word-findings (parser/text-files nil spec) stack-words)))))

(defn- event-finding [f] (select-keys f [:file :line :rule :message]))

(defn check
  "The spec check's stage: reads the spec in the working tree, publishes
  SpecParsed and then SpecGated, and reports every finding."
  [req]
  (let [config (:config req)
        spec-dir (command/config-value req :spec-dir)
        prefix (or (:id-prefix config) "spec")
        ledger-rel (or (:ledger config) (str (str/replace spec-dir #"/+$" "") "/ids.edn"))
        _ (when-not (fs/directory? (command/path req spec-dir))
            (command/cannot-run! (str "no spec directory " spec-dir)))
        issued (stamper/read-ledger (command/path req ledger-rel) ledger-rel)
        spec (parser/read-spec (:root req) {:spec-dir spec-dir :id-prefix prefix
                                            :model (:model config) :needs (:needs config)})
        _ (parser/publish! req spec)
        findings (check-spec spec {:id-prefix prefix :ledger ledger-rel :issued issued
                                   :stack-words (:stack-words config)})
        passed? (empty? findings)]
    (command/publish! req :spec/check :SpecGated
                      {:revision (:revision spec) :passed? passed? :findings (mapv event-finding findings)})
    {:result {:spec {:passed? passed?
                     :scenarios (count (:scenarios spec))
                     :needs (count (:needs spec))
                     :issued issued}}
     :findings findings}))
