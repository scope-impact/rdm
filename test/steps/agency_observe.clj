(ns steps.agency-observe
  "Steps of the component Observe (07-measure/observe.feature). W1, T and ROOM
  are directories the scenario makes beside its project."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn runs [ctx n] (some-> (k/slurp-in (:dir ctx) (str "dhf/cycles/" n "/runs.edn")) edn/read-string))
(defn last-run [ctx] (last (runs ctx "13")))

(defstep #"a project whose configuration watches W1 and whose temporary directory is T"
  (fn [_]
    (let [ctx (k/new-project)
          w1 (k/path (:top ctx) "w1") t (k/path (:top ctx) "t") room (k/path (:top ctx) "room")]
      (doseq [d [w1 t room]] (fs/create-dirs d))
      (k/edit-config! (:dir ctx) #(assoc % :agency {:watch [w1] :tmp t}))
      (assoc ctx :vars {"W1" w1 "T" t "ROOM" room}))))

(defstep #"\"rdm-spec observe begin 13 (builder|grader) ROOM\" has been run"
  (fn [ctx role] (let [r (k/rdm! ctx (str "observe begin 13 " role " ROOM"))] (k/exits! r 0) (dissoc r :runs))))

(defstep #"dhf/cycles/13/runs.edn holds one more entry, read as EDN, with exactly the keys :role, :room, :began, :ended, :minutes, :tokens and :defence"
  (fn [ctx]
    (let [rs (runs ctx "13")]
      (k/expect! (= 1 (count rs)) ctx "not one entry")
      (k/expect! (= #{:role :room :began :ended :minutes :tokens :defence} (set (keys (first rs)))) ctx (str "the keys are " (keys (first rs))))
      (k/expect! (= 1200 (:tokens (first rs))) ctx "the tokens are not 1200"))
    ctx))

(defstep #"its :defence holds exactly the keys :writes-outside, :tmp-left, :repo-touched, :results-in-room and :unobserved"
  (fn [ctx]
    (k/expect! (= #{:writes-outside :tmp-left :repo-touched :results-in-room :unobserved} (set (keys (:defence (last-run ctx))))) ctx "not those keys")
    ctx))

(defstep #"a file W1/stray.txt is written"
  (fn [ctx] (k/write! (get-in ctx [:vars "W1"]) "stray.txt" "stray\n") ctx))

(defstep #"it exits 0 with a warning naming W1/stray.txt"
  (fn [ctx] (k/exits! ctx 0) (k/line-has! ctx "warning" (k/path (get-in ctx [:vars "W1"]) "stray.txt")) ctx))

(defstep #"the run's :defence :writes-outside holds W1/stray.txt"
  (fn [ctx] (k/expect! (some #{(k/path (get-in ctx [:vars "W1"]) "stray.txt")} (get-in (last-run ctx) [:defence :writes-outside])) ctx "not recorded") ctx))

(defstep #"three entries are made in T"
  (fn [ctx] (doseq [i (range 3)] (k/write! (get-in ctx [:vars "T"]) (str "left-" i) "x")) ctx))

(defstep #"the run's :defence :tmp-left is 3"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= 3 (get-in (last-run ctx) [:defence :tmp-left])) ctx "not 3") ctx))

(defstep #"an untracked file dhf/spec/stray.feature is written in the project"
  (fn [ctx] (k/write! (:dir ctx) "dhf/spec/stray.feature" "Feature: stray\n") ctx))

(defstep #"the run's :defence :repo-touched holds dhf/spec/stray.feature"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (some #{"dhf/spec/stray.feature"} (get-in (last-run ctx) [:defence :repo-touched])) ctx "not recorded") ctx))

(defstep #"the configured results directory is written inside ROOM"
  (fn [ctx] (k/write! (get-in ctx [:vars "ROOM"]) "dhf/allure-results/a-result.json" "{}") ctx))

(defstep #"the run's :defence :results-in-room is true"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (true? (get-in (last-run ctx) [:defence :results-in-room])) ctx "not true") ctx))

(defstep #"the run's :defence :unobserved names reads outside the room"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (some #(re-find #"reads outside the room" %) (get-in (last-run ctx) [:defence :unobserved])) ctx "not named") ctx))

(defstep #"it exits 2 and says no run of the role builder began in cycle 13"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no run of the role builder began in cycle 13") ctx))

(defstep #"cycle 12 recorded two runs, one of which wrote outside its room, and cycle 13 recorded one clean run"
  (fn [_]
    (let [ctx (k/new-project)
          clean {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room false :unobserved ["reads outside the room"]}
          run (fn [role m t d] {:role role :room "r" :began "2026-10-28T10:00:00Z" :ended "2026-10-28T11:00:00Z" :minutes m :tokens t :defence d})]
      (k/write! (:dir ctx) "dhf/cycles/12/runs.edn" (k/pp [(run "builder" 30 1000 (assoc clean :writes-outside ["/x"])) (run "grader" 12.5 300 clean)]))
      (k/write! (:dir ctx) "dhf/cycles/13/runs.edn" (k/pp [(run "builder" 20 500 clean)]))
      (assoc ctx :edn-vars {'M12 42.5 'M13 20 'T12 1300 'T13 500}))))

(defstep #"M12, M13, T12 and T13 are the sums of the cycles' runs" (fn [ctx] ctx))
