(ns rdm-spec.measurement.scorer
  "Scorer (dhf/spec/measurement/scorer.feature): `rdm-spec score CYCLE
  --verdicts FILE` files a grader's verdicts against the cycle's checklist
  into dhf/cycles/CYCLE/score.edn, and nothing when nothing was graded.

  Reads, all beside the cycle: cycle.edn (written by hand: :cut, :scope,
  :builder, :grader, :spec-owner, each role {:name :actor :human|:agent}),
  ratified.edn (ratify's record; only :outcome :passed is scored) and
  checklist.json (the checklist task's). The verdicts file is a vector of
  {:id :verdict :pass|:fail :evidence \"non-blank\"}, one per graded item.

  An item nobody graded is scored as a fail, with the evidence \"not graded\".

  score.edn, keys in this order:
    {:cycle :cut :scope :room-tree :builder :grader :spec-owner
     :items [{:id :verdict :evidence}] (in checklist order)
     :passed n :scored n}

  Refusals, after which nothing is written:
    exit 2: no cycle.edn or no verdicts file, either unreadable; a verdicts
            file with no verdict in it (the grading did not happen); no
            checklist.json.
    exit 1: a role left unnamed or malformed; a builder who also grades or
            reads the score; no passing ratification; a malformed verdicts
            file (not a vector, an entry not a map, an id graded twice, a
            verdict other than :pass or :fail, no evidence); a verdict for an
            item not on the checklist."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def roles
  "The roles a cycle names, in order, with how findings name them."
  [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]])

(def actors #{:human :agent})

;; ---------------------------------------------------------------------------
;; Reading

(defn- cycle-dir [req cycle]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (not (str/includes? cycle "..")))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle name (a directory name such as 03)")))
  (fs/file (command/path req (command/config-value req :cycles-dir)) cycle))

(defn- read-edn-file
  "The EDN value in `f`, ::missing when there is no such file, or ::unreadable."
  [f]
  (if-not (fs/regular-file? f)
    ::missing
    (try (edn/read-string (slurp (str f)))
         (catch Exception _ ::unreadable))))

(defn- read-cycle [req dir]
  (let [f (fs/file dir "cycle.edn")
        rel (command/rel req f)
        c (read-edn-file f)]
    (cond
      (= ::missing c) (command/cannot-run! (str "no " rel ": the cycle is declared there, by hand"))
      (= ::unreadable c) (command/cannot-run! (str rel " is not readable EDN"))
      (not (map? c)) (command/cannot-run! (str rel " is not a map"))
      (not (and (string? (:cut c)) (not (str/blank? (:cut c))))) (command/cannot-run! (str rel " names no :cut"))
      :else c)))

(defn- read-checklist [req dir]
  (let [f (fs/file dir "checklist.json")
        rel (command/rel req f)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " rel ": derive it first (rdm-spec checklist " (fs/file-name dir) ")")))
    (let [c (try (json/parse-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str rel " is not JSON: " (ex-message e)))))]
      (when-not (and (map? c) (sequential? (get c "items"))
                     (every? #(and (map? %) (string? (get % "id"))) (get c "items")))
        (command/cannot-run! (str rel " is not a checklist: it holds no list of items with ids")))
      c)))

;; ---------------------------------------------------------------------------
;; Checks

(defn role-findings
  "Findings on the cycle's roles: each named, as human or agent, and the
  builder neither its grader nor its spec owner."
  [cycle-file c]
  (let [missing (for [[k label] roles
                      :let [r (get c k)]
                      :when (not (and (map? r) (string? (:name r)) (not (str/blank? (:name r)))))]
                  (command/finding cycle-file nil "role-unnamed"
                                   (str "the cycle names no " label " (" k " {:name .. :actor :human|:agent})")))
        bad-actor (for [[k label] roles
                        :let [r (get c k)]
                        :when (and (map? r) (string? (:name r)) (not (actors (:actor r))))]
                    (command/finding cycle-file nil "role-actor"
                                     (str "the " label " " (:name r) " is neither :human nor :agent (" k " :actor)")))
        builder (get-in c [:builder :name])
        self (for [[k label] (rest roles)
                   :when (and (string? builder) (not (str/blank? builder)) (= builder (get-in c [k :name])))]
               (command/finding cycle-file nil "builder-not-independent"
                                (str "the builder " builder " is also the cycle's " label
                                     ": the builder may not grade or read its own score")))]
    (vec (concat missing bad-actor self))))

(defn ratification-findings
  "A finding unless `ratified` (ratified.edn's value, or ::missing) passed."
  [ratified-file ratified]
  (cond
    (= ::missing ratified)
    [(command/finding ratified-file nil "not-ratified"
                      "the cycle has no passing ratification: there is no ratified.edn (rdm-spec ratify ROOM CYCLE)")]
    (not (and (map? ratified) (= :passed (:outcome ratified))))
    [(command/finding ratified-file nil "not-ratified"
                      (str "the cycle has no passing ratification: ratified.edn holds :outcome "
                           (pr-str (when (map? ratified) (:outcome ratified)))))]
    :else []))

(defn verdict-findings
  "Findings on a verdicts value (already known to hold at least one entry):
  its shape, and every id on the checklist (`ids`, a set)."
  [verdicts-file verdicts ids]
  (if-not (vector? verdicts)
    [(command/finding verdicts-file nil "verdicts-malformed"
                      (str "the verdicts file is " (if (map? verdicts) "a map" (pr-str (type verdicts)))
                           ", not a vector of {:id :verdict :evidence}"))]
    (let [counts (frequencies (keep #(when (map? %) (:id %)) verdicts))]
      (vec
       (concat
        (for [[i v] (map-indexed vector verdicts)
              :let [where (str "verdict " (inc i) (when (and (map? v) (:id v)) (str " (" (:id v) ")")))]
              msg (if-not (map? v)
                    [(str where " is not a map of :id, :verdict and :evidence")]
                    (remove nil?
                            [(when-not (and (string? (:id v)) (not (str/blank? (:id v))))
                               (str where " names no :id"))
                             (when-not (#{:pass :fail} (:verdict v))
                               (str where " has the verdict " (pr-str (:verdict v)) ", not :pass or :fail"))
                             (when-not (and (string? (:evidence v)) (not (str/blank? (:evidence v))))
                               (str where " gives no evidence"))]))]
          (command/finding verdicts-file nil "verdicts-malformed" msg))
        (for [[id n] (sort counts) :when (> n 1)]
          (command/finding verdicts-file nil "verdicts-malformed" (str id " is graded " n " times: grade it once")))
        (for [id (sort (distinct (keep #(when (map? %) (:id %)) verdicts)))
              :when (and (string? id) (not (contains? ids id)))]
          (command/finding verdicts-file nil "verdict-not-on-checklist"
                           (str id " is not on the cycle's checklist: only its items are graded"))))))))

;; ---------------------------------------------------------------------------
;; The score

(defn score-items
  "Every checklist item (ids in order) with its verdict; an item nobody graded
  is a fail."
  [ids verdicts]
  (let [by-id (into {} (map (juxt :id identity) verdicts))]
    (vec (for [id ids
               :let [v (get by-id id)]]
           (if v
             (array-map :id id :verdict (:verdict v) :evidence (:evidence v))
             (array-map :id id :verdict :fail :evidence "not graded"))))))

(defn score-map
  [{:keys [cycle cut scope room-tree builder grader spec-owner items]}]
  [[:cycle cycle] [:cut cut] [:scope scope] [:room-tree room-tree]
   [:builder builder] [:grader grader] [:spec-owner spec-owner]
   [:items items]
   [:passed (count (filter #(= :pass (:verdict %)) items))]
   [:scored (count items)]])

(defn score-text
  "score.edn's text: one key per line in the declared order, one item per line."
  [entries]
  (str "{"
       (str/join "\n "
                 (for [[k v] entries]
                   (if (= :items k)
                     (str (pr-str k) " [" (str/join "\n         " (map pr-str v)) "]")
                     (str (pr-str k) " " (pr-str v)))))
       "}\n"))

(defn score
  "The score task: `rdm-spec score CYCLE --verdicts FILE`."
  [req]
  (let [cycle (first (:args req))
        dir (cycle-dir req cycle)
        c (read-cycle req dir)
        cycle-file (command/rel req (fs/file dir "cycle.edn"))
        vfile-arg (get-in req [:opts :verdicts])
        vfile (command/path req vfile-arg)
        verdicts (read-edn-file vfile)
        _ (when (= ::missing verdicts)
            (command/cannot-run! (str "no verdicts file " vfile-arg)))
        _ (when (or (nil? verdicts) (and (coll? verdicts) (not (map? verdicts)) (empty? verdicts)))
            (command/cannot-run! (str "the verdicts file " vfile-arg " holds no verdict: the grading did not happen,"
                                      " and nothing is recorded")))
        unreadable (when (= ::unreadable verdicts)
                     [(command/finding vfile-arg nil "verdicts-malformed" "the verdicts file is not readable EDN")])
        role (role-findings cycle-file c)
        ratified-file (fs/file dir "ratified.edn")
        ratified (read-edn-file ratified-file)
        rat (ratification-findings (command/rel req ratified-file) ratified)
        early (vec (concat role rat unreadable))]
    (if (seq early)
      {:result {} :findings early}
      (let [checklist (read-checklist req dir)
            ids (mapv #(get % "id") (get checklist "items"))
            vf (verdict-findings vfile-arg verdicts (set ids))
            stale (when (and (get checklist "cut") (not= (:cut c) (get checklist "cut")))
                    [(command/finding (command/rel req (fs/file dir "checklist.json")) nil "checklist-stale"
                                      (str "the checklist is of the cut " (get checklist "cut") ", the cycle's "
                                           (:cut c) ": derive it again"))])
            findings (vec (concat vf stale))]
        (if (seq findings)
          {:result {} :findings findings}
          (let [items (score-items ids verdicts)
                entries (score-map {:cycle cycle :cut (:cut c) :scope (get c :scope :project)
                                    :room-tree (:room-tree ratified)
                                    :builder (:builder c) :grader (:grader c) :spec-owner (:spec-owner c)
                                    :items items})
                out (fs/file dir "score.edn")
                m (into {} entries)
                ungraded (- (count ids) (count verdicts))]
            (spit (str out) (score-text entries))
            {:result (array-map :cycle cycle :cut (:cut c) :passed (:passed m) :scored (:scored m)
                                :written (command/rel req out))
             :findings (if (pos? ungraded)
                         [(command/warning "ungraded" (str ungraded " item(s) nobody graded, scored as failed"))]
                         [])}))))))
