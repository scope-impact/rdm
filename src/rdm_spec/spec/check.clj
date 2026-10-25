(ns rdm-spec.spec.check
  "The Spec check component of the spec context (dhf/spec/spec/check.feature):
  the rules a spec must keep, run as the spec stage of `rdm-spec check` (and
  of `rdm-spec publish`). The spec is read by the parser in the working tree;
  the parser publishes SpecParsed and this component publishes SpecGated.

  The rules, each a stable name on the error stream:
    gherkin-*            the parser's refusals: a construct outside the subset
    check-no-id          a scenario with no id (run rdm-spec stamp)
    check-ids            a scenario with more than one id
    check-duplicate-id   an id carried by more than one scenario
    check-level          a scenario with no level, or with both
    check-unknown-tag    a tag that is not an id, a level, a need or @wip
    check-tag-place      a known tag where it may not go: an id, a level or
                         @wip anywhere but on a scenario; any tag on Examples
    check-no-need        a scenario tracing to no user need
    check-unknown-need   a need tag naming no declared need
    check-needs          the needs file is missing or unreadable
    check-need-unsourced a user need citing no source
    check-stack-word     a file of the spec naming a configured stack word

  Whether a need's citations resolve is the knowledge context's (KnowledgeIndexed,
  which this component subscribes to): its findings are reported by its own
  stage, so they are never reported twice; a citation that could not be
  checked is a warning, never a failure."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]))

(defn subscriptions
  "Keeps the run's KnowledgeIndexed payload in the run's state."
  [req]
  [[:KnowledgeIndexed (fn [payload] (swap! (:state req) assoc ::knowledge payload) nil)]])

(def allowed-tags
  "What a scenario may carry, as the findings list it."
  "an id (@spec-NNNN), a level (@e2e or @contract), a need (@UN-nnn) and @wip")

(defn- allowed-text [prefix]
  (str/replace allowed-tags "@spec-" (str "@" prefix "-")))

(defn- who
  "How a scenario is named in a finding: its id(s), else its title."
  [sc]
  (if (seq (:ids sc))
    (str/join ", " (:ids sc))
    (str "the scenario \"" (:title sc) "\"")))

(defn- id-tag? [prefix t] (re-matches (parser/id-pattern prefix) t))
(defn- need-tag? [t] (re-matches parser/need-pattern t))
(defn- level-tag? [t] (contains? parser/level-tags t))
(defn- wip-tag? [t] (= parser/wip-tag t))
(defn- known-tag? [prefix t] (or (id-tag? prefix t) (need-tag? t) (level-tag? t) (wip-tag? t)))

;; ---------------------------------------------------------------------------
;; Scenario rules

(defn- id-findings [sc]
  (let [{:keys [file line ids title]} sc]
    (cond
      (empty? ids)
      [(command/finding file line "check-no-id"
                        (str "the scenario \"" title "\" carries no id; run \"rdm-spec stamp\" to give it one"))]
      (> (count ids) 1)
      [(command/finding file line "check-ids"
                        (str "the scenario \"" title "\" carries " (count ids) " ids, "
                             (str/join " and " (map #(str "@" %) ids))
                             "; a scenario carries exactly one"))])))

(defn- level-findings [sc]
  (let [{:keys [file line levels]} sc]
    (cond
      (empty? levels)
      [(command/finding file line "check-level"
                        (str (who sc) " carries no level; a scenario carries exactly one of @e2e and @contract"))]
      (> (count levels) 1)
      [(command/finding file line "check-level"
                        (str (who sc) " carries " (count levels) " levels, " (str/join " and " levels)
                             "; a scenario carries exactly one of @e2e and @contract"))])))

(defn- unknown-tag-findings [prefix sc]
  (for [t (:tags sc) :when (not (known-tag? prefix t))]
    (command/finding (:file sc) (:line sc) "check-unknown-tag"
                     (str (who sc) " is tagged " t ", which is no tag a scenario may carry; it may carry "
                          (allowed-text prefix)))))

(defn- need-findings [declared sc]
  (concat
   (when (empty? (:needs sc))
     [(command/finding (:file sc) (:line sc) "check-no-need"
                       (str (who sc) " traces to no user need: tag it, its rule or its feature with a declared @UN-nnn"))])
   (for [n (distinct (keep #(when (need-tag? %) (subs % 1)) (:tags sc)))
         :when (not (contains? declared n))]
     (command/finding (:file sc) (:line sc) "check-unknown-need"
                      (str (who sc) " is tagged @" n ", a need the needs file does not declare")))))

(defn- example-tag-findings [sc]
  (for [ex (:examples sc) t (:tags ex)]
    (command/finding (:file sc) (:line ex) "check-tag-place"
                     (str (who sc) ": the Examples are tagged " t "; tags go on a scenario, a rule or a feature"))))

;; ---------------------------------------------------------------------------
;; Feature and rule tags: only needs go there

(defn- block-tag-findings
  "Findings for the tags of a feature or a rule (`what`), at `file`:`line`,
  carried by `scs` (the scenarios under it)."
  [prefix declared what file line tags scs]
  (let [under (str/join ", " (or (not-empty (keep :id scs)) ["no stamped scenario"]))]
    (concat
     (for [t tags :when (not (known-tag? prefix t))]
       (command/finding file line "check-unknown-tag"
                        (str "the " what " is tagged " t ", which is no tag; a " what " may carry only needs (@UN-nnn)"
                             "; a scenario may carry " (allowed-text prefix))))
     (for [t tags :when (and (known-tag? prefix t) (not (need-tag? t)))]
       (command/finding file line "check-tag-place"
                        (str "the " what " is tagged " t "; an id, a level and @wip go on a scenario only")))
     (for [t tags :when (need-tag? t)
           :let [n (subs t 1)]
           :when (not (contains? declared n))]
       (command/finding file line "check-unknown-need"
                        (str "the " what " is tagged " t ", a need the needs file does not declare; it covers " under))))))

(defn- block-findings [prefix declared spec]
  (let [by-file (group-by :file (:scenarios spec))]
    (concat
     (for [ft (:features spec)
           f (block-tag-findings prefix declared "feature" (:file ft) (:line ft) (:tags ft)
                                 (get by-file (:file ft)))]
       f)
     (for [[[file line] scs] (group-by (juxt :file :rule-line) (filter :rule-line (:scenarios spec)))
           f (block-tag-findings prefix declared "rule" file line (:rule-tags (first scs)) scs)]
       f))))

;; ---------------------------------------------------------------------------
;; Ids across the spec

(defn- duplicate-findings [scs]
  (for [[id holders] (group-by identity (mapcat :ids scs))
        :when (> (count holders) 1)
        :let [where (for [sc scs :when (some #{id} (:ids sc))] sc)
              places (str/join " and " (map #(str (:file %) ":" (:line %)) where))
              first-sc (first where)]]
    (command/finding (:file first-sc) (:line first-sc) "check-duplicate-id"
                     (str "@" id " is carried by " (count where) " scenarios, " places
                          "; an id is carried by one scenario only"))))

;; ---------------------------------------------------------------------------
;; Needs

(defn- needs-findings [spec]
  (concat
   (when-let [e (:needs-error spec)]
     [(command/finding (:needs-file spec) nil "check-needs" e)])
   (for [n (:needs spec) :when (empty? (:sources n))]
     (command/finding (:needs-file spec) (:line n) "check-need-unsourced"
                      (str (:id n) " cites no source; a user need rests on at least one, as <source>:<path>@<commit>")))))

;; ---------------------------------------------------------------------------
;; Stack words

(defn stack-word-findings
  "A finding for each line of `files` ([{:file :text}]) naming one of `words`,
  matched as a whole word, whatever its case."
  [files words]
  (let [res (for [w words :when (not (str/blank? w))]
              [w (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}_])"))])]
    (for [{:keys [file text]} files
          [i l] (map-indexed vector (str/split-lines text))
          [w re] res
          :when (re-find re l)]
      (command/finding file (inc i) "check-stack-word"
                       (str "names the stack word \"" w "\"; the spec never names its stack, which lives in dhf/harness/stack.md")))))

;; ---------------------------------------------------------------------------
;; The check

(defn check-spec
  "Every finding of the spec check on `spec` (as parser/read-spec returns it),
  given the spec's files ([{:file :text}]) and the configuration."
  [spec files config]
  (let [prefix (or (:id-prefix config) "spec")
        declared (set (map :id (:needs spec)))
        scs (:scenarios spec)]
    (vec (concat
          (parser/refusal-findings spec)
          (needs-findings spec)
          (block-findings prefix declared spec)
          (mapcat (fn [sc]
                    (concat (id-findings sc)
                            (level-findings sc)
                            (unknown-tag-findings prefix sc)
                            (need-findings declared sc)
                            (example-tag-findings sc)))
                  scs)
          (duplicate-findings scs)
          (stack-word-findings files (:stack-words config))))))

(defn check
  "The spec stage of `rdm-spec check`: reads the spec with the parser
  (publishing SpecParsed), checks it and publishes SpecGated."
  [req]
  (let [config (:config req)
        spec-dir (command/config-value req :spec-dir)
        _ (when-not (.isDirectory (command/path req spec-dir))
            (command/cannot-run! (str "no spec directory " spec-dir)))
        spec (parser/read-spec (:root req) {:spec-dir spec-dir
                                            :id-prefix (:id-prefix config)
                                            :model (:model config)
                                            :needs (:needs config)})
        _ (swap! (:state req) assoc ::parser/spec spec)
        _ (parser/publish! req spec)
        findings (check-spec spec (parser/text-files (:root req) spec) config)
        errors (remove command/warning? findings)]
    (command/publish! req :spec/check :SpecGated
                      {:revision (:revision spec) :passed? (empty? errors) :findings (vec findings)})
    {:result {:spec {:features (count (:features spec))
                     :scenarios (count (:scenarios spec))
                     :planned (count (filter :wip? (:scenarios spec)))
                     :passed? (empty? errors)}}
     :findings findings}))
