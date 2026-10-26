(ns rdm-spec.measurement.scorer
  "The Scorer (dhf/spec/measurement/scorer.feature): `rdm-spec score CYCLE
  --verdicts FILE` files a grader's verdicts on a verified cycle into
  dhf/cycles/CYCLE/score.edn, and nothing when nothing was graded. Scoring
  is independent of the builder's tests: a grader grades each checklist
  item, however they grade, and the score is the verdicts.

  It reads the cycle's cycle.edn (its cut, scope and roles), ratified.edn
  (verify's record, under its old name: only a cycle whose verification
  passed is scored), checklist.json (its items, in order) and the verdicts
  file, a vector of {:id :verdict :pass | :fail :evidence \"...\"}, each id
  once.

    score.edn  {:cycle :cut :scope :room-tree :builder :grader :spec-owner
                :items [{:id :verdict :evidence [:ungraded true]}] :passed n
                :scored n}

  An item nobody graded is a fail, {:id ID :verdict :fail :evidence
  \"ungraded: no verdict was given\" :ungraded true}.

  Findings (exit 1, nothing written): a role left unnamed or not human or
  agent (score-role), a builder who also grades or reads the score
  (score-builder), a cycle with no passing verification (score-unverified),
  a malformed verdicts file (verdicts-malformed), a verdict for an id not
  on the checklist (verdict-unknown). Cannot run (exit 2): no cycle.edn, no
  checklist.json, no verdicts file, and zero verdicts: the grading did not
  happen. Result: {:score {:cycle :file :passed :scored :ungraded}}."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def ungraded-evidence "ungraded: no verdict was given")

;; ---------------------------------------------------------------------------
;; Pure

(defn malformed
  "Why the verdicts `v` are malformed, or nil."
  [v]
  (cond
    (not (vector? v)) (str "the verdicts are " (if (map? v) "a map" "not a vector")
                           ": they are a vector of {:id :verdict :evidence}")
    :else
    (or (first (for [x v :when (not (map? x))] (str (pr-str x) " is not a verdict, {:id :verdict :evidence}")))
        (first (for [[id n] (frequencies (map :id v)) :when (< 1 n)] (str id " is graded twice: each item is graded once")))
        (first (for [x v :when (not (and (string? (:id x)) (not (str/blank? (:id x)))))]
                 (str (pr-str x) " names no :id")))
        (first (for [x v :when (not (#{:pass :fail} (:verdict x)))]
                 (str (:id x) " has the verdict " (pr-str (:verdict x)) ": a verdict is :pass or :fail")))
        (first (for [x v :when (not (and (string? (:evidence x)) (not (str/blank? (:evidence x)))))]
                 (str (:id x) " gives no evidence: every verdict carries its evidence")))
        (first (for [x v :when (seq (dissoc x :id :verdict :evidence))]
                 (str (:id x) " holds " (pr-str (vec (keys (dissoc x :id :verdict :evidence))))
                      ": a verdict holds :id, :verdict and :evidence only"))))))

(defn role-problem
  "Why the cycle `c`'s roles cannot be scored, or nil."
  [c]
  (let [role (fn [k] (get c k))]
    (or (first (for [k [:builder :grader :spec-owner]
                     :let [r (role k)]
                     :when (not (and (map? r) (string? (:name r)) (not (str/blank? (:name r)))))]
                 (str "the cycle names no " k ": every role is named, {:name :actor}")))
        (first (for [k [:builder :grader :spec-owner]
                     :when (not (#{:human :agent} (:actor (role k))))]
                 (str "the cycle's " k " has the :actor " (pr-str (:actor (role k))) ": :human or :agent")))
        (let [b (:name (role :builder))]
          (when (some #(= b (:name (role %))) [:grader :spec-owner])
            (str b " is the cycle's builder and its "
                 (if (= b (:name (role :grader))) "grader" "spec owner")
                 ": the builder may not grade or read its own score"))))))

(defn score-items
  "Pure: {:items :passed :scored} of the checklist `ids` and `verdicts`."
  [ids verdicts]
  (let [by (into {} (map (juxt :id identity)) verdicts)
        items (vec (for [id ids]
                     (or (get by id)
                         {:id id :verdict :fail :evidence ungraded-evidence :ungraded true})))]
    {:items items
     :passed (count (filter #(= :pass (:verdict %)) items))
     :scored (count items)}))

;; ---------------------------------------------------------------------------
;; The stage

(defn- rel-of [req cycle file]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle "/" file))

(defn- edn-file [req rel]
  (let [f (command/path req rel)]
    (when (fs/regular-file? f)
      (try {:value (edn/read-string (slurp (str f)))}
           (catch Exception e {:error (ex-message e)})))))

(defn score
  "The score stage: `rdm-spec score CYCLE --verdicts FILE`."
  [req]
  (let [cycle (first (:args req))
        cycle-rel (rel-of req cycle "cycle.edn")
        c (:value (or (edn-file req cycle-rel)
                      (command/cannot-run! (str "there is no " cycle-rel ": a cycle is declared before it is scored"))))
        _ (when-not (map? c) (command/cannot-run! (str cycle-rel " is not an EDN map")))
        rat-rel (rel-of req cycle "ratified.edn")
        rat (:value (edn-file req rat-rel))
        cl-rel (rel-of req cycle "checklist.json")
        vf (get-in req [:opts :verdicts])
        stop (fn [f] {:result {} :findings [f]})]
    (if-let [why (role-problem c)]
      (stop (command/finding cycle-rel (if (str/includes? why "builder may not") "score-builder" "score-role") why))
      (if-not (= :passed (:outcome rat))
        (stop (command/finding rat-rel "score-unverified"
                               (str "cycle " cycle " has no passing verification ("
                                    (if (map? rat) (str "its outcome is " (pr-str (:outcome rat))) (str "there is no " rat-rel))
                                    "): only a verified build is scored; nothing was written")))
        (let [cf (command/path req cl-rel)
              _ (when-not (fs/regular-file? cf)
                  (command/cannot-run! (str "there is no " cl-rel ": run rdm-spec checklist " cycle " first")))
              cl (try (json/parse-string (slurp (str cf)) true)
                      (catch Exception e (command/cannot-run! (str cl-rel " is not readable JSON: " (ex-message e)))))
              ids (mapv :id (:items cl))
              vfile (command/path req vf)
              _ (when-not (fs/regular-file? vfile)
                  (command/cannot-run! (str "there is no verdicts file " vf)))
              text (slurp (str vfile))
              v (try (edn/read-string text) (catch Exception e {::error (ex-message e)}))]
          (cond
            (and (map? v) (::error v))
            (stop (command/finding vf "verdicts-malformed" (str "the verdicts file " vf " is not readable EDN: " (::error v))))
            (and (sequential? v) (empty? v))
            (command/cannot-run! (str "the verdicts file " vf " holds no verdict: the grading did not happen, so nothing is recorded"))
            (malformed v)
            (stop (command/finding vf "verdicts-malformed" (str "the verdicts file " vf " is malformed: " (malformed v)
                                                               "; nothing was written")))
            :else
            (let [unknown (remove (set ids) (map :id v))]
              (if (seq unknown)
                {:result {}
                 :findings (vec (for [id unknown]
                                  (command/finding vf "verdict-unknown"
                                                   (str "a verdict grades " id ", which is not on cycle " cycle
                                                        "'s checklist; nothing was written"))))}
                (let [{:keys [items passed scored]} (score-items ids v)
                      out (array-map :cycle cycle :cut (:cut c) :scope (if (contains? c :scope) (:scope c) :project)
                                     :room-tree (:room-tree rat)
                                     :builder (:builder c) :grader (:grader c) :spec-owner (:spec-owner c)
                                     :items items :passed passed :scored scored)
                      rel (rel-of req cycle "score.edn")]
                  (spit (str (command/path req rel))
                        (binding [*print-namespace-maps* false] (str (pr-str out) "\n")))
                  {:result {:score {:cycle cycle :file rel :passed passed :scored scored
                                    :ungraded (count (filter :ungraded items))}}
                   :findings []})))))))))
