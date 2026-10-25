(ns rdm-spec.spec.check
  "Spec check (dhf/spec/spec/check.feature): the rules a spec must keep. A
  stage of `rdm-spec check` (and, for its events only, of publish).

  It reads the spec in the working tree with the Parser (or takes the spec
  a parse stage of this run already read), publishes SpecParsed when it read
  it itself, holds it to the rules below and publishes SpecGated.

  Rules, each a stable name on the error stream (FILE:LINE: RULE: what):
    the parser's own refusals, relayed with their file, line and rule
    scenario-no-id        a scenario with no id; run rdm-spec stamp
    scenario-ids          a scenario with more than one id
    id-duplicate          an id two scenarios carry; names every file that carries it
    id-not-issued         an id above the ledger's highest issued id
    ledger-unreadable     the ledger is missing or is not {:issued n}
    scenario-no-level     a scenario with no level; names the two levels
    scenario-levels       a scenario with more than one level
    tag-unknown           a tag that is no id, level, need or @wip; lists the allowed ones
    tag-misplaced         an id or level on a feature, rule or examples, @wip off a scenario
    scenario-no-need      a scenario that traces to no need (on it, its rule or its feature)
    need-undeclared       a need tag naming no declared need
    needs-unreadable      the needs file is missing or unreadable
    need-no-source        a declared need citing no source
    stack-word            a file of the spec naming a configured stack word

  Result: {:spec {:passed? bool :scenarios n :findings n}}."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]))

(defn allowed-tags
  "The tags a scenario may carry, as the findings list them."
  [prefix]
  (str "@" prefix "-NNNN (its id), @e2e or @contract (its level), @UN-nnn (a need) and @wip"))

;; ---------------------------------------------------------------------------
;; Pure rules over the parsed spec

(defn- where [sc] (str "the scenario \"" (:title sc) "\""))

(defn- named [sc] (or (:id sc) (where sc)))

(defn id-findings
  "One id per scenario, each id on one scenario and issued by the ledger
  (`issued`, nil when the ledger cannot be read)."
  [prefix scenarios issued]
  (let [by-id (group-by identity (mapcat :ids scenarios))
        carriers (fn [id] (filter #(some #{id} (:ids %)) scenarios))]
    (vec
     (concat
      (for [sc scenarios
            :let [ids (:ids sc)]
            :when (not= 1 (count ids))]
        (if (empty? ids)
          (command/finding (:file sc) (:line sc) "scenario-no-id"
                           (str (where sc) " carries no @" prefix "- id: run \"rdm-spec stamp\" to give it one"))
          (command/finding (:file sc) (:line sc) "scenario-ids"
                           (str (where sc) " carries " (count ids) " ids, " (str/join " and " ids)
                                ": a scenario carries exactly one"))))
      (for [id (sort (keys by-id))
            :when (> (count (carriers id)) 1)
            :let [cs (carriers id)
                  places (str/join ", " (map #(str (:file %) ":" (:line %)) cs))]
            sc cs]
        (command/finding (:file sc) (:line sc) "id-duplicate"
                         (str id " is carried by " (count cs) " scenarios (" places "): an id names one scenario")))
      (when issued
        (for [sc scenarios
              id (distinct (:ids sc))
              :let [n (parse-long (re-find #"\d+$" id))]
              :when (> n issued)]
          (command/finding (:file sc) (:line sc) "id-not-issued"
                           (str id " is above every id the ledger has issued (" issued
                                "): the ledger never issued it; run \"rdm-spec stamp\" to stamp the scenario"))))))))

(defn level-findings
  "Exactly one level per scenario."
  [scenarios]
  (vec
   (for [sc scenarios
         :let [ls (:levels sc)]
         :when (not= 1 (count ls))]
     (if (empty? ls)
       (command/finding (:file sc) (:line sc) "scenario-no-level"
                        (str (named sc) " carries no level: tag it @e2e or @contract"))
       (command/finding (:file sc) (:line sc) "scenario-levels"
                        (str (named sc) " carries the levels " (str/join " and " ls)
                             ": a scenario carries exactly one, @e2e or @contract"))))))

(defn- kind-of [prefix tag]
  (cond (re-matches (parser/id-pattern prefix) tag) :id
        (contains? parser/level-tags tag) :level
        (re-matches parser/need-pattern tag) :need
        (= parser/wip-tag tag) :wip
        :else nil))

(defn tag-findings
  "Only known tags, each where it may go: an id and a level on a scenario, a
  need anywhere, @wip on a scenario. `features` as the parser reads them."
  [prefix features scenarios]
  (let [unknown (fn [file line tag who]
                  (command/finding file line "tag-unknown"
                                   (str who " carries the tag " tag ", which is not one a spec may carry; a scenario may carry "
                                        (allowed-tags prefix))))
        misplaced (fn [file line tag where]
                    (command/finding file line "tag-misplaced"
                                     (str "the tag " tag " is on " where ": an id, a level and @wip go on a scenario only")))
        rules (distinct (for [sc scenarios :when (:rule-line sc)]
                          {:file (:file sc) :line (:rule-line sc) :rule (:rule sc) :tags (:rule-tags sc)}))]
    (vec
     (concat
      (for [sc scenarios t (:tags sc) :when (nil? (kind-of prefix t))]
        (unknown (:file sc) (:line sc) t (named sc)))
      (for [sc scenarios ex (:examples sc) t (:tags ex)]
        (if (kind-of prefix t)
          (misplaced (:file sc) (:line ex) t (str "the examples of " (named sc)))
          (unknown (:file sc) (:line ex) t (str "the examples of " (named sc)))))
      (for [f features t (:tags f)
            :let [k (kind-of prefix t)]
            :when (not= :need k)]
        (if k
          (misplaced (:file f) (:line f) t (str "the feature \"" (:name f) "\""))
          (unknown (:file f) (:line f) t (str "the feature \"" (:name f) "\""))))
      (for [r rules t (:tags r)
            :let [k (kind-of prefix t)]
            :when (not= :need k)]
        (if k
          (misplaced (:file r) (:line r) t (str "the rule \"" (:rule r) "\""))
          (unknown (:file r) (:line r) t (str "the rule \"" (:rule r) "\""))))))))

(defn need-findings
  "Every scenario traces to a declared need; every declared need cites a
  source. `needs` [{:id :sources :line}], `needs-file` where they are."
  [scenarios needs needs-file needs-error]
  (let [declared (set (map :id needs))]
    (vec
     (concat
      (when needs-error
        [(command/finding needs-file nil "needs-unreadable" needs-error)])
      (for [sc scenarios :when (empty? (:needs sc))]
        (command/finding (:file sc) (:line sc) "scenario-no-need"
                         (str (named sc) " traces to no user need: tag it, its rule or its feature with a declared @UN-nnn")))
      (when-not needs-error
        (for [sc scenarios n (:needs sc) :when (not (declared n))]
          (command/finding (:file sc) (:line sc) "need-undeclared"
                           (str (named sc) " traces to " n ", which " needs-file " does not declare"))))
      (for [n needs :when (empty? (remove #(str/blank? (str %)) (:sources n)))]
        (command/finding needs-file (:line n) "need-no-source"
                         (str "the need " (:id n) " cites no source: every need rests on a raw source, <source>:<path>@<commit>")))))))

(defn stack-word-findings
  "A finding for each line of each file `{:file :text}` naming a configured
  stack word, matched as a whole word."
  [files words]
  (let [words (remove str/blank? (map str words))
        res (map (fn [w] [w (re-pattern (str "(?<![\\p{L}\\p{N}_])" (java.util.regex.Pattern/quote w)
                                             "(?![\\p{L}\\p{N}_])"))])
                 words)]
    (vec (for [{:keys [file text]} files
               [i l] (map-indexed vector (str/split-lines (str text)))
               [w re] res
               :when (re-find re l)]
           (command/finding file (inc i) "stack-word"
                            (str "names the stack word " w ": the spec never names its stack, which lives in dhf/harness/stack.md"))))))

(defn read-ledger
  "{:issued n} or {:error why} from the ledger's text (nil: no file)."
  [rel text]
  (if (nil? text)
    {:error (str "the ledger " rel " does not exist")}
    (let [v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))]
      (cond
        (and (map? v) (::error v)) {:error (str "the ledger " rel " is not readable EDN: " (::error v))}
        (and (map? v) (integer? (:issued v)) (>= (:issued v) 0)) {:issued (:issued v)}
        :else {:error (str "the ledger " rel " is not {:issued n}")}))))

(defn check-spec
  "Every finding of the spec check on the spec `spec` (as the Parser reads
  it). `opts`: :prefix, :ledger {:issued n} or {:error}, :ledger-file,
  :stack-words."
  [spec {:keys [prefix ledger ledger-file stack-words]}]
  (let [scs (:scenarios spec)]
    (vec (concat
          (parser/refusal-findings spec)
          (when (:error ledger) [(command/finding ledger-file nil "ledger-unreadable" (:error ledger))])
          (id-findings prefix scs (:issued ledger))
          (level-findings scs)
          (tag-findings prefix (:features spec) scs)
          (need-findings scs (:needs spec) (:needs-file spec) (:needs-error spec))
          (stack-word-findings (parser/text-files nil spec) stack-words)))))

;; ---------------------------------------------------------------------------
;; The stage

(defn check
  "The spec check's stage: see the namespace doc."
  [req]
  (let [config (:config req)
        spec-dir (command/config-value req :spec-dir)
        prefix (or (:id-prefix config) "spec")
        _ (when-not (fs/directory? (command/path req spec-dir))
            (command/cannot-run! (str "no spec directory " spec-dir)))
        read? (some-> (:state req) deref ::parser/spec)
        spec (or read?
                 (parser/read-spec (:root req) {:spec-dir spec-dir :id-prefix prefix
                                                :model (:model config) :needs (:needs config)}))
        _ (when-not read?
            (swap! (:state req) assoc ::parser/spec spec)
            (parser/publish! req spec))
        ledger-rel (or (:ledger config) (str spec-dir "/ids.edn"))
        ledger-f (command/path req ledger-rel)
        ledger (read-ledger ledger-rel (when (fs/regular-file? ledger-f) (slurp (str ledger-f))))
        findings (check-spec spec {:prefix prefix :ledger ledger :ledger-file ledger-rel
                                   :stack-words (:stack-words config)})
        passed? (not-any? #(not (command/warning? %)) findings)]
    (command/publish! req :spec/check :SpecGated
                      {:revision (:revision spec)
                       :passed? passed?
                       :findings (mapv #(select-keys % [:file :line :rule :message]) findings)})
    {:result {:spec {:passed? passed? :scenarios (count (:scenarios spec)) :findings (count findings)}}
     :findings findings}))
