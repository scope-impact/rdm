(ns rdm-spec.spec.check
  "The spec check: one id and one level per scenario, only known tags, a need
  resting on a source, planned work marked, no stack word; under the slices
  layout, every slice planned, with its status and its decisions
  (dhf/spec/02-write-the-spec/check.feature, 12-several-specs/check.feature,
  13-slice-development/check.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]))

(defn- id-num [id] (some-> (re-find #"-(\d+)$" (str id)) second parse-long))

(defn- who [sc] (or (:id sc) (first (:ids sc)) (str "the scenario \"" (:title sc) "\"")))

(def allowed-tags-text
  "a scenario may carry one id (@PREFIX-NNNN), one level (@e2e or @contract), need tags (@UN-nnn) and @wip")

(defn scenario-findings
  "The findings about the scenarios of one spec: ids, levels, tags and needs."
  [scenarios {:keys [prefix issued declared]}]
  (let [f (fn [sc rule msg] (project/finding (:file sc) (:line sc) rule msg))
        id-re (re-pattern (str (java.util.regex.Pattern/quote prefix) "-\\d{4,}"))
        by-id (group-by identity (mapcat :ids scenarios))
        where (fn [id] (for [sc scenarios :when (some #{id} (:ids sc))] (str (:file sc) ":" (:line sc))))]
    (vec
     (concat
      (for [sc scenarios
            finding
            (concat
             (cond
               (empty? (:ids sc))
               [(f sc "scenario-unstamped" (str "the scenario \"" (:title sc) "\" carries no @" prefix "- id: run \"rdm-spec stamp\""))]
               (> (count (:ids sc)) 1)
               [(f sc "scenario-ids" (str "the scenario \"" (:title sc) "\" carries " (count (:ids sc)) " ids, "
                                          (str/join " and " (:ids sc)) ": a scenario carries exactly one"))])
             (for [id (:ids sc)
                   :when (> (count (by-id id)) 1)]
               (f sc "id-duplicate" (str id " is carried by more than one scenario: " (str/join ", " (where id)))))
             (for [id (:ids sc)
                   :when (and (number? issued) (> (id-num id) issued))]
               (f sc "id-unissued" (str id " is above " issued ", the highest id the ledger has issued: the ledger never issued it")))
             (cond
               (empty? (:levels sc))
               [(f sc "level-missing" (str (who sc) " carries no level: it carries exactly one of the two levels, @e2e or @contract"))]
               (> (count (:levels sc)) 1)
               [(f sc "level-twice" (str (who sc) " carries " (str/join " and " (map #(str "@" %) (:levels sc)))
                                         ": it carries exactly one level, @e2e or @contract"))])
             (for [t (:own-tags sc)
                   :when (not (or (re-matches id-re t) (#{"e2e" "contract" "wip"} t) (re-matches #"UN-\d+" t)))]
               (f sc "tag-unknown" (str (who sc) " carries @" t ", which is not a tag of the spec: " (str/replace allowed-tags-text "PREFIX" prefix))))
             (when (empty? (:needs sc))
               [(f sc "need-missing" (str (who sc) " traces to no user need: tag it, its rule or its feature with @UN-nnn"))])
             (for [n (:needs sc) :when (and declared (not (declared n)))]
               (f sc "need-unknown" (str (who sc) " names " n ", which the needs do not declare"))))]
        finding)))))

(defn tag-placement-findings
  "Ids, levels and @wip go on a scenario only; a feature or a rule carries need tags only."
  [features]
  (vec (for [{:keys [file feature scenarios]} features
             [line tags what] (concat [[(:line feature) (:tags feature) "the feature"]]
                                      (distinct (for [sc scenarios :when (:rule-line sc)] [(:rule-line sc) (:rule-tags sc) "the rule"])))
             t tags
             :when (not (re-matches #"UN-\d+" t))]
         (project/finding file line "tag-misplaced" (str what " carries @" t ": a feature or a rule carries need tags only")))))

(defn need-findings
  "Every user need rests on a source."
  [needs file]
  (vec (for [n needs :when (empty? (:sources n))]
         (project/finding file (:line n) "need-unsourced" (str (:id n) " cites no source: every user need rests on a source")))))

(defn stack-findings
  "Each line naming a configured stack word, one finding per word."
  [file text words]
  (vec (for [[i line] (map-indexed vector (str/split-lines (str text)))
             w words
             :when (re-find (re-pattern (str "(?<![A-Za-z0-9])" (java.util.regex.Pattern/quote w) "(?![A-Za-z0-9])")) line)]
         (assoc (project/finding file (inc i) "stack-word" (str "names " w ", a stack word: the spec never names its stack")) :word w))))

;; ------------------------------------------------------------------ slices

(def statuses ["planned" "ready" "building" "done"])

(defn- heading? [text h]
  (re-find (re-pattern (str "(?m)^#{1,6}\\s+" h "\\b")) text))

(defn- section [text h]
  (second (re-find (re-pattern (str "(?ms)^#{1,6}\\s+" h "\\b[^\\n]*\\n(.*?)(?=^#{1,6}\\s|\\z)")) text)))

(defn plan-status
  "The status line under a plan's title, or nil."
  [text]
  (let [lines (drop-while #(not (re-find #"^#\s" %)) (str/split-lines (str text)))
        after (first (remove str/blank? (rest lines)))]
    (when-let [[_ s] (and after (re-matches #"\s*status:\s*(\S+)\s*" after))] s)))

(defn plan-findings
  "The findings about one slice's plan.md (text nil when there is none)."
  [file text declared]
  (if (nil? text)
    [(project/finding file "slice-unplanned" "the slice holds no plan.md: every slice is planned")]
    (let [missing (remove #(heading? text %) ["Goal" "References" "Scope" "Decisions"])
          scope (str (section text "Scope"))
          in-out (remove #(re-find (re-pattern (str "(?m)^\\s*(?:[-*#]+\\s*)?(?:\\*\\*)?" % "\\b")) scope) ["In" "Out"])
          refs (str (section text "References"))
          named (distinct (re-seq #"UN-\d+" refs))
          st (plan-status text)]
      (vec (concat
            (for [h missing] (project/finding file "slice-unplanned" (str "the plan has no \"" h "\" heading: a plan says its goal, its references, its scope and its decisions")))
            (when (and (empty? missing) (seq in-out))
              [(project/finding file "slice-unplanned" (str "the plan's Scope has no " (str/join " or " in-out) ": a scope says what is in and what is out"))])
            (when (heading? text "References")
              (if (empty? named)
                [(project/finding file "slice-unfounded" "the plan's References name no user need: a slice rests on the needs it serves")]
                (for [n named :when (not (contains? declared n))]
                  (project/finding file "slice-unfounded" (str "the plan's References name " n ", which is no declared user need")))))
            (when-not (some #{st} statuses)
              [(project/finding file "slice-status"
                                (str (if st (str "the status " st " is not a slice's") "the plan has no status line under its title")
                                     ": a slice's status is one of " (str/join ", " (map #(str "status: " %) statuses))))]))))))

(defn slice-findings
  "The slice rules of one spec under the slices layout."
  [root spec]
  (let [dir (:dir spec)
        slices (->> (fs/list-dir (fs/path root dir)) (filter fs/directory?) (map #(str (fs/file-name %)))
                    (filter parser/slice-folder?) sort)
        declared (set (map :id (:needs spec)))]
    (vec (mapcat
          (fn [s]
            (let [plan (fs/path root dir s "plan.md")
                  pf (str dir "/" s "/plan.md")
                  text (when (fs/exists? plan) (slurp (str plan)))
                  wip (filter #(and (= s (:slice %)) (:wip? %)) (:scenarios spec))]
              (concat
               (plan-findings pf text declared)
               (when-not (fs/exists? (fs/path root dir s "decisions.md"))
                 [(project/finding (str dir "/" s) "slice-undecided" "the slice keeps no decisions.md: every slice keeps what was decided in it")])
               (when (and (= "done" (plan-status text)) (seq wip))
                 (for [sc wip]
                   (project/finding pf "slice-done-planned" (str "the slice is done, and " (who sc) " (" (:file sc) ":" (:line sc) ") is planned work, tagged @wip")))))))
          slices))))

;; ------------------------------------------------------------------ the stage

(defn- ledger-issued [root spec]
  (let [f (fs/path root (:ledger spec))]
    (if-not (fs/exists? f)
      {:finding (project/finding (:ledger spec) "ledger-unreadable" "the ledger does not exist")}
      (try (let [m (edn/read-string (slurp (str f)))]
             (if (and (map? m) (integer? (:issued m)))
               {:issued (:issued m)}
               {:finding (project/finding (:ledger spec) 1 "ledger-unreadable" "the ledger is not {:issued N}")}))
           (catch Exception e {:finding (project/finding (:ledger spec) 1 "ledger-unreadable" (ex-message e))})))))

(defn spec-findings [root cfg spec]
  (let [{:keys [issued finding]} (ledger-issued root spec)
        declared (when-not (:needs-error spec) (set (map :id (:needs spec))))
        words (:stack-words cfg)]
    (vec (concat
          (when finding [finding])
          (when-let [e (:needs-error spec)] [(project/finding (:needs-file spec) (:line e) "needs-unreadable" (:message e))])
          (scenario-findings (:scenarios spec) {:prefix (:prefix spec) :issued issued :declared declared})
          (tag-placement-findings (:features spec))
          (need-findings (:needs spec) (:needs-file spec))
          (for [f (project/files-under root (:dir spec))
                :when (re-find #"\.(feature|md|edn|txt)$" f)
                finding (stack-findings f (slurp (str (fs/path root f))) words)]
            finding)
          (when (= :slices (:layout spec)) (slice-findings root spec))))))

(defn check
  "check's stage: the spec check of every spec; publishes SpecGated."
  [run]
  (let [root (:root run)
        specs (:specs (:reading run))
        findings (vec (mapcat #(spec-findings root (:cfg run) %) specs))]
    (bus/publish! :SpecGated :spec/check {:revision :working-tree :passed? (empty? findings)
                                          :findings (mapv #(select-keys % [:file :line :rule :message]) findings)})
    {:findings findings}))
