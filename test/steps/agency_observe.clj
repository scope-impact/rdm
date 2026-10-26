(ns steps.agency-observe
  "Step definitions of dhf/spec/agency/observe.feature: W1, T and ROOM are
  directories the scenario makes."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn runs [ctx c]
  (let [f (fs/file (k/guard! (:dir ctx)) (str "dhf/cycles/" c "/runs.edn"))]
    (if (fs/exists? f) (edn/read-string (slurp f)) [])))

(defn last-run [ctx]
  (k/check-exit ctx 0)
  (or (last (runs ctx "13")) (fail! (str "no run was recorded: " (:err ctx)))))

(defstep #"a project whose configuration watches W1 and whose temporary directory is T"
  (fn [ctx]
    (let [ctx (-> (k/project! ctx) (k/placeholder! "W1") (k/placeholder! "T") (k/placeholder! "ROOM"))]
      (k/commit! (:dir ctx) "the project")
      (k/update-edn! ctx "dhf/harness/config.edn" assoc :agency {:watch [(get-in ctx [:ph "W1"])] :tmp (get-in ctx [:ph "T"])})
      (k/commit! (:dir ctx) "agency")
      (assoc ctx ::before 0))))

(defstep #"\"(rdm-spec observe begin 13 (?:builder|grader) ROOM)\" has been run"
  (fn [ctx line]
    (let [r (k/rdm-line ctx line)]
      (k/check-exit r 0)
      ctx)))

(defstep #"dhf/cycles/13/runs.edn holds one more entry, read as EDN, with exactly the keys :role, :room, :began, :ended, :minutes, :tokens and :defence"
  (fn [ctx]
    (assert (= 1 (count (runs ctx "13"))))
    (let [r (last-run ctx)]
      (assert (= #{:role :room :began :ended :minutes :tokens :defence} (set (keys r))) (pr-str r))
      (assert (= 1200 (:tokens r)))
      ctx)))

(defstep #"its :defence holds exactly the keys :writes-outside, :tmp-left, :repo-touched, :results-in-room and :unobserved"
  (fn [ctx]
    (assert (= #{:writes-outside :tmp-left :repo-touched :results-in-room :unobserved} (set (keys (:defence (last-run ctx))))))
    ctx))

(defstep #"a file W1/stray.txt is written"
  (fn [ctx] (k/write-files! (get-in ctx [:ph "W1"]) {"stray.txt" "stray\n"}) ctx))

(defn stray [ctx] (str (fs/path (get-in ctx [:ph "W1"]) "stray.txt")))

(defstep #"it exits 0 with a warning naming W1/stray.txt"
  (fn [ctx]
    (k/check-exit ctx 0)
    (k/err-line-with ctx "warning:" (stray ctx))))

(defstep #"the run's :defence :writes-outside holds W1/stray.txt"
  (fn [ctx]
    (assert (some #{(stray ctx)} (get-in (last-run ctx) [:defence :writes-outside])) (pr-str (last-run ctx)))
    ctx))

(defstep #"three entries are made in T"
  (fn [ctx] (k/write-files! (get-in ctx [:ph "T"]) {"a" "1" "b/c" "2" "d" "3"}) ctx))

(defstep #"the run's :defence :tmp-left is 3"
  (fn [ctx] (assert (= 3 (get-in (last-run ctx) [:defence :tmp-left])) (pr-str (last-run ctx))) ctx))

(defstep #"an untracked file dhf/spec/stray.feature is written in the project"
  (fn [ctx] (k/write-files! (:dir ctx) {"dhf/spec/stray.feature" "Feature: Stray\n"}) ctx))

(defstep #"the run's :defence :repo-touched holds dhf/spec/stray.feature"
  (fn [ctx]
    (assert (= ["dhf/spec/stray.feature"] (get-in (last-run ctx) [:defence :repo-touched])) (pr-str (last-run ctx)))
    ctx))

(defstep #"the configured results directory is written inside ROOM"
  (fn [ctx]
    (k/write-result! (fs/path (get-in ctx [:ph "ROOM"]) "dhf/allure-results") {:id "spec-0001"})
    ctx))

(defstep #"the run's :defence :results-in-room is true"
  (fn [ctx] (assert (true? (get-in (last-run ctx) [:defence :results-in-room]))) ctx))

(defstep #"the run's :defence :unobserved names reads outside the room"
  (fn [ctx]
    (assert (some #(str/includes? % "reads outside the room") (get-in (last-run ctx) [:defence :unobserved])) (pr-str (last-run ctx)))
    (assert (= {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room false}
               (dissoc (:defence (last-run ctx)) :unobserved))
            (pr-str (last-run ctx)))
    ctx))

(defstep #"it exits 2 and says no run of the role builder began in cycle 13"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "no run of the role builder began in cycle 13")))

(defn entry [role minutes tokens writes]
  {:role role :room "/rooms/r" :began "2026-10-26T10:00:00Z" :ended "2026-10-26T11:00:00Z" :minutes minutes :tokens tokens
   :defence {:writes-outside writes :tmp-left 0 :repo-touched [] :results-in-room false :unobserved ["reads outside the room"]}})

(defstep #"cycle 12 recorded two runs, one of which wrote outside its room, and cycle 13 recorded one clean run"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/write-files! (:dir ctx) {"dhf/cycles/12/runs.edn" (k/pr-edn [(entry "builder" 1.5 100 ["/stray.txt"]) (entry "grader" 2.25 200 [])])
                                  "dhf/cycles/13/runs.edn" (k/pr-edn [(entry "builder" 4.0 50 [])])})
      ctx)))

(defstep #"M12, M13, T12 and T13 are the sums of the cycles' runs"
  (fn [ctx]
    (assert (= {'M12 3.75 'M13 4.0 'T12 300 'T13 50} (select-keys (:bindings ctx) '[M12 M13 T12 T13])) (pr-str (:bindings ctx)))
    ctx))
