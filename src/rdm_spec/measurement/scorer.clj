(ns rdm-spec.measurement.scorer
  "Component Scorer of the bounded context measurement
  (dhf/spec/measurement/scorer.feature): `rdm-spec score CYCLE --verdicts FILE`.

  Files a grader's verdicts against the cycle's checklist into
  dhf/cycles/CYCLE/score.edn, and nothing when nothing was graded. The
  verdicts file is an EDN vector of {:id \"spec-NNNN\" :verdict :pass|:fail
  :evidence \"...\"}, one per item at most. An item no verdict grades is
  scored as a fail. score.edn:

    {:cycle :cut :scope :room-tree :builder :grader :spec-owner
     :items [{:id :verdict :evidence} ...]   ; the checklist's items, in its order
     :passed n :scored n}

  Refused, exit 1, nothing written: a cycle.edn that leaves a role (builder,
  grader, spec owner) unnamed or names one without :actor :human or :agent;
  a builder who is also the grader or the spec owner; a cycle with no passing
  ratification (ratified.edn absent, or not :outcome :passed, or of another
  cut); a malformed verdicts file; a verdict for an item not on the
  checklist. Cannot run, exit 2, nothing written: no cycle.edn, no
  checklist.json, no verdicts file, or a verdicts file with no verdict in it
  (the grading did not happen)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]))

;; ---------------------------------------------------------------------------
;; Roles

(def roles
  "The roles a cycle names: [key how it is said]."
  [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]])

(def actors #{:human :agent})

(defn- who [r] (some-> (:name r) str str/trim str/lower-case))

(defn role-findings
  "Findings on the cycle's roles: each named, as a human or an agent, and the
  builder neither the grader nor the spec owner. `file` names cycle.edn."
  [file c]
  (let [missing (for [[k said] roles
                      :let [r (get c k)]
                      :when (not (and (map? r) (string? (:name r)) (not (str/blank? (:name r)))
                                      (contains? actors (:actor r))))]
                  (command/finding file nil "missing-role"
                                   (if (nil? r)
                                     (str "the cycle names no " said " (" k " {:name \"...\" :actor :human or :agent})")
                                     (str "the cycle's " said " " (pr-str r) " needs a :name and an :actor, :human or :agent"))))
        builder (get c :builder)
        same (when (and (empty? missing) (who builder))
               (for [[k said] (rest roles)
                     :when (= (who builder) (who (get c k)))]
                 (command/finding file nil "builder-independence"
                                  (str "the builder " (:name builder) " is also the cycle's " said
                                       " (" k "): the builder may not grade or read its own score"))))]
    (vec (concat missing same))))

;; ---------------------------------------------------------------------------
;; Ratification

(defn ratification-findings
  "Findings unless `ratified` (ratified.edn's value, nil when absent) records
  a passing ratification of `cut`. `file` names ratified.edn."
  [file ratified cut]
  (cond
    (nil? ratified)
    [(command/finding file nil "not-ratified"
                      (str "the cycle has no passing ratification: no " file " (run rdm-spec ratify ROOM CYCLE)"))]

    (not (map? ratified))
    [(command/finding file nil "not-ratified" (str "the cycle has no passing ratification: " file " is not a map"))]

    (not= :passed (:outcome ratified))
    [(command/finding file nil "not-ratified"
                      (str "the cycle has no passing ratification: " file " holds :outcome " (pr-str (:outcome ratified))))]

    (not= cut (:cut ratified))
    [(command/finding file nil "not-ratified"
                      (str "the cycle has no passing ratification of its cut " cut ": " file " ratified "
                           (pr-str (:cut ratified))))]

    :else []))

;; ---------------------------------------------------------------------------
;; Verdicts

(defn- line-of [text id]
  (when (and text (string? id))
    (when-let [i (str/index-of text (str "\"" id "\""))]
      (inc (count (filter #{\newline} (subs text 0 i)))))))

(defn verdict-findings
  "Findings on the verdicts `v` read from `file` (its `text` locates them):
  a vector of maps, each with a string :id, a :verdict :pass or :fail and a
  non-blank :evidence, no id graded twice, every id on the checklist (`ids`)."
  [file text v ids]
  (if-not (vector? v)
    [(command/finding file nil "malformed-verdicts"
                      (str "the verdicts file is " (cond (map? v) "a map" (seq? v) "a list" :else (pr-str (type v)))
                           ", not a vector of {:id :verdict :evidence}"))]
    (let [at (fn [id] (line-of text id))
          shape (for [[i x] (map-indexed vector v)
                      :let [id (when (map? x) (:id x))
                            label (if (string? id) id (str "verdict " (inc i)))]
                      msg (cond
                            (not (map? x)) [(str "verdict " (inc i) " is " (pr-str x) ", not a map of :id, :verdict and :evidence")]
                            :else
                            (concat
                             (when-not (and (string? id) (not (str/blank? id)))
                               [(str label " has no :id (a scenario id, as a string)")])
                             (when-not (contains? #{:pass :fail} (:verdict x))
                               [(str "it gives " label " the verdict " (pr-str (:verdict x)) "; a verdict is :pass or :fail")])
                             (when-not (and (string? (:evidence x)) (not (str/blank? (:evidence x))))
                               [(str "it gives " label " no evidence (:evidence, a non-blank string)")])))]
                  (command/finding file (at id) "malformed-verdicts" msg))
          graded (keep #(when (and (map? %) (string? (:id %))) (:id %)) v)
          twice (for [[id n] (frequencies graded) :when (> n 1)]
                  (command/finding file (at id) "malformed-verdicts"
                                   (str "it grades " id " twice (" n " verdicts): one verdict per item")))
          unknown (for [id (distinct graded) :when (not (contains? ids id))]
                    (command/finding file (at id) "not-on-checklist"
                                     (str id " is not on the cycle's checklist: no verdict for it is filed")))]
      (vec (concat shape twice unknown)))))

;; ---------------------------------------------------------------------------
;; The score

(defn score-items
  "The checklist's items scored by `verdicts`: graded items as graded, an
  ungraded item as a fail."
  [checklist-items verdicts]
  (let [by-id (into {} (map (juxt :id identity) verdicts))]
    (mapv (fn [{:keys [id]}]
            (if-let [v (by-id id)]
              (array-map :id id :verdict (:verdict v) :evidence (:evidence v))
              (array-map :id id :verdict :fail :evidence "not graded: no verdict was filed, so it is scored as a fail")))
          checklist-items)))

(defn- person [r]
  (into (array-map :name (:name r) :actor (:actor r)) (dissoc r :name :actor)))

(defn score-text
  "The text of score.edn, one key per line in the documented order."
  [{:keys [cycle cut scope room-tree builder grader spec-owner items passed scored]}]
  (str "{:cycle " (pr-str cycle) "\n"
       " :cut " (pr-str cut) "\n"
       " :scope " (pr-str scope) "\n"
       " :room-tree " (pr-str room-tree) "\n"
       " :builder " (pr-str (person builder)) "\n"
       " :grader " (pr-str (person grader)) "\n"
       " :spec-owner " (pr-str (person spec-owner)) "\n"
       " :items [" (str/join "\n         " (map pr-str items)) "]\n"
       " :passed " passed "\n"
       " :scored " scored "}\n"))

(defn score
  "The score task: `rdm-spec score CYCLE --verdicts FILE`."
  [req]
  (let [cycle (first (:args req))
        c (checklist/read-cycle req cycle)
        cut (:cut c)
        rel #(command/rel req %)
        cycle-f (checklist/cycle-file req cycle "cycle.edn")
        ratified-f (checklist/cycle-file req cycle "ratified.edn")
        ratified (try (checklist/read-edn-file req ratified-f)
                      (catch clojure.lang.ExceptionInfo _ ::unreadable))
        cl (or (checklist/read-checklist-json req cycle)
               (command/cannot-run! (str "no " (rel (checklist/cycle-file req cycle "checklist.json"))
                                         ": derive it first (rdm-spec checklist " cycle ")")))
        items (vec (:items cl))
        ids (set (map :id items))
        vf (command/path req (get-in req [:opts :verdicts]))
        vname (rel vf)
        _ (when-not (fs/regular-file? vf)
            (command/cannot-run! (str "no verdicts file " vname ": the grading did not happen")))
        text (slurp (str vf))
        [v unreadable] (try [(edn/read-string text) nil]
                            (catch Exception e [nil (ex-message e)]))
        _ (when (and (nil? unreadable) (or (nil? v) (and (coll? v) (empty? v))))
            (command/cannot-run! (str vname " holds no verdict: the grading did not happen, so nothing is recorded")))
        findings (vec (concat
                       (role-findings (rel cycle-f) c)
                       (ratification-findings (rel ratified-f) (when-not (= ::unreadable ratified) ratified) cut)
                       (when (and (string? (:cut cl)) (not= cut (:cut cl)))
                         [(command/finding (rel (checklist/cycle-file req cycle "checklist.json")) nil "checklist-cut"
                                           (str "the checklist was derived at the cut " (:cut cl) ", not the cycle's " cut
                                                " (rdm-spec checklist " cycle ")"))])
                       (if unreadable
                         [(command/finding vname nil "malformed-verdicts" (str "the verdicts file is not EDN: " unreadable))]
                         (verdict-findings vname text v ids))))]
    (if (seq findings)
      {:result {:cycle cycle :written false} :findings findings}
      (let [scored-items (score-items items v)
            graded (set (map :id v))
            ungraded (vec (remove graded (map :id items)))
            passed (count (filter #(= :pass (:verdict %)) scored-items))
            record {:cycle cycle :cut cut :scope (get c :scope :project)
                    :room-tree (:room-tree ratified)
                    :builder (:builder c) :grader (:grader c) :spec-owner (:spec-owner c)
                    :items scored-items :passed passed :scored (count scored-items)}
            f (checklist/cycle-file req cycle "score.edn")]
        (spit (str f) (score-text record))
        {:result {:cycle cycle :cut cut :passed passed :scored (count scored-items)
                  :ungraded ungraded :score (rel f)}
         :findings (vec (for [id ungraded]
                          (command/warning vname nil "ungraded" (str id " was not graded: it is scored as a fail"))))}))))
