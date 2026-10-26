(ns steps.measurement-scoreboard
  "Step definitions for dhf/spec/measurement/scoreboard.feature. The command
  is the kernel's shared When (\"rdm-spec scoreboard NN MM\" is run), run in
  the project these Givens make (:dir): only the configuration, and each
  cycle's cycle.edn and score.edn as the scorer writes it.

  Context keys: :dir, :scores {\"NN\" [{:id :verdict}]} (the items each
  cycle scored), :scopes {\"NN\" scope}, :new-items, :rest-ids."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def ^:private tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn- item [id verdict] {:id id :verdict verdict :evidence (str id " was graded " (name verdict))})

(defn- write-score!
  "Writes cycle `nn`'s cycle.edn (the default cycle, with `overrides`) and
  its score.edn over `items`."
  [dir nn items overrides]
  (let [c (mc/write-cycle! dir nn (merge {:cut (str "spec-" (parse-long nn))} overrides))]
    (mc/write-edn! dir (mc/cycle-path nn "score.edn")
                   (merge {:cycle nn :cut (:cut c) :scope (or (:scope c) :project) :room-tree tree}
                          (select-keys c [:builder :grader :spec-owner])
                          {:items (vec items)
                           :passed (count (filter #(= :pass (:verdict %)) items))
                           :scored (count items)}))))

(defn- write-scores!
  "ctx with every cycle of :scores written, scoped as :scopes and marked
  as :bootstrap says."
  [ctx]
  (let [dir (or (:dir ctx) (s/project!))]
    (doseq [[nn items] (:scores ctx)]
      (write-score! dir nn items (cond-> {}
                                   (get-in ctx [:scopes nn]) (assoc :scope (get-in ctx [:scopes nn]))
                                   (contains? (:bootstrap ctx) nn) (assoc :bootstrap true))))
    (assoc ctx :dir dir)))

(defn- all-pass [ids] (mapv #(item % :pass) ids))

(defn- ids [from n] (mapv :id (mc/numbered from n)))

(defn- board
  "The scoreboard the last run printed; the run must have passed."
  [ctx]
  (let [r (kc/exits! ctx 0)
        out (kc/stdout-edn r)]
    (s/check! (map? (:scoreboard out)) (str "no :scoreboard on standard output\n" (s/show r)))
    (:scoreboard out)))

(defn- statement-saying! [sb text]
  (s/check! (some #(str/includes? % text) (:statements sb))
            (str "no statement says \"" text "\": " (pr-str (:statements sb)))))

(defn- every-key [x]
  (let [ks (atom #{})]
    (walk/postwalk (fn [v] (when (map? v) (swap! ks into (keys v))) v) x)
    @ks))

;; ---------------------------------------------------------------------------
;; The scoreboard compares cycles honestly

(defstep #"cycle 01 and cycle 02 scored over the same 12 items"
  (fn [ctx]
    (write-scores! (assoc ctx :scores {"01" (all-pass (ids 101 12)) "02" (all-pass (ids 101 12))}))))

(defstep #"3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01"
  (fn [ctx]
    (let [better (set (ids 101 3))
          worse #{(format "spec-%04d" 112)}
          verdict-in (fn [nn id] (cond (better id) (if (= nn "01") :fail :pass)
                                       (worse id) (if (= nn "01") :pass :fail)
                                       :else :pass))]
      (write-scores! (assoc ctx
                            :scores (into {} (for [nn ["01" "02"]]
                                               [nn (mapv #(item % (verdict-in nn %)) (ids 101 12))]))
                            :better (sort better) :worse (sort worse))))))

(defstep #"it reports 3 got better and 1 got worse"
  (fn [ctx]
    (let [sb (board ctx)]
      (s/check! (= [3 1] [(:better sb) (:worse sb)]) (str "better " (:better sb) ", worse " (:worse sb)))
      (s/check! (= [(vec (:better ctx)) (vec (:worse ctx))] [(:better-items sb) (:worse-items sb)])
                (str "better " (pr-str (:better-items sb)) ", worse " (pr-str (:worse-items sb))))
      (s/check! (= 12 (:compared sb)) (str "compared " (:compared sb))))))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [sb (board ctx)
          netty (filter #(re-find #"net" (name %)) (every-key sb))]
      (s/check! (empty? netty) (str "the scoreboard holds " (pr-str netty)))
      (s/check! (not-any? #(re-find #"\bnet\b" %) (:statements sb)) (str "a statement gives a net: " (pr-str (:statements sb)))))))

(defstep #"cycle 01 scored 12 items and cycle 02 scored 15"
  (fn [ctx]
    (write-scores! (assoc ctx :scores {"01" (all-pass (ids 101 12)) "02" (all-pass (ids 101 15))}
                          :new-items (ids 113 3)))))

(defstep #"it compares only the 12 items both scored"
  (fn [ctx]
    (let [sb (board ctx)]
      (s/check! (= 12 (:compared sb)) (str "compared " (:compared sb)))
      (s/check! (= {"01" 12 "02" 15} (:scored sb)) (str "scored " (pr-str (:scored sb)))))))

(defstep #"it states that the denominator moved from 12 to 15"
  (fn [ctx] (statement-saying! (board ctx) "the denominator moved from 12 to 15")))

(defstep #"it counts the 3 new items separately"
  (fn [ctx]
    (let [sb (board ctx)]
      (s/check! (= {"01" [] "02" (:new-items ctx)} (:only-in sb)) (str "only in: " (pr-str (:only-in sb))))
      (statement-saying! sb "3 items were scored only in cycle 02"))))

;; ---------------------------------------------------------------------------
;; A scoped cycle is reported target first

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [ctx]
    (let [all (ids 101 6)
          verification (subvec all 3)]
      (write-scores! (assoc ctx
                            :scores {"01" (mapv #(item % (if (= % (first verification)) :fail :pass)) all)
                                     "02" (all-pass verification)}
                            :scopes {"02" {:context :verification}}
                            :target-ids verification)))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx]
    (let [{:keys [target] :as sb} (board ctx)]
      (s/check! (map? target) (str "no :target: " (pr-str sb)))
      (s/check! (= {:scope {:context :verification} :cycle "02" :compared 3 :better 1 :worse 0
                    :better-items [(first (:target-ids ctx))] :worse-items []}
                   target)
                (str "the target is " (pr-str target))))))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx]
    (let [sb (board ctx)]
      (statement-saying! sb "the rest was not scored in cycle 02")
      (s/check! (= {"02" 3} (get-in sb [:rest :not-scored])) (str "the rest is " (pr-str (:rest sb)))))))

;; ---------------------------------------------------------------------------
;; A bootstrap cycle is never a baseline

(defstep #"cycle 00's cycle\.edn holds :bootstrap true"
  (fn [ctx]
    (write-scores! (assoc ctx
                          :scores {"00" (mapv #(item % :fail) (ids 101 3)) "01" (all-pass (ids 101 3))}
                          :bootstrap #{"00"}))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx]
    (let [sb (board ctx)]
      (s/check! (= ["00"] (:bootstrap sb)) (str ":bootstrap is " (pr-str (:bootstrap sb))))
      (s/check! (= ["00" "01"] (:cycles sb)) (str ":cycles is " (pr-str (:cycles sb))))
      (statement-saying! sb "cycle 00 is a bootstrap cycle"))))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [sb (board ctx)
          counted (filter #{:better :worse :better-items :worse-items :compared} (every-key sb))]
      (s/check! (empty? counted) (str "the scoreboard counts " (pr-str counted) ": " (pr-str sb))))))

;; ---------------------------------------------------------------------------
;; The scoreboard's result has one shape

(defstep #"cycle 01 and cycle 02 each scored 5 items of the whole project, 1 better and 1 worse"
  (fn [ctx]
    (let [five (ids 1 5)]
      (write-scores! (assoc ctx :scores {"01" (mapv #(item % (if (= % "spec-0002") :fail :pass)) five)
                                         "02" (mapv #(item % (if (= % "spec-0001") :fail :pass)) five)})))))

(defstep #"cycle 02 scored 5 items of the whole project and cycle 03, scoped to the component shop/till, scored 2 of them, 1 better"
  (fn [ctx]
    (let [five (ids 1 5)]
      (write-scores! (assoc ctx
                            :scores {"02" (mapv #(item % (if (= % "spec-0005") :fail :pass)) five)
                                     "03" (all-pass ["spec-0004" "spec-0005"])}
                            :scopes {"03" {:component :shop/till}})))))

(defn- read-all
  "Every EDN form of `text`, in order."
  [text]
  (let [r (java.io.PushbackReader. (java.io.StringReader. (str text)))]
    (loop [acc []]
      (let [x (edn/read {:eof ::eof} r)]
        (if (= ::eof x) acc (recur (conj acc x)))))))

(defstep #"standard output is exactly:"
  (fn [ctx doc]
    (let [r (kc/exits! ctx 0)
          got (try (read-all (:out r)) (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e)))))
          want (edn/read-string doc)]
      (s/check! (= [want] got) (str "standard output is\n" (:out r) "not\n" (pr-str want))))))
