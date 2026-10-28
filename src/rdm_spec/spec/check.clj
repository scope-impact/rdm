(ns rdm-spec.spec.check
  "Component Spec check (spec context): the rules a spec keeps. One id and
  one level per scenario, only known tags in their places, a declared need
  for every scenario resting on a source, planned work marked, no stack word,
  and, under the slices layout, every slice planned. Publishes SpecGated."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.spec.stamper :as stamper]))

(defn allowed-tags [spec]
  (str "a scenario may carry its id (@" (:id-prefix spec) "-NNNN), one level (@e2e or @contract), need tags (@UN-nnn) and @wip"))

(defn tag-kind [spec t]
  (cond (re-find (parser/id-re (:id-prefix spec)) t) :id
        (parser/levels t) :level
        (parser/need-tag? t) :need
        (= "@wip" t) :wip
        :else :unknown))

(defn- who [sc] (or (:id sc) (str "the scenario \"" (:title sc) "\"")))

(defn scenario-findings [spec issued declared sc]
  (let [f (fn [rule msg] (project/finding (:file sc) (:line sc) rule msg))
        ids (:ids sc)]
    (concat
     (case (count ids)
       0 [(f "scenario-unstamped" (str "the scenario \"" (:title sc) "\" carries no id: run \"rdm-spec stamp\""))]
       1 []
       [(f "scenario-ids" (str "the scenario \"" (:title sc) "\" carries " (count ids) " ids, " (str/join " and " ids)
                               ": a scenario carries exactly one"))])
     (when issued
       (for [id ids :let [n (parse-long (re-find #"\d+$" id))] :when (> n issued)]
         (f "id-unissued" (str id ": the ledger never issued it; it has issued up to " issued ": run \"rdm-spec stamp\""))))
     (case (count (:levels sc))
       0 [(f "level-missing" (str (who sc) " carries no level: a scenario carries exactly one of @e2e and @contract"))]
       1 []
       [(f "level-twice" (str (who sc) " carries both levels " (str/join " and " (map #(str "@" %) (:levels sc)))
                              ": a scenario carries exactly one of @e2e and @contract"))])
     (for [t (:tags sc) :when (= :unknown (tag-kind spec t))]
       (f "tag-unknown" (str (who sc) " carries " t ", which is not a tag the spec knows: " (allowed-tags spec))))
     (when (empty? (:needs sc))
       [(f "need-missing" (str (who sc) " traces to no user need: tag it, its rule or its feature with one (@UN-nnn)"))])
     (for [n (:needs sc) :when (not (declared n))]
       (f "need-unknown" (str (who sc) " is tagged @" n ", a need " (:needs spec) " does not declare"))))))

(defn placement-findings [spec feature]
  (let [ft (get-in feature [:feature :tags])
        rules (distinct (keep :rule (:scenarios feature)))]
    (concat
     (for [t ft :let [k (tag-kind spec t)] :when (#{:id :level :wip :unknown} k)]
       (project/finding (:file feature) (get-in feature [:feature :line]) (if (= k :unknown) "tag-unknown" "tag-misplaced")
                        (str "the feature carries " t ": a feature carries only need tags (@UN-nnn)")))
     (for [r rules t (:tags r) :let [k (tag-kind spec t)] :when (#{:id :level :wip :unknown} k)]
       (project/finding (:file feature) (:line r) (if (= k :unknown) "tag-unknown" "tag-misplaced")
                        (str "the rule \"" (:name r) "\" carries " t ": a rule carries only need tags (@UN-nnn)"))))))

(defn line-of [text s]
  (when text
    (some (fn [[i l]] (when (str/includes? l s) (inc i))) (map-indexed vector (str/split-lines text)))))

(defn need-findings [tree spec needs]
  (let [text (project/read-text tree (:needs spec))]
    (for [{:keys [id sources]} needs
          :when (not (and (sequential? sources) (seq sources)))]
      (project/finding (:needs spec) (or (line-of text (pr-str id)) 1) "need-unsourced"
                       (str id " cites no source: every user need rests on a source, cited as <source>:<path>@<commit>")))))

(defn duplicate-findings [scenarios]
  (for [[id scs] (group-by identity (mapcat :ids scenarios))
        :let [where (for [sc scenarios :when (some #{id} (:ids sc))] sc)]
        :when (> (count where) 1)
        :let [sc (first where)]]
    (project/finding (:file sc) (:line sc) "id-duplicate"
                     (str id " is carried by " (count where) " scenarios: "
                          (str/join " and " (map #(str (:file %) ":" (:line %)) where))))))

(defn- sections
  "A Markdown text's headings, each with the text under it until the next heading of its level or above."
  [text]
  (let [lines (str/split-lines text)
        hs (keep-indexed (fn [i l] (when-let [[_ h t] (re-find #"^(#+)\s+(.+?)\s*#*\s*$" l)] [i (count h) (str/trim t)])) lines)]
    (into {} (for [[i lvl t] hs
                   :let [end (or (some (fn [[j l2 _]] (when (and (> j i) (<= l2 lvl)) j)) hs) (count lines))]]
               [(str/lower-case t) (str/join "\n" (subvec (vec lines) (inc i) end))]))))

(defn slice-findings
  "Under the slices layout: every slice planned, its plan resting on declared needs."
  [tree cfg spec declared]
  (when (project/slices? cfg)
    (let [d (project/abs tree (:dir spec))]
      (when (fs/directory? d)
        (for [s (sort (map str (fs/list-dir d)))
              :when (and (fs/directory? s) (re-matches project/slice-re (str (fs/file-name s))))
              :let [plan (str (:dir spec) "/" (fs/file-name s) "/plan.md")
                    text (project/read-text tree plan)
                    secs (when text (sections text))
                    refs (get secs "references")
                    scope (get secs "scope")
                    needs (when refs (re-seq #"UN-\d+" refs))
                    has-in-out? (fn [label] (and scope (re-find (re-pattern (str "(?m)^\\s*(#+\\s*|\\*\\*|__)?" label "\\b")) scope)))]
              f (cond
                  (nil? text) [(project/finding plan "slice-unplanned" (str "the slice " (fs/file-name s) " holds no plan.md: a slice is planned"))]
                  :else
                  (concat
                   (for [h ["goal" "references" "scope" "decisions"] :when (not (contains? secs h))]
                     (project/finding plan "slice-unplanned" (str "the plan has no \"" (str/capitalize h) "\" heading: a slice's plan says its goal, references, scope and decisions")))
                   (when (and scope (not (and (has-in-out? "In") (has-in-out? "Out"))))
                     [(project/finding plan "slice-unplanned" "the plan's Scope says no \"In\" and \"Out\"")])
                   (when (and refs (empty? needs))
                     [(project/finding plan "slice-unfounded" "the plan's References name no user need: a slice rests on what a user needs")])
                   (for [n (distinct needs) :when (not (declared n))]
                     (project/finding plan "slice-unfounded" (str "the plan's References name " n ", which no need is")))))]
          f)))))

(defn check-spec [tree cfg spec read]
  (let [{:keys [issued finding]} (stamper/read-ledger tree spec)
        declared (set (map :id (:needs read)))]
    (vec (concat
          (when finding [finding])
          (:needs-findings read)
          (mapcat #(placement-findings spec %) (:features read))
          (mapcat #(scenario-findings spec issued declared %) (:scenarios read))
          (duplicate-findings (:scenarios read))
          (need-findings tree spec (:needs read))
          (slice-findings tree cfg spec declared)
          (project/stack-word-findings tree (:dir spec) (:stack-words cfg))))))

(defn check-stage
  "The spec check over every spec; publishes SpecGated."
  [{:keys [cfg]}]
  (let [tree (project/root)
        read (parser/read-specs-once tree cfg)
        findings (vec (mapcat #(check-spec tree cfg (:spec %) %) read))
        refusals (mapcat :refusals read)]
    (bus/publish! :spec/check :SpecGated {:revision :working-tree
                                          :passed? (and (empty? findings) (empty? refusals))
                                          :findings (vec (concat refusals findings))})
    {:result {:spec {:passed? (and (empty? findings) (empty? refusals))}}
     :findings findings
     :warnings []}))
