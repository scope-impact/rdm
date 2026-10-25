(ns rdm-spec.spec.check
  "The Spec check component of the spec context (dhf/spec/spec/check.feature):
  the rules a spec must keep. A stage of `rdm-spec check` and `rdm-spec
  publish`: reads the spec in the working tree with the Parser, publishes
  SpecParsed (as the parser) for the stages after it, holds the spec to its
  rules, and publishes SpecGated.

  The rules, each a stable name on the error stream (FILE:LINE: RULE: what):
    gherkin-*           the parser's refusals, relayed unchanged
    scenario-no-id      a scenario with no id; says to run \"rdm-spec stamp\"
    scenario-ids        a scenario with more than one id; names it and each id
    id-duplicate        an id carried by two scenarios; names every place
    id-not-issued       an id above the highest the ledger has issued
    ledger-unreadable   no ledger, or one that records no :issued number
    scenario-no-level   a scenario with no level; names @e2e and @contract
    scenario-levels     a scenario with two levels; names both
    tag-unknown         a scenario tag that is not an id, a level, a need or
                        @wip; lists the tags a scenario may carry
    tag-misplaced       a tag on a feature, rule or Examples that is not a
                        need tag (an id, a level and @wip go on a scenario)
    scenario-no-need    a scenario with no need on it, its rule or its feature
    need-undeclared     a need tag naming no declared need
    needs-unreadable    the needs file is missing or not a map of needs
    need-no-source      a declared need citing no source
    spec-stack-word     a file of the spec naming a configured :stack-words
                        word, as a whole word, whatever its case

  Whether a need's citations resolve is the knowledge context's to say
  (dhf/spec/knowledge/sources.feature): this check keeps only \"a need cites
  no source\".

  The stage's result is {:spec-check {:passed? :scenarios n :findings n}}."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]))

;; ---------------------------------------------------------------------------
;; The ledger

(defn read-ledger
  "{:issued n} from the ledger `rel` under `root`, or {:finding f} when it is
  missing or records no :issued number: the ledger is part of the spec, so
  that is a finding, never a refusal."
  [root rel]
  (let [f (command/path root rel)
        bad (fn [why] {:finding (command/finding rel nil "ledger-unreadable" why)})]
    (if-not (fs/regular-file? f)
      (bad (str "the ledger " rel " does not exist"))
      (let [v (try (edn/read-string (slurp (str f))) (catch Exception e {::error (ex-message e)}))]
        (cond
          (and (map? v) (::error v)) (bad (str "the ledger " rel " is not readable EDN: " (::error v)))
          (and (map? v) (nat-int? (:issued v))) {:issued (:issued v)}
          :else (bad (str "the ledger " rel " records no :issued number")))))))

;; ---------------------------------------------------------------------------
;; Tags

(defn- id-tag? [prefix t] (boolean (re-matches (parser/id-pattern prefix) t)))
(defn- need-tag? [t] (boolean (re-matches parser/need-pattern t)))

(defn- allowed-tags [prefix]
  (str "@" prefix "-NNNN (one id), @e2e or @contract (one level), @UN-nnn (a need) and @wip (planned)"))

(defn- id-number [id] (some-> (re-find #"\d+$" (str id)) parse-long))

(defn- who
  "How a finding names the scenario `sc`: its id, else its title."
  [sc]
  (if (:id sc) (str "the scenario " (:id sc)) (str "the scenario " (pr-str (:title sc)))))

(defn- scenario-findings
  [{:keys [id-prefix issued declared]} sc]
  (let [{:keys [file line ids levels tags]} sc
        at (fn [rule msg] (command/finding file line rule msg))]
    (concat
     ;; ids
     (case (count ids)
       0 [(at "scenario-no-id" (str "the scenario " (pr-str (:title sc)) " carries no id; run \"rdm-spec stamp\""))]
       1 []
       [(at "scenario-ids" (str "the scenario " (pr-str (:title sc)) " carries " (count ids) " ids, "
                                (str/join " and " ids) "; a scenario carries exactly one"))])
     (for [id ids
           :let [n (id-number id)]
           :when (and issued n (> n issued))]
       (at "id-not-issued" (str id " is above " issued ", the highest id the ledger has issued:"
                                " the ledger never issued it; run \"rdm-spec stamp\"")))
     ;; levels
     (case (count levels)
       0 [(at "scenario-no-level" (str (who sc) " carries no level; it carries exactly one: @e2e or @contract"))]
       1 []
       [(at "scenario-levels" (str (who sc) " carries " (count levels) " levels, " (str/join " and " levels)
                                   "; it carries exactly one: @e2e or @contract"))])
     ;; tags
     (for [t tags
           :when (not (or (id-tag? id-prefix t) (parser/level-tags t) (need-tag? t) (= parser/wip-tag t)))]
       (at "tag-unknown" (str (who sc) " carries " t ", which is not a tag a scenario may carry: "
                              (allowed-tags id-prefix))))
     ;; needs
     (if (empty? (:needs sc))
       [(at "scenario-no-need" (str (who sc) " traces to no need: tag it, its rule or its feature @UN-nnn"))]
       (when declared
         (for [n (:needs sc) :when (not (contains? declared n))]
           (at "need-undeclared" (str (who sc) " is tagged @" n ", a need the needs file does not declare"))))))))

(defn- misplaced-findings
  "Tags on a feature, a rule or an Examples block that are not need tags."
  [spec]
  (let [bad (fn [file line tags where]
              (for [t tags :when (not (need-tag? t))]
                (command/finding file line "tag-misplaced"
                                 (str t " is on " where "; only a need tag (@UN-nnn) goes there,"
                                      " an id, a level and @wip go on a scenario"))))]
    (concat
     (mapcat #(bad (:file %) (:line %) (:tags %) (str "the feature " (pr-str (:name %)))) (:features spec))
     (->> (:scenarios spec)
          (filter :rule-line)
          (map (juxt :file :rule-line :rule-tags :rule))
          distinct
          (mapcat (fn [[file line tags rule]] (bad file line tags (str "the rule " (pr-str rule))))))
     (for [sc (:scenarios spec) ex (:examples sc) f (bad (:file sc) (:line ex) (:tags ex) "an Examples block")]
       f))))

(defn- duplicate-findings
  "An id carried by more than one scenario: one finding at each place after
  the first, naming every place."
  [scenarios]
  (let [places (reduce (fn [m sc] (reduce #(update %1 %2 (fnil conj []) sc) m (:ids sc))) {} scenarios)]
    (for [[id scs] (sort-by key places)
          :when (> (count scs) 1)
          :let [where (str/join ", " (map #(str (:file %) ":" (:line %)) scs))]
          sc (rest scs)]
      (command/finding (:file sc) (:line sc) "id-duplicate"
                       (str id " is carried by " (count scs) " scenarios, at " where "; an id is one scenario's")))))

(defn- need-findings
  [{:keys [needs needs-file needs-error]}]
  (if needs-error
    [(command/finding needs-file nil "needs-unreadable" needs-error)]
    (for [n needs :when (not-any? #(not (str/blank? (str %))) (:sources n))]
      (command/finding needs-file (:line n) "need-no-source"
                       (str "the need " (:id n) " cites no source; every need rests on one")))))

(defn stack-word-findings
  "A finding for each line of `text` (the file `file`) naming one of
  `words`, matched as a whole word, whatever its case."
  [file text words]
  (let [res (for [w words :when (not (str/blank? (str w)))]
              [w (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote (str w))
                                  "(?![\\p{L}\\p{N}_])"))])]
    (vec (for [[i l] (map-indexed vector (str/split-lines (or text "")))
               [w re] res
               :when (re-find re l)]
           (command/finding file (inc i) "spec-stack-word"
                            (str "names the stack word \"" w "\"; the spec never names an implementation"))))))

;; ---------------------------------------------------------------------------
;; The check

(defn spec-findings
  "Every finding of the spec check on the read spec `spec` (parser/read-spec).
  `opts`: :id-prefix, :issued (the ledger's; nil: ids are not judged against
  it), :stack-words."
  [spec {:keys [id-prefix issued stack-words]}]
  (let [prefix (or id-prefix "spec")
        declared (when-not (:needs-error spec) (set (map :id (:needs spec))))
        ctx {:id-prefix prefix :issued issued :declared declared}]
    (vec (concat
          (parser/refusal-findings spec)
          (mapcat #(scenario-findings ctx %) (:scenarios spec))
          (duplicate-findings (:scenarios spec))
          (misplaced-findings spec)
          (need-findings spec)
          (mapcat (fn [[file text]] (stack-word-findings file text stack-words)) (:texts spec))))))

(defn check
  "The spec check's stage: reads the spec in the working tree, publishes
  SpecParsed, checks it and publishes SpecGated. Cannot run with no spec
  directory."
  [req]
  (let [config (:config req)
        spec-dir (command/config-value req :spec-dir)
        _ (when-not (fs/directory? (command/path req spec-dir))
            (command/cannot-run! (str "no spec directory " spec-dir)))
        spec (parser/read-spec (:root req) {:spec-dir spec-dir :id-prefix (:id-prefix config)
                                            :model (:model config) :needs (:needs config)})
        _ (swap! (:state req) assoc ::parser/spec spec)
        _ (parser/publish! req spec)
        ledger-rel (or (:ledger config) (str (str/replace spec-dir #"/+$" "") "/ids.edn"))
        ledger (read-ledger (:root req) ledger-rel)
        findings (vec (concat (when (:finding ledger) [(:finding ledger)])
                              (spec-findings spec {:id-prefix (:id-prefix config)
                                                   :issued (:issued ledger)
                                                   :stack-words (:stack-words config)})))
        passed? (empty? (remove command/warning? findings))]
    (command/publish! req :spec/check :SpecGated
                      {:revision (:revision spec)
                       :passed? passed?
                       :findings (mapv #(select-keys % [:file :line :rule :message]) findings)})
    {:result {:spec-check {:passed? passed? :scenarios (count (:scenarios spec)) :findings (count findings)}}
     :findings findings}))
