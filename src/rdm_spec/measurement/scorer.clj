(ns rdm-spec.measurement.scorer
  "The Scorer component (dhf/spec/measurement/scorer.feature): `rdm-spec score
  CYCLE --verdicts FILE` files a grader's verdicts on a ratified cycle into
  dhf/cycles/CYCLE/score.edn. The verdicts are the score; nothing else is.

  A verdicts file is a vector of {:id :verdict :pass|:fail :evidence \"..\"},
  one per item graded, no item twice, every id on the cycle's checklist.

  score.edn, its keys in this order (spec-0174):

    {:cycle :cut :scope :room-tree :builder :grader :spec-owner
     :items [{:id :verdict :evidence}]   ; the checklist's items, in its order
     :passed n :scored n}

  :cut and :scope are cycle.edn's (:scope :project when absent), :room-tree
  ratified.edn's, the roles cycle.edn's, each {:name :actor :human|:agent}.
  An item nobody graded is filed :fail, its evidence saying it was not
  graded: never dropped.

  Exit 2, writing nothing (it cannot run): no --verdicts, a verdicts file or
  cycle.edn or checklist.json that is missing or unreadable, and a verdicts
  file with no verdict in it, as the grading did not happen.
  Exit 1, writing nothing, every finding named: a role cycle.edn leaves
  unnamed or names wrongly (score-role-missing, score-role-actor), a builder
  who is also the grader or spec owner (score-builder-grades), a cycle with
  no passing ratification (score-not-ratified), a malformed verdicts file
  (verdicts-malformed), a verdict for an item not on the checklist
  (verdict-unknown-item).

  Result: {:score {:cycle :file :passed n :scored n}}."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Files of a cycle

(defn cycle-rel
  "dhf/cycles/<cycle>/<file>, under the configured cycles directory."
  [req cycle file]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle "/" file))

(defn- read-file
  "The text of `rel`, or nil when there is no such file."
  [req rel]
  (let [f (command/path req rel)]
    (when (fs/regular-file? f) (slurp (str f)))))

(defn- read-edn-or-cannot-run [rel text]
  (try (edn/read-string text)
       (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e))))))

;; ---------------------------------------------------------------------------
;; Pure checks

(def roles
  "The roles a cycle declares, in the order a score names them."
  [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]])

(defn role-findings
  "Pure: findings about the roles cycle.edn (`rel`) declares."
  [rel c]
  (let [missing (for [[k label] roles
                      :let [r (get c k)]
                      :when (not (and (map? r) (string? (:name r)) (not (str/blank? (:name r)))))]
                  (command/finding rel "score-role-missing"
                                   (str "the cycle names no " label " (" k " {:name N :actor :human|:agent}):"
                                        " every role is named")))
        actors (for [[k label] roles
                     :let [r (get c k)]
                     :when (and (map? r) (string? (:name r)) (not (#{:human :agent} (:actor r))))]
                 (command/finding rel "score-role-actor"
                                  (str "the " label " " (:name r) " is neither :human nor :agent but "
                                       (pr-str (:actor r)))))
        builder (get-in c [:builder :name])
        same (for [[k label] (rest roles)
                   :when (and (string? builder) (= builder (get-in c [k :name])))]
               (command/finding rel "score-builder-grades"
                                (str "the builder " builder " is also the cycle's " label
                                     ": the builder may not grade or read its own score")))]
    (vec (concat missing actors same))))

(defn verdict-findings
  "Pure: findings about the verdicts `vs` read from `rel`, against the
  checklist's item ids."
  [rel vs item-ids]
  (if-not (vector? vs)
    [(command/finding rel "verdicts-malformed"
                      (str "the verdicts file holds " (cond (map? vs) "a map" (nil? vs) "nothing" :else (pr-str (type vs)))
                           ", not a vector of {:id :verdict :evidence}"))]
    (let [on-list (set item-ids)
          shape (apply concat
                       (for [[i v] (map-indexed vector vs)
                             :let [where (str "the verdict " (inc i) (when (and (map? v) (:id v)) (str " (" (:id v) ")")))]]
                         (if-not (map? v)
                           [(command/finding rel "verdicts-malformed" (str where " is not a map {:id :verdict :evidence}"))]
                           (remove nil?
                                   [(when-not (and (string? (:id v)) (not (str/blank? (:id v))))
                                      (command/finding rel "verdicts-malformed" (str where " has no :id")))
                                    (when-not (#{:pass :fail} (:verdict v))
                                      (command/finding rel "verdicts-malformed"
                                                       (str where " gives the verdict " (pr-str (:verdict v))
                                                            ", not :pass or :fail")))
                                    (when-not (and (string? (:evidence v)) (not (str/blank? (:evidence v))))
                                      (command/finding rel "verdicts-malformed"
                                                       (str where " gives no evidence: a verdict states it")))
                                    (when-let [extra (seq (remove #{:id :verdict :evidence} (keys v)))]
                                      (command/finding rel "verdicts-malformed"
                                                       (str where " holds keys a verdict does not: " (str/join ", " extra))))]))))
          twice (for [[id n] (frequencies (keep #(when (map? %) (:id %)) vs))
                      :when (and (string? id) (> n 1))]
                  (command/finding rel "verdicts-malformed" (str "the verdicts grade " id " " n " times: twice is malformed")))
          unknown (for [v vs
                        :when (and (map? v) (string? (:id v)) (not (on-list (:id v))))]
                    (command/finding rel "verdict-unknown-item"
                                     (str (:id v) " is not on the cycle's checklist: a verdict for it is refused")))]
      (vec (concat shape (sort-by :message twice) unknown)))))

(def not-graded "not graded: an item nobody graded is a fail")

(defn score-of
  "Pure: score.edn's value, from cycle.edn `c`, ratified.edn `rat`, the
  checklist's item ids and the verdicts."
  [cycle c rat item-ids vs]
  (let [by-id (into {} (map (juxt :id identity)) vs)
        items (vec (for [id item-ids]
                     (if-let [v (by-id id)]
                       {:id id :verdict (:verdict v) :evidence (:evidence v)}
                       {:id id :verdict :fail :evidence not-graded})))]
    (array-map :cycle cycle
               :cut (:cut c)
               :scope (if (contains? c :scope) (:scope c) :project)
               :room-tree (:room-tree rat)
               :builder (:builder c)
               :grader (:grader c)
               :spec-owner (:spec-owner c)
               :items items
               :passed (count (filter #(= :pass (:verdict %)) items))
               :scored (count items))))

(defn score-text
  "Pure: score.edn's text, one key per line in the score's order."
  [score]
  (binding [*print-namespace-maps* false]
    (str "{"
         (str/join "\n "
                   (for [[k v] score]
                     (if (= :items k)
                       (str k " [" (str/join "\n         " (map pr-str v)) "]")
                       (str k " " (pr-str v)))))
         "}\n")))

;; ---------------------------------------------------------------------------
;; The stage

(defn score
  "The score stage: files the verdicts into dhf/cycles/CYCLE/score.edn."
  [req]
  (let [cycle (first (:args req))
        _ (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
            (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle's name, such as 03")))
        vrel (or (not-empty (get-in req [:opts :verdicts]))
                 (command/cannot-run! "score takes the grader's verdicts: rdm-spec score CYCLE --verdicts FILE"))
        vtext (or (read-file req vrel) (command/cannot-run! (str "no verdicts file " vrel)))
        vs (try (edn/read-string vtext)
                (catch Exception e ::unreadable))
        _ (when (or (nil? vs) (and (sequential? vs) (empty? vs)) (and (map? vs) (empty? vs)))
            (command/cannot-run! (str vrel " holds no verdict: the grading did not happen, so nothing is recorded")))
        crel (cycle-rel req cycle "cycle.edn")
        c (read-edn-or-cannot-run crel (or (read-file req crel) (command/cannot-run! (str "no " crel ": the cycle is not declared"))))
        _ (when-not (map? c) (command/cannot-run! (str crel " is not an EDN map")))
        lrel (cycle-rel req cycle "checklist.json")
        cl (try (json/parse-string (or (read-file req lrel)
                                       (command/cannot-run! (str "no " lrel ": derive it first (rdm-spec checklist " cycle ")")))
                                   true)
                (catch clojure.lang.ExceptionInfo e (throw e))
                (catch Exception e (command/cannot-run! (str lrel " is not readable JSON: " (ex-message e)))))
        item-ids (mapv :id (:items cl))
        rrel (cycle-rel req cycle "ratified.edn")
        rtext (read-file req rrel)
        rat (when rtext (try (edn/read-string rtext) (catch Exception _ nil)))
        ratified? (and (map? rat) (= :passed (:outcome rat)))
        findings (vec (concat
                       (role-findings crel c)
                       (when-not ratified?
                         [(command/finding rrel "score-not-ratified"
                                           (str (cond (nil? rtext) "there is no ratified.edn"
                                                      (not (map? rat)) "ratified.edn is not readable"
                                                      :else (str "the ratification's outcome is " (pr-str (:outcome rat))))
                                                ": the cycle has no passing ratification, and only a ratified build is scored"))])
                       (if (= ::unreadable vs)
                         [(command/finding vrel "verdicts-malformed" "the verdicts file is not readable EDN")]
                         (verdict-findings vrel vs item-ids))))]
    (if (seq findings)
      {:result {} :findings findings}
      (let [sc (score-of cycle c rat item-ids vs)
            srel (cycle-rel req cycle "score.edn")]
        (spit (str (command/path req srel)) (score-text sc))
        {:result {:score {:cycle cycle :file srel :passed (:passed sc) :scored (:scored sc)}}
         :findings []}))))
