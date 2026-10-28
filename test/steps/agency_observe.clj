(ns steps.agency-observe
  "Steps of the component Observe (dhf/spec/07-measure/observe.feature).
  W1, T and ROOM are directories the scenario makes."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn runs [ctx n] (or (some-> (k/slurp-rel ctx (str "dhf/cycles/" n "/runs.edn")) edn/read-string) []))
(defn last-run [ctx] (last (runs ctx "13")))
(defn defence [ctx] (:defence (last-run ctx)))

(defstep #"a project whose configuration watches W1 and whose temporary directory is T"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)
          w1 (str (:base ctx) "/w1") t (str (:base ctx) "/t") room (str (:base ctx) "/room")]
      (doseq [d [w1 t room]] (fs/create-dirs d))
      (k/config! ctx assoc :agency {:watch [w1] :tmp t})
      (assoc ctx :ph {"ROOM" room "W1" w1 "T" t}))))

(defstep #"\"rdm-spec observe begin 13 (builder|grader) ROOM\" has been run"
  (fn [ctx role] (let [r (k/run-rdm ctx (str "observe begin 13 " role " ROOM"))] (k/exits! r 0) ctx)))

(defstep #"dhf/cycles/13/runs.edn holds one more entry, read as EDN, with exactly the keys :role, :room, :began, :ended, :minutes, :tokens and :defence"
  (fn [ctx]
    (k/check! (= 1 (count (runs ctx "13"))) "runs.edn holds " (count (runs ctx "13")) " entries")
    (k/check! (= #{:role :room :began :ended :minutes :tokens :defence} (set (keys (last-run ctx)))) "the entry holds " (pr-str (keys (last-run ctx))))
    (k/check! (= 1200 (:tokens (last-run ctx))) "the tokens are " (:tokens (last-run ctx)))
    ctx))

(defstep #"its :defence holds exactly the keys :writes-outside, :tmp-left, :repo-touched, :results-in-room and :unobserved"
  (fn [ctx]
    (k/check! (= #{:writes-outside :tmp-left :repo-touched :results-in-room :unobserved} (set (keys (defence ctx)))) "the defence holds " (pr-str (keys (defence ctx))))
    ctx))

(defstep #"a file W1/stray.txt is written"
  (fn [ctx] (spit (str (get-in ctx [:ph "W1"]) "/stray.txt") "stray\n") ctx))

(defstep #"it exits 0 with a warning naming W1/stray.txt"
  (fn [ctx] (k/exits! ctx 0) (k/err-line-has! ctx "warning" (str (get-in ctx [:ph "W1"]) "/stray.txt")) ctx))

(defstep #"the run's :defence :writes-outside holds W1/stray.txt"
  (fn [ctx]
    (k/check! (some #{(str (get-in ctx [:ph "W1"]) "/stray.txt")} (:writes-outside (defence ctx))) "writes-outside holds " (pr-str (:writes-outside (defence ctx))))
    ctx))

(defstep #"three entries are made in T"
  (fn [ctx]
    (let [t (get-in ctx [:ph "T"])]
      (spit (str t "/a.txt") "a") (spit (str t "/b.txt") "b") (fs/create-dirs (str t "/c"))
      ctx)))

(defstep #"the run's :defence :tmp-left is 3"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= 3 (:tmp-left (defence ctx))) "tmp-left is " (:tmp-left (defence ctx))) ctx))

(defstep #"an untracked file dhf/spec/stray.feature is written in the project"
  (fn [ctx] (k/write! ctx "dhf/spec/stray.feature" "Feature: Stray\n")))

(defstep #"the run's :defence :repo-touched holds dhf/spec/stray.feature"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= ["dhf/spec/stray.feature"] (:repo-touched (defence ctx))) "repo-touched holds " (pr-str (:repo-touched (defence ctx))))
    ctx))

(defstep #"the configured results directory is written inside ROOM"
  (fn [ctx]
    (k/result-json! ctx (str (get-in ctx [:ph "ROOM"]) "/dhf/allure-results") {:spec "spec-0001"})
    ctx))

(defstep #"the run's :defence :results-in-room is true"
  (fn [ctx] (k/exits! ctx 0) (k/check! (true? (:results-in-room (defence ctx))) "results-in-room is " (:results-in-room (defence ctx))) ctx))

(defstep #"the run's :defence :unobserved names reads outside the room"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (some #(str/includes? % "reads outside the room") (:unobserved (defence ctx))) "unobserved is " (pr-str (:unobserved (defence ctx))))
    ctx))

(defstep #"it exits 2 and says no run of the role builder began in cycle 13"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no run of the role builder began in cycle 13") ctx))

(defn run-entry [minutes tokens breach?]
  {:role "builder" :room "/rooms/x" :began "2026-10-28T10:00:00Z" :ended "2026-10-28T11:00:00Z" :minutes minutes :tokens tokens
   :defence {:writes-outside (if breach? ["/stray"] []) :tmp-left 0 :repo-touched [] :results-in-room false
             :unobserved ["reads outside the room"]}})

(defstep #"cycle 12 recorded two runs, one of which wrote outside its room, and cycle 13 recorded one clean run"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/write-edn! ctx "dhf/cycles/12/runs.edn" [(run-entry 30.5 1000 true) (run-entry 12.25 500 false)])
      (k/write-edn! ctx "dhf/cycles/13/runs.edn" [(run-entry 20.0 700 false)])
      ctx)))

(defstep #"M12, M13, T12 and T13 are the sums of the cycles' runs"
  (fn [ctx]
    (let [{:syms [M12 M13 T12 T13]} (:bindings ctx)]
      (k/check! (= [42.75 20.0 1500 700] [(double M12) (double M13) T12 T13]) "the sums are " (pr-str [M12 M13 T12 T13]))
      ctx)))
