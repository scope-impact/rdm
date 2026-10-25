(ns rdm-spec.measurement.scorer
  "Scorer (dhf/spec/measurement/scorer.feature): `rdm-spec score CYCLE
  --verdicts FILE` files a grader's verdicts on a ratified cycle into
  dhf/cycles/CYCLE/score.edn, and nothing when nothing was graded.

  A verdicts file is a vector of {:id \"spec-0012\" :verdict :pass|:fail
  :evidence \"non-blank text\"}, each id once and on the cycle's checklist.

  In order, writing nothing unless every check passes:
    cannot run (exit 2): no cycle.edn, no checklist.json, no verdicts file or
      one that is not EDN, a verdicts file holding no verdict (the grading
      did not happen);
    findings (exit 1): a role (:builder, :grader, :spec-owner) unnamed or
      not {:name \"..\" :actor :human|:agent}; a builder who is also the
      grader or the spec owner; no ratified.edn, or one whose :outcome is not
      :passed or whose :cut is not the cycle's; a malformed verdicts file; a
      verdict for an item not on the checklist.
  An item nobody graded is filed as a fail, with :ungraded true.

  score.edn, keys in this order:
    {:cycle :cut :scope :room-tree :builder :grader :spec-owner
     :items [{:id :verdict :evidence}] :passed :scored}
  items in the checklist's order."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]))

(def roles
  "The roles a cycle names, as score.edn and the findings name them."
  [[:builder "builder"] [:grader "grader"] [:spec-owner "spec owner"]])

(def actors #{:human :agent})

;; ---------------------------------------------------------------------------
;; Reading

(defn read-checklist
  "The cycle's checklist.json, or cannot run."
  [req cycle]
  (let [rel (checklist/cycle-file req cycle "checklist.json")
        f (command/path req rel)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " rel ": derive it first with rdm-spec checklist " cycle)))
    (let [c (try (json/parse-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str rel " is not readable JSON: " (ex-message e)))))]
      (when-not (and (map? c) (sequential? (get c "items")))
        (command/cannot-run! (str rel " holds no items")))
      (assoc c ::file rel))))

(defn- line-of
  "The first line of `text` holding `needle`, or nil."
  [text needle]
  (when (and text needle)
    (some (fn [[i l]] (when (str/includes? l needle) (inc i)))
          (map-indexed vector (str/split-lines text)))))

(defn read-verdicts
  "{:file rel :text text :value v} for the verdicts file `given` (relative to
  the root), or cannot run."
  [req given]
  (let [f (command/path req given)
        rel (command/rel req f)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no verdicts file " given)))
    (let [text (slurp (str f))
          v (try (edn/read-string text)
                 (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))]
      {:file rel :text text :value v})))

;; ---------------------------------------------------------------------------
;; Checking

(defn role-findings
  "Every role unnamed or malformed, and a builder who grades or reads its
  own score."
  [cycle]
  (let [file (::checklist/file cycle)
        bad (for [[k label] roles
                  :let [r (get cycle k)]
                  :when (not (and (map? r) (string? (:name r)) (not (str/blank? (:name r))) (actors (:actor r))))]
              (command/finding file nil "role-unnamed"
                               (if (nil? r)
                                 (str "the cycle names no " label " (" k "): every role is named, with :name and :actor :human or :agent")
                                 (str "the " label " (" k ") " (pr-str r) " is not {:name \"...\" :actor :human|:agent}"))))
        who (fn [k] (some-> (get-in cycle [k :name]) str str/trim str/lower-case))
        builder (who :builder)
        same (when (and builder (not (str/blank? builder)))
               (for [[k label] (rest roles) :when (= builder (who k))]
                 (command/finding file nil "builder-not-independent"
                                  (str "the builder " (get-in cycle [:builder :name]) " is also the cycle's " label
                                       ": the builder may not grade or read its own score"))))]
    (vec (concat bad same))))

(defn ratified-findings
  "Nothing when the cycle has a passing ratification of its cut."
  [req cycle-name cycle]
  (let [rel (checklist/cycle-file req cycle-name "ratified.edn")
        r (checklist/read-edn-file req rel nil)
        no (fn [why] [(command/finding rel nil "not-ratified" (str "the cycle has no passing ratification: " why))])]
    (cond
      (nil? r) (no (str rel " does not exist"))
      (not (map? r)) (no (str rel " is not a map"))
      (not= :passed (:outcome r)) (no (str "its outcome is " (pr-str (:outcome r))
                                           (when (:reason r) (str " (" (:reason r) ")"))))
      (not= (:cut cycle) (:cut r)) (no (str "it ratified the cut " (pr-str (:cut r)) ", not the cycle's " (:cut cycle)))
      :else [])))

(defn verdict-findings
  "What is malformed in the verdicts `v`, read from `file`/`text`, against
  the checklist ids `ids`."
  [{:keys [file text value]} ids]
  (let [at (fn [id] (line-of text (pr-str id)))]
    (if-not (vector? value)
      [(command/finding file 1 "verdicts-malformed"
                        (str "a verdicts file is a vector of {:id :verdict :evidence}, not "
                             (cond (map? value) "a map" (nil? value) "nothing" :else (pr-str (type value)))))]
      (let [shape (for [[i v] (map-indexed vector value)
                        :let [id (when (map? v) (:id v))
                              line (or (at id) 1)
                              where (if (string? id) id (str "verdict " (inc i)))]
                        problem (cond
                                  (not (map? v)) [(str "verdict " (inc i) " is not a map: " (pr-str v))]
                                  :else (remove nil?
                                                [(when-not (and (string? id) (not (str/blank? id)))
                                                   (str "verdict " (inc i) " has no :id"))
                                                 (when-not (#{:pass :fail} (:verdict v))
                                                   (str where " has the verdict " (pr-str (:verdict v)) ", not :pass or :fail"))
                                                 (when-not (and (string? (:evidence v)) (not (str/blank? (:evidence v))))
                                                   (str where " gives no evidence"))
                                                 (when-let [extra (seq (remove #{:id :verdict :evidence} (keys v)))]
                                                   (str where " has keys a verdict does not: " (pr-str (vec extra))))]))]
                    (command/finding file line "verdicts-malformed" problem))
            twice (for [[id n] (frequencies (keep #(when (map? %) (:id %)) value))
                        :when (and (string? id) (> n 1))]
                    (command/finding file (at id) "verdicts-malformed" (str id " is graded " n " times")))
            unknown (for [id (distinct (keep #(when (map? %) (:id %)) value))
                          :when (and (string? id) (not (contains? ids id)))]
                      (command/finding file (at id) "verdict-not-on-checklist"
                                       (str id " is not on the cycle's checklist")))]
        (vec (concat shape twice unknown))))))

;; ---------------------------------------------------------------------------
;; Filing

(defn score-items
  "Every checklist item with its verdict, in the checklist's order; an item
  nobody graded fails."
  [items verdicts]
  (let [by-id (into {} (map (juxt :id identity) verdicts))]
    (vec (for [it items
               :let [id (get it "id")
                     v (get by-id id)]]
           (if v
             {:id id :verdict (:verdict v) :evidence (:evidence v)}
             {:id id :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true})))))

(defn score-text
  "score.edn's text, its keys in the published order."
  [m]
  (let [ks [:cycle :cut :scope :room-tree :builder :grader :spec-owner :items :passed :scored]
        item-text (fn [it] (str "{" (str/join " " (for [k [:id :verdict :evidence :ungraded] :when (contains? it k)]
                                                    (str k " " (pr-str (get it k))))) "}"))
        val-text (fn [k v]
                   (if (= :items k)
                     (str "[" (str/join "\n         " (map item-text v)) "]")
                     (pr-str v)))]
    (str "{" (str/join "\n " (for [k ks] (str k " " (val-text k (get m k))))) "}\n")))

(defn score
  "The score stage."
  [req]
  (let [cycle-name (first (:args req))
        cycle (checklist/read-cycle req cycle-name)
        cl (read-checklist req cycle-name)
        verdicts (read-verdicts req (get-in req [:opts :verdicts]))
        _ (when (or (nil? (:value verdicts)) (and (vector? (:value verdicts)) (empty? (:value verdicts))))
            (command/cannot-run! (str (:file verdicts) " holds no verdict: the grading did not happen, so nothing is recorded")))
        items (get cl "items")
        ids (set (map #(get % "id") items))
        findings (vec (concat (role-findings cycle)
                              (ratified-findings req cycle-name cycle)
                              (when (not= (:cut cycle) (get cl "cut"))
                                [(command/finding (::file cl) nil "checklist-of-another-cut"
                                                  (str "the checklist is of the cut " (pr-str (get cl "cut"))
                                                       ", not the cycle's " (:cut cycle)))])
                              (verdict-findings verdicts ids)))]
    (if (seq findings)
      {:result {} :findings findings}
      (let [ratified (checklist/read-edn-file req (checklist/cycle-file req cycle-name "ratified.edn"))
            scored (score-items items (:value verdicts))
            m {:cycle cycle-name
               :cut (:cut cycle)
               :scope (get cycle :scope :project)
               :room-tree (:room-tree ratified)
               :builder (:builder cycle)
               :grader (:grader cycle)
               :spec-owner (:spec-owner cycle)
               :items scored
               :passed (count (filter #(= :pass (:verdict %)) scored))
               :scored (count scored)}
            rel (checklist/cycle-file req cycle-name "score.edn")
            f (command/path req rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (score-text m))
        {:result {:score {:cycle cycle-name :file rel :passed (:passed m) :scored (:scored m)
                          :ungraded (count (filter :ungraded scored))}}
         :findings []}))))
