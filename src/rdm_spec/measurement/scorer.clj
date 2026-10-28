(ns rdm-spec.measurement.scorer
  "Files a grader's verdicts against the checklist, and nothing when nothing
  was graded; only a verified build, and never by its builder
  (dhf/spec/07-measure/scorer.feature, 13-slice-development/scorer.feature)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.measurement.checklist :as checklist]))

(defn scope-problem [scope] (checklist/scope-problem scope))

(defn verdicts-problem
  "What is malformed about one file's verdicts, or nil."
  [vs]
  (cond
    (not (vector? vs)) "the verdicts are not a vector of {:id :verdict :evidence}"
    :else
    (or (first (for [v vs :when (not (map? v))] (str "a verdict is not a map: " (pr-str v))))
        (first (for [v vs :when (not (string? (:id v)))] (str "a verdict names no :id: " (pr-str v))))
        (first (for [v vs :when (not (#{:pass :fail} (:verdict v)))] (str (:id v) " is given the verdict " (pr-str (:verdict v)) ": a verdict is :pass or :fail")))
        (first (for [v vs :when (str/blank? (str (:evidence v)))] (str (:id v) " is given no evidence: every verdict carries its evidence")))
        (first (for [[id n] (frequencies (map :id vs)) :when (> n 1)] (str id " is graded twice"))))))

(defn roles-problem [cyc]
  (let [missing (for [[k n] [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]] :when (not (get-in cyc [k :name]))] n)
        b (get-in cyc [:builder :name])]
    (cond (seq missing) (str "the cycle leaves the role" (when (> (count missing) 1) "s") " " (str/join " and " missing) " unnamed: every role is named")
          (or (= b (get-in cyc [:grader :name])) (= b (get-in cyc [:spec-owner :name])))
          (str b " is the cycle's builder and its " (if (= b (get-in cyc [:grader :name])) "grader" "spec owner")
               ": the builder may not grade or read its own score"))))

(defn ratified-problem [r]
  (when-not (= :passed (:outcome r))
    (str "the cycle has no passing verification: " (if r (str "ratified.edn holds :outcome " (pr-str (:outcome r))) "there is no ratified.edn"))))

(defn score-items [ids by-id]
  (vec (for [id ids]
         (or (some-> (by-id id) (select-keys [:id :verdict :evidence]))
             {:id id :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}))))

(defn tally [items] {:passed (count (filter #(= :pass (:verdict %)) items)) :scored (count items)})

(defn- read-verdicts [root file]
  (let [p (fs/path root file)]
    (if-not (fs/exists? p)
      (project/cannot-run! (str "the verdicts file " file " does not exist"))
      (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (slurp (str p))))]
        (try {:verdicts (edn/read rdr)}
             (catch Exception e
               {:finding (project/finding file (max 1 (.getLineNumber rdr)) "verdicts-unreadable"
                                          (str file " is not readable EDN: " (ex-message e)))}))))))

(defn score [run]
  (let [root (:root run) cfg (:cfg run)
        cycle (first (:args run))
        files (get-in run [:opts "--verdicts"])
        _ (when (empty? files) (project/cannot-run! "score needs --verdicts FILE: no verdict was given, so the grading did not happen"))
        dir (str (:cycles-dir cfg) "/" cycle)
        cyc (checklist/read-cycle root cfg cycle)
        refuse (fn [file rule msg] {:findings [(project/finding file rule msg)] :result {:score {:cycle cycle :written false}}})]
    (if-let [p (roles-problem cyc)]
      (refuse (str dir "/cycle.edn") "cycle-roles" p)
      (let [rf (fs/path root dir "ratified.edn")
            ratified (when (fs/exists? rf) (try (edn/read-string (slurp (str rf))) (catch Exception _ nil)))]
        (if-let [p (ratified-problem ratified)]
          (refuse (str dir "/ratified.edn") "score-unverified" p)
          (let [cl (fs/path root dir "checklist.json")
                _ (when-not (fs/exists? cl) (project/cannot-run! (str "no " dir "/checklist.json: run rdm-spec checklist " cycle " first")))
                items (get (json/parse-string (slurp (str cl))) "items")
                read (for [f files] (assoc (read-verdicts root f) :file f))
                bad (concat (keep :finding read)
                            (for [r read :when (not (:finding r)) :let [p (verdicts-problem (:verdicts r))] :when p]
                              (project/finding (:file r) "verdicts-malformed" p)))]
            (if (seq bad)
              {:findings (vec bad) :result {:score {:cycle cycle :written false}}}
              (let [all (for [r read v (:verdicts r)] (assoc v :file (:file r)))
                    ids (map #(get % "id") items)
                    known (set ids)]
                (when (empty? all) (project/cannot-run! "the verdicts hold no verdict: the grading did not happen, so nothing is recorded"))
                (let [dups (for [[id vs] (group-by :id all) :when (> (count vs) 1)]
                             (project/finding (:file (second vs)) "verdict-twice" (str id " is graded twice, in " (str/join " and " (distinct (map :file vs))))))
                      unknown (for [v all :when (not (known (:id v)))]
                                (project/finding (:file v) "verdict-unknown" (str (:id v) " is not on cycle " cycle "'s checklist")))]
                  (if (seq (concat dups unknown))
                    {:findings (vec (concat dups unknown)) :result {:score {:cycle cycle :written false}}}
                    (let [scored (score-items ids (into {} (map (juxt :id identity) all)))
                          roles (into {} (map (fn [i] [(get i "id") (get i "role")]) items))
                          slice (or (:slice cyc) (some #(when (map? %) (:slice %)) (when (sequential? (:scope cyc)) (:scope cyc))))
                          roled? (some some? (vals roles))
                          t (tally scored)
                          s (array-map :cycle cycle :cut (:cut cyc) :scope (:scope cyc :project) :room-tree (:room-tree ratified)
                                       :builder (:builder cyc) :grader (:grader cyc) :spec-owner (:spec-owner cyc)
                                       :items scored :passed (:passed t) :scored (:scored t))
                          s (if roled?
                              (assoc s :slice (merge {:name slice} (tally (filter #(= "slice" (roles (:id %))) scored)))
                                       :regression (tally (filter #(= "regression" (roles (:id %))) scored)))
                              s)]
                      (spit (str (fs/path root dir "score.edn")) (project/pprint-str s))
                      {:result {:score {:cycle cycle :passed (:passed t) :scored (:scored t)
                                        :ungraded (count (filter :ungraded scored)) :file (str dir "/score.edn")}}})))))))))))
