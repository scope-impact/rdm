(ns rdm-spec.measurement.scorer
  "The Scorer component (dhf/spec/measurement/scorer.feature): `rdm-spec
  score CYCLE --verdicts FILE` files a grader's verdicts against the
  cycle's checklist into dhf/cycles/CYCLE/score.edn, and nothing when
  nothing was graded. Scoring is independent of the builder's tests: only
  the verdicts are the score.

  A verdicts file is a vector of {:id :verdict :pass|:fail :evidence text}
  (spec-0212). score.edn, its keys in this order (spec-0174):

    {:cycle :cut :scope :room-tree :builder :grader :spec-owner
     :items [{:id :verdict :evidence}] :passed n :scored n}

  :items in the checklist's order; an item nobody graded is filed as
  {:id ID :verdict :fail :evidence \"ungraded: no verdict was given\"
  :ungraded true} (spec-0023). :scope is cycle.edn's (:project when
  absent), :room-tree ratified.edn's.

  Refusals, writing nothing. Cannot run (exit 2): no cycle.edn or one that
  is not a map, no --verdicts or a verdicts file that does not exist, no
  checklist.json, and a verdicts file with no verdict in it, since the
  grading did not happen (spec-0022). Findings (exit 1), every one in one
  run: a role cycle.edn leaves unnamed or names badly (score-role), a
  builder who also grades or reads the score (score-builder-independence),
  a cycle with no passing ratification (score-not-ratified), a malformed
  verdicts file (verdicts-not-a-vector, verdicts-entry, verdicts-id,
  verdicts-duplicate, verdicts-verdict, verdicts-evidence), a verdict for
  an item not on the checklist (score-unknown-item).

  Result: {:score {:cycle :file :passed :scored :ungraded}}."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def ungraded-evidence "ungraded: no verdict was given")

(def roles
  "Every role a cycle names, and what it is called."
  [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]])

;; ---------------------------------------------------------------------------
;; Pure checks

(defn- role? [r]
  (and (map? r) (string? (:name r)) (not (str/blank? (:name r))) (#{:human :agent} (:actor r))))

(defn role-findings
  "Pure: the findings about the roles cycle.edn (at `rel`) names."
  [rel c]
  (let [missing (for [[k label] roles
                      :when (not (role? (get c k)))]
                  (command/finding rel "score-role"
                                   (if (contains? c k)
                                     (str "the " label " " k " is " (pr-str (get c k))
                                          ", not {:name NAME :actor :human|:agent}")
                                     (str "the cycle names no " k " (its " label
                                          "): every role is named, as human or agent"))))
        builder (get-in c [:builder :name])
        same (when (role? (:builder c))
               (for [[k label] (rest roles)
                     :when (= builder (get-in c [k :name]))]
                 (command/finding rel "score-builder-independence"
                                  (str builder " is both the builder and the " label
                                       " of the cycle: the builder may not grade or read its own score"))))]
    (vec (concat missing same))))

(defn ratification-findings
  "Pure: a finding unless ratified.edn (at `rel`, nil when absent) passed."
  [rel ratified]
  (if (= :passed (:outcome ratified))
    []
    [(command/finding rel "score-not-ratified"
                      (str "the cycle has no passing ratification ("
                           (if (nil? ratified) "no ratified.edn" (str ":outcome " (pr-str (:outcome ratified))))
                           "): only a ratified build is scored"))]))

(defn verdict-findings
  "Pure: the findings about the verdicts file `verdicts` (at `rel`), each
  broken rule its own finding."
  [rel verdicts]
  (if-not (vector? verdicts)
    [(command/finding rel "verdicts-not-a-vector"
                      (str "the verdicts file holds " (if (map? verdicts) "a map" (pr-str (type verdicts)))
                           ", not a vector of {:id :verdict :evidence}"))]
    (let [counts (frequencies (keep #(when (map? %) (:id %)) verdicts))]
      (vec
       (concat
        (for [[i v] (map-indexed vector verdicts)
              f (if-not (map? v)
                  [(command/finding rel "verdicts-entry"
                                    (str "verdict " (inc i) " is " (pr-str v) ", not a map of :id :verdict :evidence"))]
                  (let [{:keys [id verdict evidence]} v
                        who (if (string? id) id (str "verdict " (inc i)))]
                    (concat
                     (when-not (and (string? id) (not (str/blank? id)))
                       [(command/finding rel "verdicts-id" (str "verdict " (inc i) " has the id " (pr-str id)
                                                                ", not a checklist item's id"))])
                     (when-not (#{:pass :fail} verdict)
                       [(command/finding rel "verdicts-verdict"
                                         (str who " has the verdict " (pr-str verdict) ", not :pass or :fail"))])
                     (when-not (and (string? evidence) (not (str/blank? evidence)))
                       [(command/finding rel "verdicts-evidence"
                                         (str who " gives no evidence: every verdict says what it rests on"))]))))]
          f)
        (for [[id n] (sort-by key counts)
              :when (and (string? id) (> n 1))]
          (command/finding rel "verdicts-duplicate"
                           (str id " is graded twice" (when (> n 2) (str " (" n " times)"))
                                ": one verdict per item"))))))))

(defn unknown-findings
  "Pure: a finding for each verdict whose id is not on the checklist (`ids`)."
  [rel ids verdicts]
  (let [known (set ids)]
    (vec (for [id (distinct (map :id verdicts))
               :when (not (known id))]
           (command/finding rel "score-unknown-item"
                            (str id " is graded but is not on the cycle's checklist"))))))

(defn score-items
  "Pure: the items filed, in the checklist's order (`ids`): each item's
  verdict, or a fail marked ungraded."
  [ids verdicts]
  (let [by-id (into {} (map (juxt :id identity)) verdicts)]
    (mapv (fn [id]
            (if-let [v (by-id id)]
              (array-map :id id :verdict (:verdict v) :evidence (:evidence v))
              (array-map :id id :verdict :fail :evidence ungraded-evidence :ungraded true)))
          ids)))

(defn score-of
  "Pure: the score of cycle `cycle` (its name), from its cycle.edn, its
  ratified.edn and the items filed."
  [cycle c ratified items]
  (array-map :cycle cycle
             :cut (:cut c)
             :scope (if (some? (:scope c)) (:scope c) :project)
             :room-tree (:room-tree ratified)
             :builder (:builder c)
             :grader (:grader c)
             :spec-owner (:spec-owner c)
             :items (vec items)
             :passed (count (filter #(= :pass (:verdict %)) items))
             :scored (count items)))

(defn score-text
  "Pure: score.edn's text: one key per line, one item per line."
  [sc]
  (binding [*print-namespace-maps* false]
    (let [pad (apply str (repeat (count " :items [") " "))
          lines (for [[k v] sc]
                  (if (= :items k)
                    (str ":items [" (str/join (str "\n" pad) (map pr-str v)) "]")
                    (str (pr-str k) " " (pr-str v))))]
      (str "{" (str/join "\n " lines) "}\n"))))

;; ---------------------------------------------------------------------------
;; Reading the cycle

(defn- cycle-rel [req cycle file]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle "/" file))

(defn- read-edn-file
  "The EDN at `rel`, nil when there is no such file; cannot run when it is
  unreadable."
  [req rel]
  (let [f (command/path req rel)]
    (when (fs/regular-file? f)
      (try (edn/read-string (slurp (str f)))
           (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e))))))))

(defn- read-checklist-ids [req rel]
  (let [f (command/path req rel)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " rel ": the cycle's checklist is derived (rdm-spec checklist) before it is scored")))
    (let [cl (try (json/parse-string (slurp (str f)) true)
                  (catch Exception e (command/cannot-run! (str rel " is not readable JSON: " (ex-message e)))))]
      (mapv :id (:items cl)))))

(defn- read-verdicts
  "[verdicts rel]; a verdicts file that is not EDN is malformed (a finding)."
  [req]
  (let [rel (or (not-empty (:verdicts (:opts req)))
                (command/cannot-run! "score takes --verdicts FILE: rdm-spec score CYCLE --verdicts FILE"))
        f (command/path req rel)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "the verdicts file " rel " does not exist")))
    (let [text (slurp (str f))]
      (try [(edn/read-string text) rel nil]
           (catch Exception e
             [nil rel (command/finding rel "verdicts-not-edn" (str "the verdicts file is not EDN: " (ex-message e)))])))))

;; ---------------------------------------------------------------------------
;; The stage

(defn score
  "The score stage: files the verdicts into dhf/cycles/CYCLE/score.edn."
  [req]
  (let [cycle (first (:args req))
        _ (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
            (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle's name, such as 03")))
        crel (cycle-rel req cycle "cycle.edn")
        c (or (read-edn-file req crel)
              (command/cannot-run! (str "no " crel ": a cycle is declared by hand in its cycle.edn")))
        _ (when-not (map? c) (command/cannot-run! (str crel " is not an EDN map")))
        [verdicts vrel unreadable] (read-verdicts req)
        _ (when (and (nil? unreadable) (or (nil? verdicts) (and (vector? verdicts) (empty? verdicts))))
            (command/cannot-run! (str "the verdicts file " vrel " holds no verdict: the grading did not happen,"
                                      " so nothing is recorded")))
        ids (read-checklist-ids req (cycle-rel req cycle "checklist.json"))
        rrel (cycle-rel req cycle "ratified.edn")
        ratified (read-edn-file req rrel)
        malformed (if unreadable [unreadable] (verdict-findings vrel verdicts))
        findings (vec (concat (role-findings crel c)
                              (ratification-findings rrel (when (map? ratified) ratified))
                              malformed
                              (when (empty? malformed) (unknown-findings vrel ids verdicts))))]
    (if (seq findings)
      {:result {} :findings findings}
      (let [items (score-items ids verdicts)
            sc (score-of cycle c ratified items)
            rel (cycle-rel req cycle "score.edn")
            f (command/path req rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (score-text sc))
        {:result {:score {:cycle cycle :file rel :passed (:passed sc) :scored (:scored sc)
                          :ungraded (count (filter :ungraded items))}}
         :findings []}))))
