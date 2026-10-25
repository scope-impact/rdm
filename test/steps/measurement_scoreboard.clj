(ns steps.measurement-scoreboard
  "Step definitions for dhf/spec/measurement/scoreboard.feature.

  A scoreboard fixture is a project holding only the configuration and the
  cycles in :cycles, {cycle {:verdicts {id :pass|:fail} :scope s :bootstrap?}},
  each written as cycle.edn, checklist.json (its scored items, half of them of
  the context verification) and score.edn. Each Given changes :cycles and
  rebuilds the fixture, so Givens compose."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-scoreboard! [] (s/needs-built! 'rdm-spec.measurement.scoreboard))

(defn ids [n] (mapv #(format "spec-%04d" (+ 701 %)) (range n)))

(defn context-of
  "The context of item `id` in every fixture: the even ones are verification's."
  [id]
  (if (even? (parse-long (subs id 5))) "verification" "architecture"))

(defn- all-pass [ids] (into (sorted-map) (map (fn [id] [id :pass]) ids)))

(defn- write-cycle-files! [dir cycle {:keys [verdicts scope bootstrap?]}]
  (if bootstrap?
    (mc/write-cycle! dir cycle {:bootstrap true})
    (mc/write-cycle! dir cycle (cond-> {} scope (assoc :scope scope))))
  (when-not bootstrap?
    (s/write! dir (mc/cycle-path cycle "checklist.json")
              (json/generate-string
               {"cycle" cycle "cut" "spec-3"
                "items" (vec (for [id (sort (keys verdicts))]
                               {"id" id "title" (str "Scenario " id) "context" (context-of id)
                                "component" (if (= "verification" (context-of id)) "Status" "Lint") "level" "e2e"}))
                "planned" []}))
    (s/write! dir (mc/cycle-path cycle "score.edn")
              (pr-str {:cycle cycle :cut "spec-3" :scope (or scope :project)
                       :room-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
                       :builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
                       :spec-owner {:name "Sami" :actor :human}
                       :items (vec (for [[id v] (sort verdicts)] {:id id :verdict v :evidence "graded"}))
                       :passed (count (filter #{:pass} (vals verdicts))) :scored (count verdicts)}))))

(defn- fixture! [ctx cycles]
  (needs-scoreboard!)
  (let [dir (or (:dir ctx) (s/project!))]
    (when (fs/exists? (fs/file dir "dhf/cycles")) (fs/delete-tree (fs/file dir "dhf/cycles")))
    (doseq [[c data] cycles] (write-cycle-files! dir c data))
    (assoc ctx :dir dir :cycles cycles)))

(defn- board [ctx]
  (mc/exits! ctx 0)
  (kc/stdout-edn (mc/the-run ctx)))

(defn- flip [verdicts ids to] (reduce #(assoc %1 %2 to) verdicts ids))

;; ---------------------------------------------------------------------------
;; The scoreboard compares cycles honestly

(defstep #"cycle 01 and cycle 02 scored over the same (\d+) items"
  (fn [ctx n]
    (let [items (ids (parse-long n))]
      (fixture! ctx {"01" {:verdicts (all-pass items)} "02" {:verdicts (all-pass items)}}))))

(defstep #"(\d+) items passed in 02 that failed in 01, and (\d+) failed in 02 that passed in 01"
  (fn [ctx better worse]
    (let [items (sort (keys (get-in ctx [:cycles "01" :verdicts])))
          b (take (parse-long better) items)
          w (take (parse-long worse) (drop (parse-long better) items))]
      (fixture! (assoc ctx :better (vec b) :worse (vec w))
                (-> (:cycles ctx)
                    (update-in ["01" :verdicts] flip b :fail)
                    (update-in ["02" :verdicts] flip w :fail))))))

(defstep #"it reports (\d+) got better and (\d+) got worse"
  (fn [ctx better worse]
    (let [o (:overall (board ctx))]
      (s/check! o (str "no overall comparison\n" (s/show (mc/the-run ctx))))
      (s/check! (= [(parse-long better) (parse-long worse)] [(:better o) (:worse o)])
                (str "it reports " (:better o) " better and " (:worse o) " worse"))
      (s/check! (= (:better ctx) (:better-ids o)) (str "better: " (pr-str (:better-ids o))))
      (s/check! (= (:worse ctx) (:worse-ids o)) (str "worse: " (pr-str (:worse-ids o)))))))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [b (board ctx)
          ks (atom [])]
      (walk/postwalk #(do (when (map? %) (swap! ks into (keys %))) %) b)
      (s/check! (not-any? #(re-find #"(?i)net|delta|change|total" (name %)) @ks) (str "keys: " (pr-str (distinct @ks))))
      (s/check! (not (re-find #"(?i)\bnet\b" (str (:out (mc/the-run ctx)) (:err (mc/the-run ctx)))))
                "the output speaks of a net figure"))))

(defstep #"cycle 01 scored (\d+) items and cycle 02 scored (\d+)"
  (fn [ctx a b]
    (fixture! ctx {"01" {:verdicts (all-pass (ids (parse-long a)))}
                   "02" {:verdicts (all-pass (ids (parse-long b)))}})))

(defstep #"it compares only the (\d+) items both scored"
  (fn [ctx n]
    (let [b (board ctx)]
      (s/check! (= (parse-long n) (get-in b [:overall :compared]) (get-in b [:denominator :compared]))
                (str "it compared " (pr-str (:overall b)) " " (pr-str (:denominator b)))))))

(defstep #"it states that the denominator moved from (\d+) to (\d+)"
  (fn [ctx from to]
    (let [d (:denominator (board ctx))]
      (s/check! (= [(parse-long from) (parse-long to) true] [(:from d) (:to d) (:moved d)]) (pr-str d))
      (mc/says! ctx (re-pattern (str "denominator moved from " from " to " to)) "that the denominator moved"))))

(defstep #"it counts the (\d+) new items separately"
  (fn [ctx n]
    (let [d (:denominator (board ctx))
          want (vec (remove (set (keys (get-in ctx [:cycles "01" :verdicts])))
                            (sort (keys (get-in ctx [:cycles "02" :verdicts])))))]
      (s/check! (= (parse-long n) (:new d)) (pr-str d))
      (s/check! (= want (:new-ids d)) (pr-str d)))))

;; ---------------------------------------------------------------------------
;; A scoped cycle is reported target first

(def every-item (ids 12))

(defn- verification? [id] (= "verification" (context-of id)))

(defstep #"cycle 02 regenerated the bounded context verification and cycle 01 is its baseline"
  (fn [ctx]
    (fixture! ctx {"01" {:verdicts (all-pass every-item)}
                   "02" {:verdicts (all-pass every-item) :scope {:context :verification}}})))

(defn- mixed!
  "Cycle 01 fails two of verification's items and one other; cycle 02 fails
  one other item cycle 01 passed (and scores what `scored-in-02` keeps)."
  [ctx scored-in-02]
  (let [ver (filter verification? every-item)
        other (remove verification? every-item)
        before (flip (all-pass every-item) (concat (take 2 ver) (take 1 other)) :fail)
        after (-> (all-pass every-item) (flip [(second other)] :fail) (select-keys (filter scored-in-02 every-item)))]
    (fixture! (assoc ctx :expect {:target {:better (vec (take 2 ver)) :worse []}
                                  :rest {:better [(first other)] :worse [(second other)]}})
              (-> (:cycles ctx)
                  (assoc-in ["01" :verdicts] before)
                  (assoc-in ["02" :verdicts] (into (sorted-map) after))))))

(defstep #"both cycles scored every scenario"
  (fn [ctx] (mixed! ctx (constantly true))))

(defn- section! [b k want]
  (let [sec (get b k)]
    (s/check! (map? sec) (str "no " (name k) " section: " (pr-str b)))
    (s/check! (= [(count (:better want)) (count (:worse want)) (:better want) (:worse want)]
                 [(:better sec) (:worse sec) (:better-ids sec) (:worse-ids sec)])
              (str (name k) ": " (pr-str sec) "\nexpected " (pr-str want)))))

(defstep #"it reports better and worse for verification's scenarios first"
  (fn [ctx]
    (let [b (board ctx)
          out (:out (mc/the-run ctx))]
      (section! b :target (get-in ctx [:expect :target]))
      (s/check! (= {:context :verification} (:scope b)) (str "scope: " (pr-str (:scope b))))
      (s/check! (< (str/index-of out ":target") (or (str/index-of out ":rest") Integer/MAX_VALUE))
                "the target is not reported first"))))

(defstep #"then, separately, better and worse for every other scenario both scored"
  (fn [ctx]
    (let [b (board ctx)]
      (section! b :rest (get-in ctx [:expect :rest]))
      (s/check! (= (count (remove verification? every-item)) (get-in b [:rest :compared])) (pr-str (:rest b))))))

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [ctx]
    (-> (fixture! ctx {"01" {:verdicts (all-pass every-item)}
                       "02" {:verdicts (all-pass every-item) :scope {:context :verification}}})
        (mixed! verification?))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx]
    (section! (board ctx) :target (get-in ctx [:expect :target]))))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx]
    (let [b (board ctx)]
      (s/check! (false? (get-in b [:rest :scored])) (str "rest: " (pr-str (:rest b))))
      (s/check! (nil? (get-in b [:rest :better])) "it counts better and worse for what cycle 02 did not score")
      (mc/says! ctx #"rest was not scored in cycle 02" "that the rest was not scored in cycle 02"))))

;; ---------------------------------------------------------------------------
;; A bootstrap cycle is never a baseline

(defstep #"cycle 00's cycle\.edn holds :bootstrap true"
  (fn [ctx]
    (fixture! ctx {"00" {:bootstrap? true} "01" {:verdicts (all-pass every-item)}})))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx]
    (let [b (board ctx)]
      (s/check! (= ["00"] (:bootstrap b)) (pr-str b))
      (mc/says! ctx #"00 is a bootstrap" "that cycle 00 is a bootstrap"))))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [b (board ctx)
          ks (atom [])]
      (walk/postwalk #(do (when (map? %) (swap! ks into (keys %))) %) b)
      (s/check! (not-any? #{:better :worse :overall :target :rest} @ks) (pr-str b)))))
