(ns rdm-spec.spec.check
  "The spec check (dhf/spec/spec/check.feature): one id and one level per
  scenario, only known tags, every scenario tracing to a declared need, every
  need citing a source, and no stack word anywhere in the spec. Publishes
  SpecGated."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.spec.parser :as parser]))

(defn- id-number [id] (parse-long (re-find #"\d+$" id)))

(defn allowed-tags [prefix]
  (str "a scenario may carry @" prefix "-NNNN (its id), @e2e or @contract (its level), @UN-nnn (a need) and @wip"))

(defn scenario-findings
  "The findings about the scenarios' ids, levels, tags and needs."
  [{:keys [scenarios needs issued prefix]}]
  (let [re (parser/id-re prefix)
        declared (set (map :id needs))
        by-id (group-by identity (mapcat :ids scenarios))
        where (fn [s] (str (:file s) ":" (:line s)))
        occurrences (group-by first (for [s scenarios id (:ids s)] [id s]))
        f (fn [s rule msg] (cmd/finding (:file s) (:line s) rule msg))
        name-of (fn [s] (or (:id s) (str "\"" (:title s) "\"")))]
    (vec
     (for [s scenarios
           x (concat
              (case (count (:ids s))
                0 [(f s "scenario-unstamped" (str "the scenario \"" (:title s) "\" carries no id: run \"rdm-spec stamp\""))]
                1 []
                [(f s "scenario-ids" (str "the scenario \"" (:title s) "\" carries " (count (:ids s)) " ids, "
                                          (str/join " and " (:ids s)) "; a scenario carries exactly one"))])
              (for [id (:ids s)
                    :let [others (remove #(= s %) (map second (occurrences id)))]
                    :when (seq others)]
                (f s "id-duplicate" (str id " is used by " (inc (count others)) " scenarios: here and at "
                                         (str/join ", " (map where others)))))
              (for [id (:ids s) :when (and (integer? issued) (> (id-number id) issued))]
                (f s "id-unissued" (str id " is above " issued ", the highest id the ledger issued: the ledger never issued it")))
              (case (count (:levels s))
                1 []
                0 [(f s "level-missing" (str (name-of s) " carries no level: a scenario carries exactly one of the two levels, @e2e or @contract"))]
                [(f s "level-multiple" (str (name-of s) " carries " (str/join " and " (map #(str "@" %) (:levels s)))
                                            ": a scenario carries exactly one of the two levels, @e2e or @contract"))])
              (for [t (:tags s)
                    :when (not (or (re-matches re t) (#{"e2e" "contract" "wip"} t) (re-matches #"UN-\d+" t)))]
                (f s "tag-unknown" (str (name-of s) " carries the unknown tag @" t "; " (allowed-tags prefix))))
              (for [t (concat (:feature-tags s) (:rule-tags s))
                    :when (not (re-matches #"UN-\d+" t))]
                (f s (if (or (re-matches re t) (#{"e2e" "contract" "wip"} t)) "tag-misplaced" "tag-unknown")
                   (str "the tag @" t " above " (name-of s) " is on its feature or rule; "
                        "an id, a level and @wip go on a scenario only, and only a need tag goes on a feature or a rule")))
              (when (empty? (:needs s))
                [(f s "need-missing" (str (name-of s) " traces to no user need: tag it, its rule or its feature with one"))])
              (for [n (:needs s) :when (not (declared n))]
                (f s "need-undeclared" (str (name-of s) " traces to " n ", which dhf/spec/needs.edn does not declare"))))]
       x))))

(defn line-of
  "The 1-based line of the first occurrence of s in text, or nil."
  [text s]
  (when text
    (when-let [i (str/index-of text s)]
      (inc (count (filter #{\newline} (subs text 0 i)))))))

(defn stack-findings
  "A finding for every line of every [file text] that names a stack word."
  [files words]
  (vec
   (for [[file text] files
         [i line] (map-indexed vector (str/split-lines (str text)))
         w words
         :when (re-find (re-pattern (str "(?<![\\p{L}\\p{N}])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}])")) line)]
     (cmd/finding file (inc i) "stack-word"
                  (str "names the stack word " w ": the spec and the wiki never name the stack, which lives in dhf/harness/stack.md")))))

(defn stage
  "The spec check's stage: reads SpecParsed, checks it, publishes SpecGated."
  [{:keys [root config revision]}]
  (let [{:keys [scenarios needs] :as parsed} (bus/latest :SpecParsed)
        prefix (:id-prefix config "spec")
        ledger-path (:ledger config "dhf/spec/ids.edn")
        needs-path (:needs config "dhf/spec/needs.edn")
        ledger-src (cmd/read-at root revision ledger-path)
        issued (try (:issued (cmd/read-edn ledger-src)) (catch Exception _ nil))
        needs-src (cmd/read-at root revision needs-path)
        needs-ok (try (map? (cmd/read-edn needs-src)) (catch Exception _ false))
        spec-files (cmd/files-at root revision (:spec-dir config "dhf/spec"))
        findings (vec
                  (concat
                   (when-not (integer? issued)
                     [(cmd/finding ledger-path nil "ledger-unreadable"
                                   (if ledger-src "the ledger holds no :issued count" "the ledger is missing"))])
                   (when-not needs-ok
                     [(cmd/finding needs-path nil "needs-unreadable"
                                   (if needs-src "the needs are not an EDN map" "the needs file is missing"))])
                   (scenario-findings {:scenarios scenarios :needs needs :issued issued :prefix prefix})
                   (for [n needs :when (empty? (:sources n))]
                     (cmd/finding needs-path (line-of needs-src (str "\"" (:id n) "\"")) "need-unsourced"
                                  (str (:id n) " cites no source: every user need rests on a raw source")))
                   (stack-findings (for [f spec-files] [f (cmd/read-at root revision f)])
                                   (:stack-words config []))))]
    (bus/publish! :spec/check :SpecGated {:revision (:revision parsed) :passed? (empty? findings) :findings findings})
    {:findings findings}))
