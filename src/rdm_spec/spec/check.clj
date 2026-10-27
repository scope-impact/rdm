(ns rdm-spec.spec.check
  "The spec check (dhf/spec/spec/check.feature): the rules a spec keeps.
  Every scenario carries exactly one id the ledger issued, used by no other
  scenario, exactly one level, only known tags in their places, and traces to
  a declared need; every need rests on a source; no file of the spec names a
  configured stack word; and the parser's refusals. Publishes SpecGated."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.spec.stamper :as stamper]))

(def need-re #"UN-\d+")

(defn- allowed [prefix]
  (str "a scenario may carry @" prefix "-NNNN (its id), @e2e or @contract (its level), @UN-nnn (a need) and @wip"))

(defn- label [s] (or (:id s) (first (:ids s)) (str "the scenario \"" (:title s) "\"")))

(defn scenario-findings
  "The findings about scenarios. opts: :prefix :issued :needs (a set)."
  [scenarios {:keys [prefix issued needs]}]
  (let [id-re (re-pattern (str (java.util.regex.Pattern/quote prefix) "-(\\d{4,})"))
        known? (fn [t] (or (re-matches id-re t) (#{"e2e" "contract" "wip"} t) (re-matches need-re t)))
        f (fn [s rule msg] (cmd/finding (:file s) (:line s) rule msg))]
    (distinct
     (concat
      (for [s scenarios
            [where tags line] [["feature" (:feature-tags s) nil] ["rule" (:rule-tags s) nil]]
            t tags :when (not (re-matches need-re t))]
        (cmd/finding (:file s) line "tag-misplaced"
                     (str "the " where " " (pr-str (if (= where "feature") (:feature s) (:rule s))) " carries @" t
                          ": a feature or a rule carries only need tags (@UN-nnn); ids, levels and @wip go on a scenario")))
      (for [s scenarios ex (:examples s) t (:tags ex)]
        (cmd/finding (:file s) (:line ex) "tag-misplaced" (str "Examples carry @" t ": tags go on a scenario, a rule or a feature")))
      (mapcat
       (fn [s]
         (let [ids (:ids s) levels (:levels s)]
           (concat
            (for [t (:tags s) :when (not (known? t))]
              (f s "tag-unknown" (str (label s) " carries @" t ": " (allowed prefix))))
            (cond
              (empty? ids) [(f s "scenario-unstamped" (str "the scenario \"" (:title s) "\" has no id: run \"rdm-spec stamp\""))]
              (> (count ids) 1) [(f s "scenario-ids" (str "the scenario carries " (count ids) " ids, " (str/join " and " ids)
                                                         ": a scenario carries exactly one"))])
            (for [id ids
                  :let [n (parse-long (second (re-matches id-re id)))]
                  :when (and issued (> n issued))]
              (f s "id-unissued" (str id " is above the ledger's highest id, " issued ": the ledger never issued it; run \"rdm-spec stamp\"")))
            (case (count levels)
              0 [(f s "level-missing" (str (label s) " carries no level: a scenario carries exactly one of @e2e and @contract"))]
              1 []
              [(f s "level-twice" (str (label s) " carries " (str/join " and " (map #(str "@" %) levels))
                                       ": a scenario carries exactly one of @e2e and @contract"))])
            (if (empty? (:needs s))
              [(f s "need-missing" (str (label s) " traces to no user need: tag it, its rule or its feature with @UN-nnn"))]
              (for [n (:needs s) :when (not (needs n))]
                (f s "need-undeclared" (str (label s) " traces to " n ", which the needs do not declare")))))))
       scenarios)
      (let [uses (for [s scenarios id (:ids s)] [id s])]
        (for [[id us] (group-by first uses) :when (> (count us) 1) [_ s] us]
          (f s "id-duplicate" (str id " is used by " (count us) " scenarios: "
                                   (str/join " and " (map (fn [[_ x]] (str (:file x) ":" (:line x))) us))))))))))

(defn stack-words
  "[{:line :word}] of every configured stack word a text names."
  [text words]
  (for [[i line] (map-indexed vector (str/split-lines (str text)))
        w words
        :when (re-find (re-pattern (str "(?<![\\p{L}\\p{N}])" (java.util.regex.Pattern/quote w) "(?![\\p{L}\\p{N}])")) line)]
    {:line (inc i) :word w}))

(defn check
  "Every finding of the spec check, at a revision."
  [root rev config]
  (let [spec-dir (:spec-dir config "dhf/spec")
        ledger (:ledger config "dhf/spec/ids.edn")
        needs-file (:needs config "dhf/spec/needs.edn")
        prefix (:id-prefix config "spec")
        {:keys [scenarios refusals]} (parser/read-spec root rev config)
        issued (stamper/issued root rev config)
        ntext (cmd/read-at root rev needs-file)
        nmap (try (cmd/read-edn ntext) (catch Exception _ ::unreadable))]
    {:scenarios scenarios
     :findings
     (vec
      (concat
       refusals
       (when (nil? issued)
         [(cmd/finding ledger nil "ledger-unreadable" "the ledger is missing or holds no {:issued N}")])
       (when-not (map? nmap)
         [(cmd/finding needs-file nil "needs-unreadable" "the needs are missing or are not a map of need to {:text :sources}")])
       (for [[id v] (when (map? nmap) (sort-by (comp str key) nmap))
             :when (empty? (:sources v))]
         (cmd/finding needs-file (parser/line-in ntext (str "\"" (name id) "\"")) "need-unsourced"
                      (str (name id) " cites no source: every user need rests on a raw source")))
       (scenario-findings scenarios {:prefix prefix :issued issued
                                     :needs (if (map? nmap) (set (map name (keys nmap))) #{})})
       (for [f (cmd/files-at root rev spec-dir)
             :when (re-find #"\.(feature|md|edn|txt)$" f)
             {:keys [line word]} (stack-words (cmd/read-at root rev f) (:stack-words config []))]
         (cmd/finding f line "stack-word"
                      (str "names the stack word " word ": the spec never names its stack, which lives in dhf/harness/stack.md")))))}))

(defn stage
  "The spec check's stage: publishes SpecGated."
  [{:keys [root config revision]}]
  (let [{:keys [scenarios findings]} (check root revision config)]
    (bus/publish! :spec/check :SpecGated
                  {:revision (if (cmd/working-tree? revision) :working-tree revision)
                   :passed? (empty? findings) :findings findings})
    {:findings findings
     :result {:spec {:scenarios (count scenarios) :findings (count findings)}}}))
