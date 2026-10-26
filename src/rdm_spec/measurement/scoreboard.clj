(ns rdm-spec.measurement.scoreboard
  "The Scoreboard component (dhf/spec/measurement/scoreboard.feature):
  `rdm-spec scoreboard A B` compares two filed scores for the spec owner,
  honestly: what got better and what got worse (A to B), counted apart and
  never as a net, over the items both scored; a moved denominator and the
  items only one cycle scored are said aloud.

  It reads each cycle's score.edn (the scorer's) and cycle.edn (for
  :bootstrap; a cycle's scope is its score's :scope, else its cycle.edn's,
  else the project). An item passed when its verdict is :pass; an ungraded
  item was filed as a fail, and counts as one.

  The result, on standard output, in one of three shapes, each with
  :cycles [A B] first and :statements last:

    whole project (spec-0283):
      {:scoreboard {:cycles :scored {A n B n} :compared :better :worse
                    :better-items :worse-items :only-in {A [id] B [id]}
                    :statements}}
    a scoped cycle (spec-0284): the scoped cycle's scope is the target (B's
    when both are scoped), everything else the rest:
      {:scoreboard {:cycles
                    :target {:scope :cycle :compared :better :worse
                             :better-items :worse-items}
                    :rest {:compared :better :worse :better-items
                           :worse-items :not-scored {cycle n}}
                    :statements}}
      :not-scored counts, per cycle that left some, the items of the rest
      the other cycle scored and it did not.
    a bootstrap cycle (spec-0192): never a baseline, so nothing is counted:
      {:scoreboard {:cycles :bootstrap [cycle] :scored {cycle n} :statements}}
      :scored names only the cycles that were scored.

  Cannot run (exit 2): a cycle name that is not one, the same cycle twice,
  a cycle with no score.edn (a bootstrap cycle needs none), an unreadable
  score.edn or cycle.edn."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Pure: comparing

(defn- passed? [item] (= :pass (:verdict item)))

(defn- by-id [items] (into {} (map (juxt :id identity)) items))

(defn- scope-of [{:keys [score cycle]}]
  (let [s (if (contains? score :scope) (:scope score) (:scope cycle))]
    (if (nil? s) :project s)))

(defn- scoped? [c] (not= :project (scope-of c)))

(defn- bootstrap? [c] (true? (get-in c [:cycle :bootstrap])))

(defn- direction
  "{:compared :better :worse :better-items :worse-items} over `ids`, A to B."
  [a-items b-items ids]
  (let [better (sort (filter #(and (not (passed? (a-items %))) (passed? (b-items %))) ids))
        worse (sort (filter #(and (passed? (a-items %)) (not (passed? (b-items %)))) ids))]
    (array-map :compared (count ids) :better (count better) :worse (count worse)
               :better-items (vec better) :worse-items (vec worse))))

(defn- n-items
  "\"1 item\" or \"N items\"."
  [n]
  (str n (if (= 1 n) " item" " items")))

(defn- whole
  [{a :name :as ca} {b :name :as cb}]
  (let [ai (by-id (get-in ca [:score :items]))
        bi (by-id (get-in cb [:score :items]))
        shared (sort (filter bi (keys ai)))
        only-a (vec (sort (remove bi (keys ai))))
        only-b (vec (sort (remove ai (keys bi))))
        d (direction ai bi shared)
        statements (concat
                    (when (or (seq only-a) (seq only-b))
                      [(str "the denominator moved from " (count ai) " to " (count bi) ": only the "
                            (n-items (count shared)) " both scored "
                            (if (= 1 (count shared)) "is" "are") " compared")])
                    (when (seq only-a)
                      [(str (n-items (count only-a)) (if (= 1 (count only-a)) " was" " were")
                            " scored only in cycle " a ": " (if (= 1 (count only-a)) "it is" "they are")
                            " not compared")])
                    (when (seq only-b)
                      [(str (n-items (count only-b)) (if (= 1 (count only-b)) " was" " were")
                            " scored only in cycle " b ": " (if (= 1 (count only-b)) "it is" "they are")
                            " counted apart, not compared")]))]
    (array-map :cycles [a b]
               :scored (array-map a (count ai) b (count bi))
               :compared (:compared d) :better (:better d) :worse (:worse d)
               :better-items (:better-items d) :worse-items (:worse-items d)
               :only-in (array-map a only-a b only-b)
               :statements (vec statements))))

(defn- scoped
  [{a :name :as ca} {b :name :as cb}]
  (let [ai (by-id (get-in ca [:score :items]))
        bi (by-id (get-in cb [:score :items]))
        [t other] (if (scoped? cb) [cb ca] [ca cb])
        tn (:name t)
        on (:name other)
        target-ids (set (map :id (get-in t [:score :items])))
        other-ids (set (map :id (get-in other [:score :items])))
        t-shared (sort (filter other-ids target-ids))
        t-only (sort (remove other-ids target-ids))
        rest-ids (remove target-ids (distinct (concat (keys ai) (keys bi))))
        r-shared (sort (filter #(and (ai %) (bi %)) rest-ids))
        not-scored (into (array-map)
                         (for [[nm own] [[a ai] [b bi]]
                               :let [n (count (remove own rest-ids))]
                               :when (pos? n)]
                           [nm n]))
        td (direction ai bi t-shared)
        rd (direction ai bi r-shared)
        statements (concat
                    [(str "cycle " tn " is scoped to " (pr-str (scope-of t)) ": its scope is reported first")]
                    (when (seq t-only)
                      [(str (n-items (count t-only)) " of the scope " (if (= 1 (count t-only)) "was" "were")
                            " scored only in cycle " tn ", not compared: " (str/join ", " t-only))])
                    (for [[nm n] not-scored]
                      (str "the rest was not scored in cycle " nm ": " (n-items n) " of cycle "
                           (if (= nm a) b a) " outside the scope " (if (= 1 n) "is" "are") " not compared")))]
    (array-map :cycles [a b]
               :target (merge (array-map :scope (scope-of t) :cycle tn) td)
               :rest (assoc rd :not-scored not-scored)
               :statements (vec statements))))

(defn- bootstrap
  [{a :name :as ca} {b :name :as cb}]
  (let [boots (filterv bootstrap? [ca cb])]
    (array-map :cycles [a b]
               :bootstrap (mapv :name boots)
               :scored (into (array-map) (for [c [ca cb] :when (:score c)]
                                           [(:name c) (count (get-in c [:score :items]))]))
               :statements (mapv #(str "cycle " (:name %) " is a bootstrap cycle: it is never a baseline,"
                                       " and nothing is compared against it")
                                 boots))))

(defn compare-cycles
  "Pure: the scoreboard (the value of :scoreboard) of cycle `a` against
  cycle `b`, each {:name :score score.edn-or-nil :cycle cycle.edn-or-nil}."
  [a b]
  (cond
    (or (bootstrap? a) (bootstrap? b)) (bootstrap a b)
    (or (scoped? a) (scoped? b)) (scoped a b)
    :else (whole a b)))

;; ---------------------------------------------------------------------------
;; Reading cycles

(defn- cycle-rel [req cycle file]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle "/" file))

(defn- read-edn-file [req rel]
  (let [f (command/path req rel)]
    (when (fs/regular-file? f)
      (let [v (try (edn/read-string (slurp (str f)))
                   (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))]
        (when-not (map? v) (command/cannot-run! (str rel " is not an EDN map")))
        v))))

(defn- read-cycle [req nm]
  (when-not (and (string? nm) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" nm))
    (command/cannot-run! (str "the cycle " (pr-str nm) " is not a cycle's name, such as 03")))
  (let [c {:name nm
           :cycle (read-edn-file req (cycle-rel req nm "cycle.edn"))
           :score (read-edn-file req (cycle-rel req nm "score.edn"))}]
    (when (and (nil? (:score c)) (not (bootstrap? c)))
      (command/cannot-run! (str "no " (cycle-rel req nm "score.edn") ": cycle " nm
                                " has not been scored (rdm-spec score " nm " --verdicts FILE)")))
    (when (and (:score c) (not (vector? (get-in c [:score :items]))))
      (command/cannot-run! (str (cycle-rel req nm "score.edn") " holds no :items vector")))
    c))

;; ---------------------------------------------------------------------------
;; The stage

(defn scoreboard
  "The scoreboard stage: compares the two cycles named."
  [req]
  (let [[a b] (:args req)]
    (when (= a b)
      (command/cannot-run! (str "the scoreboard compares two cycles, not cycle " a " with itself")))
    {:result {:scoreboard (compare-cycles (read-cycle req a) (read-cycle req b))}
     :findings []}))
