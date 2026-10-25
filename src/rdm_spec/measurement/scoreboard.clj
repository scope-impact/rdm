(ns rdm-spec.measurement.scoreboard
  "The Scoreboard component (dhf/spec/measurement/scoreboard.feature):
  `rdm-spec scoreboard A B` compares cycle B with cycle A, for the spec
  owner and never the builder, from their score.edn and cycle.edn.

  It counts both directions over the items both cycles scored, never the
  net; says out loud when the denominator moved; counts the items only B
  scored apart (new) and says which only A scored (not scored in B: for a
  scoped cycle, the rest of the project). It reports the compared part, the
  target, first. A cycle whose cycle.edn holds :bootstrap true is marked and
  nothing is measured against it.

  Result:

    {:scoreboard
     {:cycles [A B]
      :scope {A scope B scope}
      :compared n                              ; items both scored
      :better {:count n :items [id]}           ; :fail in A, :pass in B
      :worse {:count n :items [id]}            ; :pass in A, :fail in B
      :denominator {:from n :to n :moved bool} ; the items each scored
      :new {:cycle B :count n :items [id]}     ; scored in B only
      :not-scored {:cycle B :count n :items [id]}  ; scored in A only
      :says [sentence]}}                       ; the target first

  or, when a cycle is a bootstrap, {:scoreboard {:cycles :bootstrap [cycle]
  :says}}. A moved denominator, items not scored and a bootstrap are also
  warnings, so the error stream says them too.

  A cycle with no score.edn that is no bootstrap, or a file that is not
  readable, cannot run (exit 2)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(defn- cycle-rel [req cycle file]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle "/" file))

(defn- read-edn [req rel]
  (let [f (command/path req rel)]
    (when (fs/regular-file? f)
      (let [v (try (edn/read-string (slurp (str f)))
                   (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))]
        (when-not (map? v) (command/cannot-run! (str rel " is not an EDN map")))
        v))))

(defn- read-cycle
  "{:cycle :bootstrap? :scope :score} of `cycle`."
  [req cycle]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle's name, such as 03")))
  (let [c (read-edn req (cycle-rel req cycle "cycle.edn"))
        bootstrap? (true? (:bootstrap c))
        srel (cycle-rel req cycle "score.edn")
        sc (read-edn req srel)]
    (when (and (not bootstrap?) (nil? sc))
      (command/cannot-run! (str "no " srel ": cycle " cycle " has not been scored (rdm-spec score " cycle ")")))
    (when (and sc (not (vector? (:items sc))))
      (command/cannot-run! (str srel " holds no :items vector")))
    {:cycle cycle :bootstrap? bootstrap?
     :scope (or (:scope sc) (:scope c) :project)
     :score sc}))

(defn- verdicts [sc] (into {} (map (juxt :id :verdict)) (:items sc)))

(defn- in-order
  "The ids of `ids` in the order the score `sc` lists them."
  [sc ids]
  (let [s (set ids)] (vec (filter s (map :id (:items sc))))))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(defn compare-cycles
  "Pure: the scoreboard of cycle `b` against cycle `a` ({:cycle
  :bootstrap? :scope :score})."
  [a b]
  (let [cycles [(:cycle a) (:cycle b)]
        boot (vec (keep #(when (:bootstrap? %) (:cycle %)) [a b]))]
    (if (seq boot)
      {:cycles cycles
       :bootstrap boot
       :says (vec (for [c boot] (str "cycle " c " is a bootstrap cycle, reviewed by a person only:"
                                     " nothing is measured against it, and no better or worse is counted")))}
      (let [va (verdicts (:score a))
            vb (verdicts (:score b))
            shared (in-order (:score b) (filter va (keys vb)))
            better (in-order (:score b) (filter #(and (= :pass (vb %)) (not= :pass (va %))) shared))
            worse (in-order (:score b) (filter #(and (not= :pass (vb %)) (= :pass (va %))) shared))
            new (in-order (:score b) (remove va (keys vb)))
            unscored (in-order (:score a) (remove vb (keys va)))
            [ca cb] cycles
            moved? (not= (count va) (count vb))
            scoped (remove #(= :project (:scope %)) [a b])]
        {:cycles cycles
         :scope {ca (:scope a) cb (:scope b)}
         :compared (count shared)
         :better {:count (count better) :items better}
         :worse {:count (count worse) :items worse}
         :denominator {:from (count va) :to (count vb) :moved moved?}
         :new {:cycle cb :count (count new) :items new}
         :not-scored {:cycle cb :count (count unscored) :items unscored}
         :says (vec (remove nil?
                            [(str "over the " (plural (count shared) "item" "items") " both cycles scored"
                                  (when (seq scoped)
                                    (str ", within the scope " (str/join " and " (map #(pr-str (:scope %)) scoped))))
                                  ", " (count better) " got better and " (count worse) " got worse")
                             (when moved? (str "the denominator moved from " (count va) " to " (count vb)))
                             (when (seq new)
                               (str (plural (count new) "item was" "items were") " scored in cycle " cb
                                    " only, counted apart as new"))
                             (when (seq unscored)
                               (str (plural (count unscored) "item" "items") " scored in cycle " ca
                                    " were not scored in cycle " cb
                                    (if (not= :project (:scope b))
                                      (str ": the rest, outside its scope " (pr-str (:scope b)))
                                      "")))]))}))))

(defn scoreboard
  "The scoreboard stage."
  [req]
  (let [[a b] (:args req)
        sb (compare-cycles (read-cycle req a) (read-cycle req b))
        says (:says sb)
        loud (if (:bootstrap sb)
               (map #(vector "cycle-bootstrap" %) says)
               (remove nil?
                       [(when (get-in sb [:denominator :moved])
                          ["denominator-moved" (first (filter #(str/starts-with? % "the denominator") says))])
                        (when (pos? (get-in sb [:new :count]))
                          ["items-new" (first (filter #(str/includes? % "counted apart as new") says))])
                        (when (pos? (get-in sb [:not-scored :count]))
                          ["items-not-scored" (first (filter #(str/includes? % "were not scored") says))])]))]
    {:result {:scoreboard sb}
     :findings (vec (for [[rule s] loud] (command/warning rule s)))}))
