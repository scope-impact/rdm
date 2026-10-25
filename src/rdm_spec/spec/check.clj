(ns rdm-spec.spec.check
  "The Spec check component of the spec context (dhf/spec/spec/check.feature):
  the rules a spec must keep. It reads the spec with the Parser, publishes
  SpecParsed (for the stages after it) and SpecGated, and reports every
  finding; the model lint, the views and the knowledge are other stages of
  `rdm-spec check`.

  The rules, each a stable name on the error stream:
    (the parser's)        a construct outside the Gherkin subset, relayed
                          with its file, line and rule unchanged
    scenario-no-id        a scenario with no id: run rdm-spec stamp
    scenario-many-ids     a scenario with more than one id
    id-duplicate          an id carried by more than one scenario
    id-unissued           an id above the highest the ledger has issued
    ledger-unreadable     a ledger that is not EDN with an :issued number
    scenario-no-level     a scenario with no level (@e2e or @contract)
    scenario-many-levels  a scenario with both levels
    tag-unknown           a tag that is not an id, a level, a need or @wip
    tag-misplaced         an id, a level or @wip on a feature, a rule or
                          examples; an id, a level and @wip go on a scenario
    scenario-no-need      a scenario tracing to no need, by a tag on it, its
                          rule or its feature
    need-undeclared       a need tag naming no need the needs file declares
    needs-unreadable      a needs file that is missing or not a map of needs
    need-no-source        a declared need citing no source
    need-unresolved       a need every citation of which failed to resolve
                          (from KnowledgeIndexed; the Sources component names
                          each citation and why)
    stack-word            a spec file naming a configured stack word

  Every rule but the parser's is pure (`findings`), over the parsed spec."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]))

;; ---------------------------------------------------------------------------
;; Tags

(defn allowed-tags
  "The tags a scenario may carry, as a reader is told them."
  [prefix]
  (str "an id (@" prefix "-NNNN), a level (@e2e or @contract), a need (@UN-nnn) and @wip"))

(defn tag-kind
  "What the tag `t` is: :id, :level, :need, :wip or :unknown."
  [prefix t]
  (cond (re-matches (parser/id-pattern prefix) t) :id
        (parser/level-tags t) :level
        (re-matches parser/need-pattern t) :need
        (= parser/wip-tag t) :wip
        :else :unknown))

(defn- id-number [prefix id]
  (some-> (re-matches (re-pattern (str (java.util.regex.Pattern/quote prefix) "-(\\d+)")) id) second parse-long))

(defn- label
  "How a finding names the scenario: its id when it has exactly one, else
  its title."
  [sc]
  (or (:id sc) (str "the scenario \"" (:title sc) "\"")))

(defn- at [file line rule message] (command/finding file line rule message))

;; ---------------------------------------------------------------------------
;; The rules

(defn- id-findings [prefix sc]
  (let [{:keys [ids file line title]} sc]
    (case (count ids)
      0 [(at file line "scenario-no-id"
             (str "the scenario \"" title "\" carries no @" prefix "- id; run \"rdm-spec stamp\" to give it one"))]
      1 []
      [(at file line "scenario-many-ids"
           (str "the scenario \"" title "\" carries " (count ids) " ids, "
                (str/join " and " (map #(str "@" %) ids)) "; a scenario carries exactly one"))])))

(defn- level-findings [sc]
  (let [{:keys [levels file line]} sc]
    (case (count levels)
      0 [(at file line "scenario-no-level"
             (str (label sc) " carries no level; a scenario carries exactly one of @e2e and @contract"))]
      1 []
      [(at file line "scenario-many-levels"
           (str (label sc) " carries both " (str/join " and " levels)
                "; a scenario carries exactly one of @e2e and @contract"))])))

(defn- tag-findings [prefix sc]
  (let [{:keys [tags file line examples]} sc]
    (concat
     (for [t (distinct tags) :when (= :unknown (tag-kind prefix t))]
       (at file line "tag-unknown"
           (str (label sc) " carries " t ", which no scenario may carry; a scenario may carry "
                (allowed-tags prefix))))
     (for [ex examples t (distinct (:tags ex))]
       (at file (:line ex) "tag-misplaced"
           (str "the examples of " (label sc) " carry " t "; examples carry no tag, "
                (allowed-tags prefix) " go on the scenario"))))))

(defn- heading-tag-findings
  "The tags of a feature or a rule (`what` names it): only needs belong there."
  [prefix file line what tags]
  (for [t (distinct tags)
        :let [k (tag-kind prefix t)]
        :when (not= :need k)]
    (if (= :unknown k)
      (at file line "tag-unknown"
          (str what " carries " t ", which is not a tag of the spec; a feature or a rule carries needs (@UN-nnn)"
               " and a scenario may carry " (allowed-tags prefix)))
      (at file line "tag-misplaced"
          (str what " carries " t "; an id, a level and @wip go on a scenario only, a feature or a rule"
               " carries needs (@UN-nnn)")))))

(defn- need-findings [declared sc]
  (let [{:keys [needs file line]} sc]
    (if (empty? needs)
      [(at file line "scenario-no-need"
           (str (label sc) " traces to no user need: tag it, its rule or its feature with a declared @UN-nnn"))]
      (for [n needs :when (and declared (not (contains? declared n)))]
        (at file line "need-undeclared"
            (str (label sc) " traces to @" n ", which the needs file does not declare"))))))

(defn- unissued-findings [prefix issued ledger-rel sc]
  (when issued
    (for [id (:ids sc)
          :let [n (id-number prefix id)]
          :when (and n (> n issued))]
      (at (:file sc) (:line sc) "id-unissued"
          (str id " is above " (if (pos? issued) (str "the highest id the ledger " ledger-rel " has issued, "
                                                      (format "%s-%04d" prefix issued))
                                   (str "every id: the ledger " ledger-rel " has issued none"))
               "; the ledger never issued it. Ids are handed out by \"rdm-spec stamp\", never by hand")))))

(defn- duplicate-findings [scenarios]
  (let [carried (for [sc scenarios id (:ids sc)] {:id id :file (:file sc) :line (:line sc)})]
    (for [[id places] (group-by :id carried)
          :when (> (count places) 1)
          :let [[_ second-place] places]]
      (at (:file second-place) (:line second-place) "id-duplicate"
          (str id " is carried by " (count places) " scenarios, "
               (str/join ", " (map #(str (:file %) ":" (:line %)) places))
               "; an id is carried by one scenario")))))

(defn- rules-of
  "Every distinct rule heading of the scenarios: [{:file :line :name :tags}]."
  [scenarios]
  (distinct (for [sc scenarios :when (:rule-line sc)]
              {:file (:file sc) :line (:rule-line sc) :name (:rule sc) :tags (:rule-tags sc)})))

(defn- source-findings [needs-file needs knowledge]
  (let [by-need (group-by :need (filter :need (:citations knowledge)))]
    (concat
     (for [{:keys [id sources line]} needs :when (empty? sources)]
       (at needs-file line "need-no-source"
           (str id " cites no source; every user need rests on a raw source, cited as <source>:<path>@<commit>")))
     (for [{:keys [id sources line]} needs
           :let [cs (get by-need id)]
           :when (and (seq sources) (seq cs) (every? #(= :unresolved (:status %)) cs))]
       (at needs-file line "need-unresolved"
           (str id " rests on no source that resolves: every citation it makes fails to resolve"))))))

(defn word-pattern
  "Matches the stack word `w` as a whole word, whatever its case."
  [w]
  (re-pattern (str "(?i)(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}_])")))

(defn stack-word-findings
  "A finding for each line of the file `file` holding `text` that names one
  of `words`."
  [file text words]
  (let [res (for [w words :when (and (string? w) (not (str/blank? w)))] [w (word-pattern w)])]
    (for [[i l] (map-indexed vector (str/split-lines (or text "")))
          [w re] res
          :when (re-find re l)]
      (at file (inc i) "stack-word"
          (str "names the stack word \"" w "\"; the spec never names an implementation"
               " (the stack is recorded in dhf/harness/stack.md)")))))

(defn findings
  "Pure: every finding of the spec check on the parsed `spec`
  (parser/read-spec). `opts`: :id-prefix, :issued (the ledger's highest id,
  nil when not known), :ledger (its path), :stack-words, :knowledge
  (KnowledgeIndexed's payload, or nil)."
  [spec {:keys [id-prefix issued ledger stack-words knowledge]}]
  (let [prefix (or id-prefix "spec")
        scenarios (:scenarios spec)
        declared (when-not (:needs-error spec) (set (map :id (:needs spec))))]
    (vec (concat
          (parser/refusal-findings spec)
          (when-let [e (:needs-error spec)]
            [(at (:needs-file spec) nil "needs-unreadable" e)])
          (for [ft (:features spec)
                f (heading-tag-findings prefix (:file ft) (:line ft) (str "the feature \"" (:name ft) "\"") (:tags ft))]
            f)
          (for [r (rules-of scenarios)
                f (heading-tag-findings prefix (:file r) (:line r) (str "the rule \"" (:name r) "\"") (:tags r))]
            f)
          (mapcat #(id-findings prefix %) scenarios)
          (duplicate-findings scenarios)
          (mapcat #(unissued-findings prefix issued ledger %) scenarios)
          (mapcat level-findings scenarios)
          (mapcat #(tag-findings prefix %) scenarios)
          (mapcat #(need-findings declared %) scenarios)
          (source-findings (:needs-file spec) (:needs spec) knowledge)
          (for [[file text] (:texts spec)
                f (stack-word-findings file text stack-words)]
            f)))))

;; ---------------------------------------------------------------------------
;; The ledger

(defn read-ledger
  "{:issued n} from the ledger `rel` under `root` (0 when there is none,
  as the stamper reads it), or {:finding f} when it cannot be read."
  [root rel]
  (let [f (command/path root rel)]
    (if-not (fs/regular-file? f)
      {:issued 0}
      (let [v (try (edn/read-string (slurp (str f))) (catch Exception e {::error (ex-message e)}))]
        (cond
          (and (map? v) (::error v))
          {:finding (at rel nil "ledger-unreadable" (str "the ledger " rel " is not EDN: " (::error v)))}

          (and (map? v) (integer? (:issued v)) (not (neg? (:issued v))))
          {:issued (:issued v)}

          :else
          {:finding (at rel nil "ledger-unreadable" (str "the ledger " rel " records no :issued number"))})))))

;; ---------------------------------------------------------------------------
;; The stage

(defn subscriptions
  "Keeps KnowledgeIndexed's payload: which of the needs' citations resolve."
  [req]
  [[:KnowledgeIndexed (fn [payload] (swap! (:state req) assoc ::knowledge payload) nil)]])

(defn check
  "The spec check, a stage of `rdm-spec check` and `rdm-spec publish`: reads
  the spec in the working tree, publishes SpecParsed, checks it and
  publishes SpecGated."
  [req]
  (let [config (:config req)
        spec-dir (command/config-value req :spec-dir)
        _ (when-not (fs/directory? (command/path req spec-dir))
            (command/cannot-run! (str "no spec directory " spec-dir)))
        spec (parser/read-spec (:root req) {:spec-dir spec-dir :id-prefix (:id-prefix config)
                                            :model (:model config) :needs (:needs config)})
        _ (some-> (:state req) (swap! assoc :rdm-spec.spec.parser/spec spec))
        _ (parser/publish! req spec)
        ledger (:ledger config)
        {:keys [issued finding]} (when ledger (read-ledger (:root req) ledger))
        found (vec (concat (when finding [finding])
                           (findings spec {:id-prefix (:id-prefix config)
                                           :issued issued
                                           :ledger ledger
                                           :stack-words (:stack-words config)
                                           :knowledge (some-> (:state req) deref ::knowledge)})))
        passed? (empty? found)]
    (command/publish! req :spec/check :SpecGated
                      {:revision (:revision spec)
                       :passed? passed?
                       :findings (mapv #(select-keys % [:file :line :rule :message]) found)})
    {:result {:spec {:revision (:revision spec)
                     :scenarios (count (:scenarios spec))
                     :needs (count (:needs spec))
                     :passed? passed?}}
     :findings found}))
