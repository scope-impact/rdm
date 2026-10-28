(ns rdm-spec.measurement.scoreboard
  "Component Scoreboard (measurement context): compares two cycles' filed
  scores over the items both scored, counting what got better and what got
  worse, never a net; a moved denominator said aloud, new items apart; a
  scoped cycle reported target first; a bootstrap cycle never a baseline."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(defn read-cycle [cfg n]
  (let [d (str (:cycles-dir cfg) "/" n)
        s (project/read-edn-file (project/root) (str d "/score.edn"))
        c (project/read-edn-file (project/root) (str d "/cycle.edn"))]
    (when (or (:missing s) (:error s))
      (project/cannot-run! (str "cycle " n " has no readable " d "/score.edn: a cycle is compared only once scored")))
    {:n n :score (:value s) :cycle (when (map? (:value c)) (:value c))}))

(defn verdicts [s] (into {} (for [i (:items (:score s))] [(:id i) (:verdict i)])))

(defn compare-items [ids va vb]
  (let [better (vec (sort (filter #(and (= :fail (va %)) (= :pass (vb %))) ids)))
        worse (vec (sort (filter #(and (= :pass (va %)) (= :fail (vb %))) ids)))]
    {:compared (count ids) :better (count better) :worse (count worse) :better-items better :worse-items worse}))

(defn scoped? [c] (let [s (get-in c [:cycle :scope] :project)] (not= :project s)))

(defn scoreboard-task [{:keys [cfg args]}]
  (let [[na nb] args
        a (read-cycle cfg na)
        b (read-cycle cfg nb)
        boot (vec (for [c [a b] :when (get-in c [:cycle :bootstrap])] (:n c)))
        va (verdicts a) vb (verdicts b)
        ids-a (set (keys va)) ids-b (set (keys vb))
        shared (sort (filter ids-a ids-b))]
    (project/report
     {:scoreboard
      (cond
        (seq boot)
        {:cycles [na nb] :bootstrap boot :scored {na (count va) nb (count vb)}
         :statements (vec (for [n boot] (str "cycle " n " is a bootstrap cycle: it is never a baseline, and nothing is compared against it")))}

        (scoped? b)
        (let [scope (get-in b [:cycle :scope])
              target (sort ids-b)
              rest-ids (filter #(not (ids-b %)) shared)
              not-scored (count (remove ids-b ids-a))]
          {:cycles [na nb]
           :target (merge {:scope scope :cycle nb} (compare-items (filter ids-a target) va vb))
           :rest (assoc (compare-items rest-ids va vb) :not-scored {nb not-scored})
           :statements (vec (concat [(str "cycle " nb " is scoped to " (pr-str scope) ": its scope is reported first")]
                                    (when (pos? not-scored)
                                      [(str "the rest was not scored in cycle " nb ": " not-scored " item" (when (not= 1 not-scored) "s")
                                            " of cycle " na " outside the scope " (if (= 1 not-scored) "is" "are") " not compared")])))})

        :else
        (merge {:cycles [na nb] :scored {na (count va) nb (count vb)}}
               (compare-items shared va vb)
               {:only-in {na (vec (sort (remove ids-b ids-a))) nb (vec (sort (remove ids-a ids-b)))}
                :statements (vec (concat
                                  (when (not= (count va) (count vb))
                                    [(str "the denominator moved from " (count va) " to " (count vb) ": only the "
                                          (count shared) " items both scored are compared")])
                                  (when (seq (remove ids-a ids-b))
                                    [(str (count (remove ids-a ids-b)) " items are new in cycle " nb ", counted apart")])
                                  (when (seq (remove ids-b ids-a))
                                    [(str (count (remove ids-b ids-a)) " items of cycle " na " are not in cycle " nb ", counted apart")])))}))})))
