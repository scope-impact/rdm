(ns rdm-spec.measurement.scorer
  "The scorer (dhf/spec/measurement/scorer.feature): files graders' verdicts
  on a verified cycle into its score.edn. Several verdicts files are scored as
  one, held to the same rules as one. It refuses to record what it did not
  measure: no verdict at all is a grading that did not happen; an item nobody
  graded is a fail; a verdict off the checklist, a malformed file, a cycle
  whose builder grades or reads its own score, or one with no passing
  verification is refused, and nothing is written."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [edamame.core :as e]
            [rdm-spec.kernel.command :as cmd]))

(def roles [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]])

(defn verdict-findings
  "The findings about the verdicts files [[file value] ...] against the checklist's ids."
  [files ids]
  (let [entries (for [[f v] files :when (vector? v) e v] [f e])
        uses (group-by (comp :id second) (filter (comp map? second) entries))]
    (vec
     (concat
      (for [[f v] files :when (not (vector? v))]
        (cmd/finding f nil "verdicts-malformed"
                     (str "the verdicts are " (cond (map? v) "a map" (nil? v) "empty" :else (str "a " (.getSimpleName (class v))))
                          ", not a vector of {:id :verdict :evidence}")))
      (for [[f e] entries
            :let [id (when (map? e) (:id e))
                  why (cond (not (map? e)) (str (pr-str e) " is not a map of :id, :verdict and :evidence")
                            (not (string? id)) (str (pr-str e) " names no :id")
                            (not (#{:pass :fail} (:verdict e))) (str id "'s verdict " (pr-str (:verdict e)) " is neither :pass nor :fail")
                            (not (and (string? (:evidence e)) (not (str/blank? (:evidence e))))) (str id " gives no evidence: a verdict carries its evidence"))]
            :when why]
        (cmd/finding f nil "verdict-malformed" why))
      (for [[id es] uses :when (> (count es) 1)]
        (cmd/finding (first (first es)) nil "verdict-twice"
                     (str id " is graded twice, in " (str/join " and " (distinct (map first es))) ": an item is graded once")))
      (for [[id es] uses :when (and (string? id) (not (ids id)))]
        (cmd/finding (first (first es)) nil "verdict-off-checklist" (str id " is not on the cycle's checklist")))))))

(defn items
  "Every checklist item's verdict, an ungraded one a fail."
  [ids verdicts]
  (let [by-id (into {} (map (juxt :id identity) verdicts))]
    (vec (for [id ids]
           (if-let [v (by-id id)]
             (select-keys v [:id :verdict :evidence])
             {:id id :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true})))))

(defn- read-verdicts
  "[file value] of a verdicts file, or a finding where it stops being EDN."
  [root f]
  (let [p (fs/path root f)]
    (when-not (fs/regular-file? p) (cmd/cannot-run! (str "the verdicts file " f " does not exist")))
    (try
      (let [forms (e/parse-string-all (slurp (fs/file p)))]
        (if (= 1 (count forms))
          [f (first forms)]
          {:finding (cmd/finding f nil "verdicts-unreadable" (str f " holds " (count forms) " forms, not one vector"))}))
      (catch clojure.lang.ExceptionInfo ex
        {:finding (cmd/finding f (let [d (ex-data ex)] (or (:row d) (:line d) (some-> (re-find #"line (\d+)" (str (ex-message ex))) second parse-long))) "verdicts-unreadable"
                               (str f " is not readable EDN: " (ex-message ex)))}))))

(defn stage [{:keys [root config args opts]}]
  (let [id (first args)
        dir (str (:cycles-dir config "dhf/cycles") "/" id)
        read (fn [f] (try (cmd/read-edn (cmd/read-at root nil (str dir "/" f))) (catch Exception _ ::unreadable)))
        cycle (read "cycle.edn")
        _ (when-not (map? cycle) (cmd/cannot-run! (str "there is no readable " dir "/cycle.edn")))
        _ (when (empty? (:verdicts opts)) (cmd/cannot-run! "usage: rdm-spec score CYCLE --verdicts FILE [--verdicts FILE ...]"))
        checklist (or (try (json/parse-string (cmd/read-at root nil (str dir "/checklist.json"))) (catch Exception _ nil))
                      (cmd/cannot-run! (str "there is no readable " dir "/checklist.json: derive it with rdm-spec checklist " id)))
        ids (mapv #(get % "id") (get checklist "items"))
        ratified (read "ratified.edn")
        read-files (mapv #(read-verdicts root %) (:verdicts opts))
        unreadable (keep :finding read-files)
        files (remove :finding read-files)
        role-findings (concat
                       (for [[k n] roles :when (not (and (map? (k cycle)) (string? (:name (k cycle)))))]
                         (cmd/finding (str dir "/cycle.edn") nil "role-missing"
                                      (str "the cycle names no " n " (" k "): a cycle names who built, graded and reads it")))
                       (let [b (get-in cycle [:builder :name])]
                         (for [[k n] (rest roles) :when (and b (= b (get-in cycle [k :name])))]
                           (cmd/finding (str dir "/cycle.edn") nil "builder-grades"
                                        (str b " is the cycle's builder and its " n ": the builder may not grade or read its own score")))))
        verified (when-not (and (map? ratified) (= :passed (:outcome ratified)))
                   [(cmd/finding (str dir "/ratified.edn") nil "cycle-unverified"
                                 (str "the cycle has no passing verification: " dir "/ratified.edn "
                                      (if (map? ratified) (str "holds :outcome " (:outcome ratified)) "does not exist")
                                      "; only a verified build is scored"))])
        findings (vec (concat unreadable role-findings verified (verdict-findings files (set ids))))
        verdicts (for [[_ v] files :when (vector? v) e v] e)]
    (cond
      (seq findings) {:findings findings}
      (empty? verdicts)
      (cmd/cannot-run! "no verdict was given: the grading did not happen, so nothing is recorded")
      :else
      (let [its (items ids verdicts)
            passed (count (filter #(= :pass (:verdict %)) its))
            score (array-map :cycle id :cut (:cut cycle) :scope (:scope cycle :project) :room-tree (:room-tree ratified)
                             :builder (:builder cycle) :grader (:grader cycle) :spec-owner (:spec-owner cycle)
                             :items its :passed passed :scored (count its))
            f (str dir "/score.edn")]
        (spit (fs/file root f) (binding [*print-namespace-maps* false] (with-out-str (pp/pprint score))))
        {:result {:score {:cycle id :file f :passed passed :scored (count its)
                          :ungraded (count (filter :ungraded its))}}}))))
