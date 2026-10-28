(ns rdm-spec.measurement.scoreboard
  "Compares two cycles over the items both scored: better and worse, never a
  net; a moved denominator said aloud; a scoped cycle's target first; a
  bootstrap never a baseline (dhf/spec/07-measure/scoreboard.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [rdm-spec.kernel.project :as project]))

(defn compare-items [a b]
  (let [shared (sort (filter #(contains? b %) (keys a)))
        better (vec (for [id shared :when (and (= :fail (a id)) (= :pass (b id)))] id))
        worse (vec (for [id shared :when (and (= :pass (a id)) (= :fail (b id)))] id))]
    {:compared (count shared) :better (count better) :worse (count worse) :better-items better :worse-items worse}))

(defn bootstraps [cycles] (vec (sort (for [[n c] cycles :when (:bootstrap c)] n))))

(defn scoped-statements [a b scope not-scored]
  [(str "cycle " b " is scoped to " (project/edn-str scope) ": its scope is reported first")
   (str "the rest was not scored in cycle " b ": " not-scored " items of cycle " a " outside the scope are not compared")])

(defn whole [an bn a b]
  (let [c (compare-items a b)
        only-a (vec (sort (remove #(contains? b %) (keys a))))
        only-b (vec (sort (remove #(contains? a %) (keys b))))]
    (merge {:cycles [an bn] :scored {an (count a) bn (count b)}}
           c
           {:only-in {an only-a bn only-b}
            :statements (if (not= (count a) (count b))
                          [(str "the denominator moved from " (count a) " to " (count b) ": " (count only-b) " items new in cycle " bn
                                " and " (count only-a) " items only in cycle " an " are counted apart")]
                          [])})))

(defn- read-score [root cfg n]
  (let [f (fs/path root (:cycles-dir cfg) n "score.edn")]
    (when-not (fs/exists? f) (project/cannot-run! (str "cycle " n " has no score.edn: it was not scored")))
    (edn/read-string (slurp (str f)))))

(defn- read-cycle [root cfg n]
  (let [f (fs/path root (:cycles-dir cfg) n "cycle.edn")]
    (when (fs/exists? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))))

(defn scoreboard [run]
  (let [root (:root run) cfg (:cfg run)
        [an bn] (:args run)
        sa (read-score root cfg an) sb (read-score root cfg bn)
        boot (bootstraps {an (read-cycle root cfg an) bn (read-cycle root cfg bn)})
        verdicts (fn [s] (into {} (for [i (:items s)] [(:id i) (:verdict i)])))
        a (verdicts sa) b (verdicts sb)
        scope (:scope sb :project)]
    {:result
     {:scoreboard
      (cond
        (seq boot) {:cycles [an bn] :bootstrap boot
                    :statements (vec (for [n boot] (str "cycle " n " is a bootstrap: nothing is measured against it")))}
        (not= :project scope)
        (let [c (compare-items a b)
              not-scored (count (remove #(contains? b %) (keys a)))]
          {:cycles [an bn]
           :target (merge {:scope scope :cycle bn} c)
           :rest {:compared 0 :better 0 :worse 0 :better-items [] :worse-items [] :not-scored {bn not-scored}}
           :statements (scoped-statements an bn scope not-scored)})
        :else (whole an bn a b))}}))
