(ns rdm-spec.measurement.scoreboard
  "Scoreboard (dhf/spec/measurement/scoreboard.feature): `rdm-spec scoreboard
  A B` compares cycle A (the earlier) with cycle B, for the spec owner.

  It counts what got better (failed in A, passed in B) and what got worse
  (passed in A, failed in B), only over the items both cycles scored, and
  never a net figure. A moved denominator, items scored in one cycle only,
  and a bootstrap cycle are stated in the result's :statements and as
  warnings on the error stream; none of them fails the task.

  When either cycle is scoped (its score's :scope is not :project), the
  target is the items the scoped cycle scored (B's when B is scoped, else
  A's): its checklist holds its scope and nothing else. The target is
  reported first, then the rest, compared only where both cycles scored it.

  A cycle whose cycle.edn holds :bootstrap true is marked and nothing is
  compared against it; it needs no score.edn. Any other cycle without a
  score.edn cannot be compared (exit 2).

  The result:
    {:scoreboard {:cycles [\"01\" \"02\"]
                  ; a bootstrap cycle among them:
                  :bootstrap [\"00\"]
                  ; else, two project cycles:
                  :compared n :better n :worse n :better-items [id] :worse-items [id]
                  :scored {\"01\" n \"02\" n} :only-in {\"01\" [id] \"02\" [id]}
                  ; else, a scoped cycle:
                  :target {:scope S :cycle \"02\" :compared .. :better .. :worse .. :better-items .. :worse-items ..}
                  :rest {:compared .. :better .. :worse .. :better-items .. :worse-items ..
                         :not-scored {\"02\" n}}
                  :statements [\"...\"]}}"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]))

(defn read-score
  "The cycle's score.edn, or cannot run."
  [req cycle]
  (let [rel (checklist/cycle-file req cycle "score.edn")
        s (checklist/read-edn-file req rel nil)]
    (cond
      (nil? s) (command/cannot-run! (str "no " rel ": cycle " cycle " has not been scored"))
      (not (and (map? s) (sequential? (:items s)))) (command/cannot-run! (str rel " holds no :items"))
      :else (assoc s ::file rel))))

(defn verdicts
  "{id :pass|:fail} of a score."
  [score]
  (into {} (map (juxt :id :verdict) (:items score))))

(defn compare-over
  "Better and worse over `ids`, from verdict maps `a` and `b`."
  [ids a b]
  (let [ids (checklist/id-order ids)
        better (vec (filter #(and (= :pass (get b %)) (not= :pass (get a %))) ids))
        worse (vec (filter #(and (= :pass (get a %)) (not= :pass (get b %))) ids))]
    {:compared (count ids) :better (count better) :worse (count worse)
     :better-items better :worse-items worse}))

(defn- scoped? [score] (not= :project (get score :scope :project)))

(defn compare-scores
  "The comparison of scores `sa` and `sb` of cycles `a` and `b`."
  [a sa b sb]
  (let [va (verdicts sa) vb (verdicts sb)
        ka (set (keys va)) kb (set (keys vb))
        shared (set/intersection ka kb)
        only-a (checklist/id-order (set/difference ka kb))
        only-b (checklist/id-order (set/difference kb ka))]
    (if-not (or (scoped? sa) (scoped? sb))
      (let [moved? (or (seq only-a) (seq only-b))]
        (merge {:cycles [a b]}
               (compare-over shared va vb)
               {:scored {a (count ka) b (count kb)}
                :only-in {a (vec only-a) b (vec only-b)}
                :statements
                (vec (concat
                      (when moved?
                        [(str "the denominator moved from " (count ka) " to " (count kb)
                              ": only the " (count shared) " items both cycles scored are compared")])
                      (when (seq only-b)
                        [(str (count only-b) " new items scored only in cycle " b ", counted separately: "
                              (str/join ", " only-b))])
                      (when (seq only-a)
                        [(str (count only-a) " items scored only in cycle " a ", counted separately: "
                              (str/join ", " only-a))])))}))
      (let [[tc ts] (if (scoped? sb) [b sb] [a sa])
            [oc] (if (= tc b) [a] [b])
            target (set (keys (verdicts ts)))
            other-ids (if (= tc b) ka kb)
            rest-ids (set/difference other-ids target)
            rest-shared (set/difference shared target)
            not-scored (set/difference rest-ids (if (= tc b) kb ka))]
        {:cycles [a b]
         :target (merge {:scope (:scope ts) :cycle tc}
                        (compare-over (set/intersection target shared) va vb))
         :rest (merge (compare-over rest-shared va vb)
                      {:not-scored {tc (count not-scored)}})
         :statements
         (vec (concat
               [(str "cycle " tc " is scoped to " (pr-str (:scope ts)) ": its scope is reported first")]
               (when (seq not-scored)
                 [(str "the rest was not scored in cycle " tc ": " (count not-scored) " items of cycle " oc
                       " outside the scope are not compared")])
               (let [missing (set/difference target (if (= tc b) ka kb))]
                 (when (seq missing)
                   [(str (count missing) " items of the scope were not scored in cycle " oc ", counted separately: "
                         (str/join ", " (checklist/id-order missing)))]))))}))))

(defn scoreboard
  "The scoreboard stage."
  [req]
  (let [[a b] (:args req)
        ca (checklist/read-cycle req a)
        cb (checklist/read-cycle req b)
        boot (vec (for [[n c] [[a ca] [b cb]] :when (true? (:bootstrap c))] n))
        board (if (seq boot)
                {:cycles [a b]
                 :bootstrap boot
                 :statements (vec (for [n boot]
                                    (str "cycle " n " is a bootstrap: it is never a baseline, and nothing is compared against it")))}
                (compare-scores a (read-score req a) b (read-score req b)))]
    {:result {:scoreboard board}
     :findings (vec (for [s (:statements board)]
                      (command/warning "scoreboard" s)))}))
