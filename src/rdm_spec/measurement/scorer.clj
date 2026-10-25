(ns rdm-spec.measurement.scorer
  "Component Scorer of the bounded context measurement
  (dhf/spec/measurement/scorer.feature): `rdm-spec score CYCLE --verdicts
  FILE` files a grader's verdicts on a ratified cycle into
  dhf/cycles/CYCLE/score.edn, and nothing when nothing was graded.

  The scorer never grades. It refuses (exit 1, nothing written):
  - a cycle.edn that leaves a role unnamed, or names a role without a name or
    as neither :human nor :agent;
  - a cycle whose builder is also its grader or its spec owner;
  - a cycle with no passing ratification (ratified.edn absent, or its
    :outcome not :passed, or for another cut);
  - a malformed verdicts file (not a vector; an entry that is not exactly
    {:id :verdict :evidence}; a verdict neither :pass nor :fail; no evidence;
    an item graded twice);
  - a verdict for an item the cycle's checklist does not hold.
  A verdicts file holding no verdict cannot be scored at all (exit 2): the
  grading did not happen. An item of the checklist nobody graded is recorded
  as failed.

  score.edn: {:cycle :cut :scope :room-tree :builder :grader :spec-owner
  :items [{:id :verdict :evidence}] :passed :scored}, keys in that order, one
  item per checklist item in the checklist's order. An ungraded item carries
  :ungraded true besides its :fail."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]))

;; ---------------------------------------------------------------------------
;; Roles

(def roles
  "The roles a cycle names, in the order they are reported."
  [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]])

(def actors #{:human :agent})

(defn role-findings
  "Findings on cycle.edn's roles: each named, with a name and an actor, and
  the builder neither the grader nor the spec owner. `file` names cycle.edn."
  [file c]
  (let [bad (fn [rule msg] (command/finding file nil rule msg))
        named (fn [r] (let [n (:name r)] (when (and (string? n) (not (str/blank? n))) (str/trim n))))
        shape (for [[k label] roles
                    :let [r (get c k)]
                    f [(cond
                         (nil? r)
                         (bad "cycle-role-missing" (str "the cycle names no " label " (" k "); every role is named, "
                                                        "{:name \"...\" :actor :human or :agent}"))
                         (not (map? r))
                         (bad "cycle-role" (str "the " label " (" k ") is " (pr-str r)
                                                ", not {:name \"...\" :actor :human or :agent}"))
                         (nil? (named r))
                         (bad "cycle-role" (str "the " label " (" k ") has no name"))
                         (not (actors (:actor r)))
                         (bad "cycle-role" (str "the " label " " (named r) " is " (pr-str (:actor r))
                                                ", neither :human nor :agent")))]
                    :when f]
                f)
        builder (some-> (:builder c) (#(when (map? %) (named %))))
        same (for [[k label] (rest roles)
                   :let [r (get c k)]
                   :when (and builder (map? r) (= builder (named r)))]
               (bad "builder-scores-own-cycle"
                    (str "the builder " builder " is also the cycle's " label
                         ": the builder may not grade or read its own score")))]
    (vec (concat shape same))))

;; ---------------------------------------------------------------------------
;; The ratification

(defn ratification-findings
  "Findings unless `ratified` (ratified.edn's content, or nil when absent)
  records a passing ratification of `cut`."
  [file ratified cut]
  (let [bad #(command/finding file nil "no-passing-ratification" %)]
    (cond
      (nil? ratified)
      [(bad "the cycle has no passing ratification: there is no ratified.edn; run rdm-spec ratify ROOM CYCLE first")]
      (not (map? ratified))
      [(bad "the cycle has no passing ratification: ratified.edn is not a map")]
      (not= :passed (:outcome ratified))
      [(bad (str "the cycle has no passing ratification: ratified.edn holds :outcome "
                 (pr-str (:outcome ratified))))]
      (not= cut (:cut ratified))
      [(bad (str "the cycle has no passing ratification: ratified.edn ratified the cut " (pr-str (:cut ratified))
                 ", the cycle names " (pr-str cut)))]
      :else [])))

;; ---------------------------------------------------------------------------
;; Verdicts

(def verdict-keys #{:id :verdict :evidence})

(defn parse-verdicts
  "The forms of a verdicts file's text: {:forms [..]} or {:error message}.
  Maps and vectors carry their line as :row metadata."
  [text]
  (try {:forms (vec (edamame/parse-string-all text {:all false}))}
       (catch Exception e {:error (ex-message e)})))

(defn verdict-findings
  "Findings on the verdicts `v` (the file's one form), read from `file`:
  a vector of {:id :verdict :evidence}, each id once."
  [file v]
  (let [at (fn [x] (:row (meta x)))
        bad (fn [x rule msg] (command/finding file (at x) rule msg))]
    (cond
      (map? v)
      [(bad v "verdicts-shape" "the verdicts file is a map, not a vector of {:id :verdict :evidence}")]
      (not (vector? v))
      [(bad v "verdicts-shape" (str "the verdicts file holds " (pr-str v) ", not a vector of {:id :verdict :evidence}"))]
      :else
      (let [entry (fn [e]
                    (let [id (:id e)
                          who (if (and (string? id) (not (str/blank? id))) id "a verdict")]
                      (if-not (map? e)
                        [(bad e "verdicts-shape" (str (pr-str e) " is not a verdict {:id :verdict :evidence}"))]
                        (cond-> []
                          (not (and (string? id) (not (str/blank? id))))
                          (conj (bad e "verdicts-id" (str (pr-str e) " names no item: :id is " (pr-str id)
                                                          ", not a scenario id")))
                          (seq (remove verdict-keys (keys e)))
                          (conj (bad e "verdicts-shape" (str who " carries " (str/join ", " (sort (remove verdict-keys (keys e))))
                                                             "; a verdict is exactly :id, :verdict and :evidence")))
                          (not (#{:pass :fail} (:verdict e)))
                          (conj (bad e "verdicts-verdict" (str who " has the verdict " (pr-str (:verdict e))
                                                               "; a verdict is :pass or :fail")))
                          (not (and (string? (:evidence e)) (not (str/blank? (:evidence e)))))
                          (conj (bad e "verdicts-evidence" (str who " gives no evidence; every verdict carries its evidence")))))))
            twice (for [[id es] (group-by :id (filter #(and (map? %) (string? (:id %))) v))
                        :when (> (count es) 1)]
                    (bad (second es) "verdicts-duplicate"
                         (str id " is graded " (count es) " times; each item is graded once")))]
        (vec (concat (mapcat entry v) twice))))))

(defn unknown-findings
  "Findings for each verdict whose id the checklist (`ids`) does not hold."
  [file verdicts ids]
  (let [known (set ids)]
    (vec (for [v verdicts :when (not (known (:id v)))]
           (command/finding file (:row (meta v)) "verdict-not-on-checklist"
                            (str (:id v) " is not on the cycle's checklist; only its items are graded"))))))

;; ---------------------------------------------------------------------------
;; The score

(def ungraded-evidence "not graded: no verdict was filed for this item")

(defn score-items
  "One item per checklist id, in the checklist's order: the grader's verdict,
  or a fail when nobody graded it."
  [ids verdicts]
  (let [by-id (into {} (map (juxt :id identity) verdicts))]
    (vec (for [id ids]
           (if-let [v (by-id id)]
             (array-map :id id :verdict (:verdict v) :evidence (:evidence v))
             (array-map :id id :verdict :fail :evidence ungraded-evidence :ungraded true))))))

(defn score-map
  "score.edn's value, keys in order."
  [cycle c ratified items]
  (array-map :cycle cycle
             :cut (:cut c)
             :scope (or (:scope c) :project)
             :room-tree (:room-tree ratified)
             :builder (:builder c)
             :grader (:grader c)
             :spec-owner (:spec-owner c)
             :items items
             :passed (count (filter #(= :pass (:verdict %)) items))
             :scored (count items)))

(defn score-text
  "score.edn's text: one key per line, one item per line."
  [m]
  (str "{"
       (str/join "\n "
                 (for [[k v] m]
                   (if (= :items k)
                     (str ":items [" (str/join "\n         " (map pr-str v)) "]")
                     (str (pr-str k) " " (pr-str v)))))
       "}\n"))

(defn read-score
  "Cycle `cycle`'s score.edn, or cannot-run naming it when it is absent or
  not an EDN map."
  [req cycle]
  (let [f (checklist/cycle-file req cycle "score.edn")]
    (when-not (fs/exists? f)
      (command/cannot-run! (str "no " (command/rel req f) ": cycle " cycle " is not scored; "
                                "rdm-spec score " cycle " --verdicts FILE files its verdicts")))
    (let [s (checklist/read-edn-file req f)]
      (when-not (map? s) (command/cannot-run! (str (command/rel req f) " is not a map")))
      s)))

(defn- read-ratified [req cycle]
  (let [f (checklist/cycle-file req cycle "ratified.edn")]
    (when (fs/exists? f)
      (try (edn/read-string (slurp (str f))) (catch Exception _ ::unreadable)))))

(defn score
  "The score task: `rdm-spec score CYCLE --verdicts FILE`."
  [req]
  (let [[cycle] (:args req)
        verdicts-arg (get-in req [:opts :verdicts])
        c (checklist/read-cycle req cycle)
        cycle-rel (command/rel req (checklist/cycle-file req cycle "cycle.edn"))
        vfile (command/path req verdicts-arg)
        vrel (command/rel req vfile)
        _ (when-not (fs/regular-file? vfile)
            (command/cannot-run! (str "no verdicts file " vrel)))
        {:keys [forms error]} (parse-verdicts (slurp (str vfile)))
        _ (when (or (and (nil? error) (empty? forms))
                    (and (= 1 (count forms)) (or (nil? (first forms)) (= [] (first forms)))))
            (command/cannot-run! (str vrel " holds no verdict: the grading did not happen, so nothing is scored")))
        verdict-fs (cond
                     error [(command/finding vrel nil "verdicts-unreadable" (str vrel " is not EDN: " error))]
                     (> (count forms) 1) [(command/finding vrel (:row (meta (second forms))) "verdicts-shape"
                                                           (str vrel " holds " (count forms)
                                                                " values; it holds one vector of verdicts"))]
                     :else (verdict-findings vrel (first forms)))
        verdicts (when (empty? verdict-fs) (first forms))
        cl (checklist/read-checklist req cycle)
        ids (mapv :id (:items cl))
        ratified (read-ratified req cycle)
        findings (vec (concat (role-findings cycle-rel c)
                              (ratification-findings (command/rel req (checklist/cycle-file req cycle "ratified.edn"))
                                                     (when-not (= ::unreadable ratified) ratified)
                                                     (:cut c))
                              verdict-fs
                              (when verdicts (unknown-findings vrel verdicts ids))))]
    (if (seq findings)
      {:result {:cycle cycle :written nil} :findings findings}
      (let [items (score-items ids verdicts)
            m (score-map cycle c ratified items)
            f (checklist/cycle-file req cycle "score.edn")]
        (spit (str f) (score-text m))
        {:result (array-map :cycle cycle :cut (:cut c) :passed (:passed m) :scored (:scored m)
                            :ungraded (count (filter :ungraded items))
                            :written (command/rel req f))
         :findings []}))))
