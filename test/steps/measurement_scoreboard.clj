(ns steps.measurement-scoreboard
  "Step definitions for dhf/spec/measurement/scoreboard.feature. Scored cycles
  are written by hand into a temporary project (cycle.edn, checklist.json and
  score.edn per cycle) and compared with `rdm-spec scoreboard`, whose result is
  the EDN on its standard output."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn ids [from n] (vec (for [i (range n)] (format "spec-%04d" (+ from i)))))

(defn write-score!
  "Writes cycle `cycle`'s cycle.edn and score.edn, `verdicts` {id :pass|:fail}."
  [dir cycle verdicts & [{:keys [scope] :or {scope :project}}]]
  (mc/write-cycle! dir cycle {:scope scope})
  (let [items (vec (for [[id v] (sort-by key verdicts)] {:id id :verdict v :evidence (str "graded " id)}))]
    (s/write! dir (mc/cycle-path cycle "score.edn")
              (pr-str {:cycle cycle :cut "spec-3" :scope scope :room-tree "4b825dc"
                       :builder (:builder mc/roles) :grader (:grader mc/roles) :spec-owner (:spec-owner mc/roles)
                       :items items
                       :passed (count (filter #{:pass} (vals verdicts))) :scored (count items)}))))

(defn all [id-list v] (zipmap id-list (repeat v)))

(defn result [ctx] (mc/run-result ctx))

(defn passed! [ctx]
  (s/check! (zero? (:exit (:run ctx))) (s/show (:run ctx))))

;; --- Both directions are counted, never the net -----------------------------

(defstep #"cycle 01 and cycle 02 scored over the same (\d+) items"
  (fn [ctx n]
    (let [dir (s/project!)
          xs (ids 101 (parse-long n))]
      (write-score! dir "01" (all xs :pass))
      (write-score! dir "02" (all xs :pass))
      (mc/settle (assoc ctx :dir dir :ids xs)))))

(defstep #"(\d+) items passed in 02 that failed in 01, and (\d+) failed in 02 that passed in 01"
  (fn [ctx better worse]
    (let [[b w] [(parse-long better) (parse-long worse)]
          xs (:ids ctx)
          better-ids (take b xs)
          worse-ids (take w (drop b xs))
          ;; one more item failing in both, which neither got better nor worse
          both-fail (take 1 (drop (+ b w) xs))
          v01 (merge (all xs :pass) (all better-ids :fail) (all both-fail :fail))
          v02 (merge (all xs :pass) (all worse-ids :fail) (all both-fail :fail))]
      (write-score! (:dir ctx) "01" v01)
      (write-score! (:dir ctx) "02" v02)
      (mc/settle ctx))))

(defstep #"it reports (\d+) got better and (\d+) got worse"
  (fn [ctx better worse]
    (passed! ctx)
    (let [shared (:shared (result ctx))]
      (s/check! (= [(parse-long better) (parse-long worse)] [(:better shared) (:worse shared)])
                (str "reported " (pr-str shared))))))

(defn- all-keys [x]
  (let [ks (atom [])]
    (walk/postwalk (fn [f] (when (map? f) (swap! ks into (keys f))) f) x)
    @ks))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [r (result ctx)
          netty (filter #(re-find #"net|delta|change|diff|gain" (name %)) (filter keyword? (all-keys r)))]
      (s/check! (empty? netty) (str "the result carries a net figure: " (pr-str netty)))
      (s/check! (not (re-find #"(?i)\bnet\b" (:err (:run ctx)))) (s/show (:run ctx))))))

;; --- A moved denominator is said out loud ----------------------------------

(defstep #"cycle 01 scored (\d+) items and cycle 02 scored (\d+)"
  (fn [ctx a b]
    (let [dir (s/project!)
          [a b] [(parse-long a) (parse-long b)]]
      (write-score! dir "01" (all (ids 101 a) :pass))
      ;; the shared items: one got worse; the new ones: one failed
      (write-score! dir "02" (merge (all (ids 101 b) :pass) {"spec-0101" :fail} {(format "spec-%04d" (+ 101 (dec b))) :fail}))
      (mc/settle (assoc ctx :dir dir :a a :b b)))))

(defstep #"it compares only the (\d+) items both scored"
  (fn [ctx n]
    (passed! ctx)
    (let [shared (:shared (result ctx))]
      (s/check! (= (parse-long n) (:items shared)) (str "compared " (pr-str shared)))
      (s/check! (= [0 1] [(:better shared) (:worse shared)]) (str "the new items were compared: " (pr-str shared))))))

(defstep #"it states that the denominator moved from (\d+) to (\d+)"
  (fn [ctx from to]
    (let [r (result ctx)
          d (:denominator r)]
      (s/check! (and (= (parse-long from) (:from d)) (= (parse-long to) (:to d)) (true? (:moved d)))
                (str "denominator " (pr-str d)))
      (s/check! (str/includes? (:err (:run ctx)) (str "moved from " from " to " to))
                (str "the error stream does not say it\n" (s/show (:run ctx)))))))

(defstep #"it counts the (\d+) new items separately"
  (fn [ctx n]
    (let [new (:new (result ctx))]
      (s/check! (= (parse-long n) (:items new) (count (:ids new))) (str "new " (pr-str new))))))

;; --- A scoped cycle is reported target first --------------------------------

(def verification-ids ["spec-0024" "spec-0025" "spec-0026" "spec-0027"])
(def other-ids ["spec-0012" "spec-0013" "spec-0014" "spec-0015"])

(defstep #"cycle 02 regenerated the bounded context verification and cycle 01 is its baseline"
  (fn [ctx]
    (let [dir (mc/copy-of-room-model!)
          ;; verification: 2 better, 1 worse; the rest: 1 better, 2 worse
          v01 (merge (all verification-ids :pass) (all other-ids :pass)
                     {"spec-0024" :fail "spec-0025" :fail "spec-0012" :fail})
          v02 (merge (all verification-ids :pass) (all other-ids :pass)
                     {"spec-0026" :fail "spec-0013" :fail "spec-0014" :fail})]
      (write-score! dir "01" v01)
      (write-score! dir "02" v02 {:scope {:context :verification}})
      (doseq [c ["01" "02"]]
        (s/write! dir (mc/cycle-path c "checklist.json")
                  (json/generate-string
                   {:cycle c :cut "spec-3"
                    :items (sort-by :id (concat (for [id verification-ids] {:id id :title id :context "verification" :component "Status" :level "e2e"})
                                                (for [id other-ids] {:id id :title id :context "architecture" :component "Lint" :level "e2e"})))
                    :planned []})))
      (mc/settle (assoc ctx :dir dir)))))

(defstep #"it reports better and worse for verification's scenarios first"
  (fn [ctx]
    (passed! ctx)
    (let [out (:out (:run ctx))
          r (result ctx)
          t (:target r)]
      (s/check! (and t (:rest r)) (str "no target and rest: " out))
      (s/check! (< (str/index-of out ":target") (str/index-of out ":rest")) (str "the rest is reported first:\n" out))
      (s/check! (= {:context :verification} (or (:scope t) (:scope r))) (str "the target is " (pr-str t)))
      (s/check! (= [4 2 1] [(:items t) (:better t) (:worse t)]) (str "target " (pr-str t))))))

(defstep #"then, separately, better and worse for every other scenario"
  (fn [ctx]
    (let [rest (:rest (result ctx))]
      (s/check! (= [4 1 2] [(:items rest) (:better rest) (:worse rest)]) (str "rest " (pr-str rest))))))

;; --- A bootstrap cycle is never a baseline ---------------------------------

(defstep #"cycle 00's cycle.edn holds :bootstrap true"
  (fn [ctx]
    (let [dir (s/project!)
          xs (ids 101 12)]
      ;; the bootstrap cycle was reviewed by a person: it has no score.edn
      (mc/write-cycle! dir "00" {:bootstrap true})
      (write-score! dir "01" (merge (all xs :pass) {"spec-0101" :fail}))
      (mc/settle (assoc ctx :dir dir)))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx]
    (passed! ctx)
    (let [r (result ctx)]
      (s/check! (= ["00"] (:bootstrap r)) (str "bootstrap: " (pr-str (:bootstrap r)))))))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [ks (set (all-keys (result ctx)))]
      (s/check! (not (or (ks :better) (ks :worse))) (str "it reports " (pr-str (result ctx)))))))
