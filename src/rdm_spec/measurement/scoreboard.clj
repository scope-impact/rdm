(ns rdm-spec.measurement.scoreboard
  "The scoreboard (dhf/spec/measurement/scoreboard.feature): two cycles
  compared over the items both scored, better and worse counted apart and
  never netted, a moved denominator said aloud, a scoped cycle's target
  reported first, and nothing measured against a bootstrap cycle."
  (:require [rdm-spec.kernel.command :as cmd]))

(defn- verdicts [score] (into {} (map (juxt :id :verdict) (:items score))))

(defn- diff [va vb ids]
  (let [better (sort (filter #(and (= :fail (va %)) (= :pass (vb %))) ids))
        worse (sort (filter #(and (= :pass (va %)) (= :fail (vb %))) ids))]
    {:compared (count ids) :better (count better) :worse (count worse)
     :better-items (vec better) :worse-items (vec worse)}))

(defn compare-scores [a sa b sb]
  (let [va (verdicts sa) vb (verdicts sb)
        both (filter vb (keys va))
        scope (:scope sb)]
    (if (and scope (not= :project scope))
      (let [rest-a (remove vb (keys va))]
        {:cycles [a b]
         :target (merge {:scope scope :cycle b} (diff va vb both))
         :rest (assoc (diff va vb []) :not-scored {b (count rest-a)})
         :statements [(str "cycle " b " is scoped to " (pr-str scope) ": its scope is reported first")
                      (str "the rest was not scored in cycle " b ": " (count rest-a) " items of cycle " a
                           " outside the scope are not compared")]})
      (let [only-a (vec (sort (remove vb (keys va))))
            only-b (vec (sort (remove va (keys vb))))]
        (merge {:cycles [a b] :scored {a (count va) b (count vb)}}
               (diff va vb both)
               {:only-in {a only-a b only-b}
                :statements (vec (concat
                                  (when (not= (count va) (count vb))
                                    [(str "the denominator moved from " (count va) " to " (count vb))])
                                  (when (seq only-b)
                                    [(str (count only-b) " items new in cycle " b " are counted separately, not compared")])
                                  (when (seq only-a)
                                    [(str (count only-a) " items of cycle " a " were not scored in cycle " b)])))})))))

(defn stage [{:keys [root config args]}]
  (let [[a b] args
        dir #(str (:cycles-dir config "dhf/cycles") "/" %)
        read (fn [c f] (try (cmd/read-edn (cmd/read-at root nil (str (dir c) "/" f))) (catch Exception _ nil)))
        score (fn [c] (or (read c "score.edn") (cmd/cannot-run! (str "cycle " c " has no score: " (dir c) "/score.edn"))))
        sa (score a) sb (score b)
        boot (vec (filter #(:bootstrap (read % "cycle.edn")) [a b]))]
    {:result
     {:scoreboard
      (if (seq boot)
        {:cycles [a b] :bootstrap boot :scored {a (count (:items sa)) b (count (:items sb))}
         :statements (mapv #(str "cycle " % " is a bootstrap: nothing is measured against it") boot)}
        (compare-scores a sa b sb))}}))
