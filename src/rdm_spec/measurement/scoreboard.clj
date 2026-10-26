(ns rdm-spec.measurement.scoreboard
  "The Scoreboard component (dhf/spec/measurement/scoreboard.feature):
  `rdm-spec scoreboard A B` compares the filed scores of two cycles, for the
  spec owner, never the builder. It counts both directions over the items
  both cycles scored, never a net; a moved denominator is said out loud and
  the items one cycle alone scored are counted apart; a scoped cycle is
  reported target first, the rest apart; a bootstrap cycle is never a
  baseline.

  It reads each cycle's score.edn (its :scope and :items) and cycle.edn
  (:bootstrap). An item is better when it failed in A and passed in B, worse
  when it passed in A and failed in B. Item lists are sorted by id.

  Its result, exactly (spec-0283, spec-0284):

    two cycles of the whole project:
      {:scoreboard {:cycles [A B] :scored {A n B n} :compared n
                    :better n :worse n :better-items [id] :worse-items [id]
                    :only-in {A [id] B [id]} :statements [text]}}
    a scoped cycle (B, else A, when its :scope is not :project):
      {:scoreboard {:cycles [A B]
                    :target {:scope S :cycle C :compared n :better n :worse n
                             :better-items [id] :worse-items [id]}
                    :rest {:compared n :better n :worse n :better-items [id]
                           :worse-items [id] :not-scored {C n}}
                    :statements [text]}}
    a bootstrap cycle:
      {:scoreboard {:cycles [A B] :bootstrap [C] :statements [text]}}

  A scoped cycle's checklist holds its scope only, so every item it scored
  is its target's, and the rest is what the other cycle scored outside it,
  not compared.

  Cannot run (exit 2): a cycle with no score.edn (a bootstrap cycle aside),
  or one that is not readable EDN."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Pure

(defn- verdicts [score] (into {} (for [it (:items score) :when (map? it)] [(:id it) (:verdict it)])))

(defn- directions
  "{:compared :better :worse :better-items :worse-items} over the `shared` ids."
  [va vb shared]
  (let [better (sort (filter #(and (= :fail (va %)) (= :pass (vb %))) shared))
        worse (sort (filter #(and (= :pass (va %)) (= :fail (vb %))) shared))]
    {:compared (count shared) :better (count better) :worse (count worse)
     :better-items (vec better) :worse-items (vec worse)}))

(defn- scoped? [score] (let [s (:scope score)] (and (some? s) (not= :project s))))

(defn- show [x] (binding [*print-namespace-maps* false] (pr-str x)))

(defn compare-cycles
  "Pure: the scoreboard of the cycles [a b], each {:cycle cycle.edn :score
  score.edn} in `cycles`."
  [[a b] cycles]
  (let [boot (vec (filter #(get-in cycles [% :cycle :bootstrap]) [a b]))]
    (if (seq boot)
      {:cycles [a b] :bootstrap boot
       :statements (vec (for [c boot] (str "cycle " c " is a bootstrap cycle: it is never a baseline,"
                                           " so nothing is compared against it")))}
      (let [sa (get-in cycles [a :score]) sb (get-in cycles [b :score])
            va (verdicts sa) vb (verdicts sb)
            ida (set (keys va)) idb (set (keys vb))
            shared (filter idb ida)]
        (if-let [t (cond (scoped? sb) b (scoped? sa) a)]
          (let [other (if (= t b) a b)
                [vt vo] (if (= t b) [vb va] [va vb])
                not-scored (count (remove (set (keys vt)) (keys vo)))
                scope (get-in cycles [t :score :scope])]
            {:cycles [a b]
             :target (merge {:scope scope :cycle t} (directions va vb shared))
             :rest (assoc (directions va vb []) :not-scored {t not-scored})
             :statements (vec (concat
                               [(str "cycle " t " is scoped to " (show scope) ": its scope is reported first")]
                               (when (pos? not-scored)
                                 [(str "the rest was not scored in cycle " t ": " not-scored " items of cycle " other
                                       " outside the scope are not compared")])))})
          (let [only-a (vec (sort (remove idb ida)))
                only-b (vec (sort (remove ida idb)))]
            (merge {:cycles [a b] :scored {a (count ida) b (count idb)}}
                   (directions va vb shared)
                   {:only-in {a only-a b only-b}
                    :statements (vec (concat
                                      (when (not= (count ida) (count idb))
                                        [(str "the denominator moved from " (count ida) " to " (count idb)
                                              ": only the " (count shared) " items both cycles scored are compared")])
                                      (when (seq only-b)
                                        [(str (count only-b) " items are new in cycle " b ": counted apart, never compared")])
                                      (when (seq only-a)
                                        [(str (count only-a) " items of cycle " a " were not scored in cycle " b
                                              ": counted apart, never compared")])))})))))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- read-edn [req rel]
  (let [f (command/path req rel)]
    (when (fs/regular-file? f)
      (try (edn/read-string (slurp (str f)))
           (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e))))))))

(defn scoreboard
  "The scoreboard stage: `rdm-spec scoreboard A B`."
  [req]
  (let [dir (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "")
        [a b] (:args req)
        cycles (into {}
                     (for [c [a b]
                           :let [cyc (read-edn req (str dir "/" c "/cycle.edn"))
                                 score-rel (str dir "/" c "/score.edn")
                                 score (read-edn req score-rel)]]
                       (do
                         (when (and (nil? score) (not (:bootstrap cyc)))
                           (command/cannot-run! (str "there is no " score-rel ": cycle " c " has no filed score to compare")))
                         (when (and score (not (map? score)))
                           (command/cannot-run! (str score-rel " is not a score, a map")))
                         [c {:cycle cyc :score score}])))]
    {:result {:scoreboard (compare-cycles [a b] cycles)} :findings []}))
