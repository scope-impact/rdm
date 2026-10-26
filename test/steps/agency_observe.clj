(ns steps.agency-observe
  "Step definitions for dhf/spec/agency/observe.feature: `rdm-spec observe`
  in a project the scenario makes: the room's configuration (without
  :oracle) watching W1 with the temporary directory T, made a git
  repository; W1, T and ROOM are new directories under the run's fixture
  root, named in :placeholders.

  Context keys set here: :dir, :room, :placeholders (W1, T), :runs-before
  (how many runs cycle 13 had recorded before the When), :runs-written
  ({cycle [run]} a Given recorded)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- runs-rel [cycle] (str "dhf/cycles/" cycle "/runs.edn"))

(defn- runs-of [ctx cycle]
  (let [f (fs/file (:dir ctx) (runs-rel cycle))]
    (if (fs/exists? f) (edn/read-string (slurp (str f))) [])))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))))

(defstep #"a project whose configuration watches W1 and whose temporary directory is T"
  (fn [ctx]
    (let [w1 (str (fs/create-dirs (fs/file (s/tmp-dir) "w1")))
          t (str (fs/create-dirs (fs/file (s/tmp-dir) "t")))
          room (s/tmp-dir)
          dir (s/project! {:agency {:watch [w1] :tmp t}})]
      (git! dir "init" "-q")
      (assoc ctx :dir dir :room room
             :placeholders (merge (:placeholders ctx) {"W1" w1 "T" t})))))

(defstep #"\"rdm-spec (observe begin \S+ \S+ ROOM)\" has been run"
  (fn [ctx line]
    (let [ctx (kc/run-line ctx line)]
      (kc/exits! ctx 0)
      (assoc ctx :runs-before (count (runs-of ctx "13"))))))

(defstep #"a file W1/stray\.txt is written"
  (fn [ctx] (s/write! (get-in ctx [:placeholders "W1"]) "stray.txt" "a stray write\n") ctx))

(defstep #"three entries are made in T"
  (fn [ctx]
    (let [t (get-in ctx [:placeholders "T"])]
      (s/write! t "one.txt" "1\n")
      (s/write! t "two.txt" "2\n")
      (fs/create-dirs (fs/file t "three"))
      ctx)))

(defstep #"an untracked file dhf/spec/stray\.feature is written in the project"
  (fn [ctx] (s/write! (:dir ctx) "dhf/spec/stray.feature" "Feature: Stray\n") ctx))

(defstep #"the configured results directory is written inside ROOM"
  (fn [ctx]
    (let [rd (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results")]
      (s/write! (:room ctx) (str rd "/" (random-uuid) "-result.json") "{\"status\":\"passed\"}\n")
      ctx)))

(defn- the-run-recorded
  "The run cycle 13's runs.edn recorded last."
  [ctx]
  (let [r (kc/exits! ctx 0)
        runs (runs-of ctx "13")]
    (s/check! (seq runs) (str "dhf/cycles/13/runs.edn holds no run\n" (s/show r)))
    (last runs)))

(defstep #"dhf/cycles/13/runs\.edn holds one more entry, read as EDN, with exactly the keys :role, :room, :began, :ended, :minutes, :tokens and :defence"
  (fn [ctx]
    (let [run (the-run-recorded ctx)
          runs (runs-of ctx "13")]
      (s/check! (= (inc (:runs-before ctx 0)) (count runs)) (str "runs.edn holds " (count runs) " runs"))
      (s/check! (= #{:role :room :began :ended :minutes :tokens :defence} (set (keys run)))
                (str "the run's keys are " (pr-str (keys run))))
      nil)))

(defstep #"its :defence holds exactly the keys :writes-outside, :tmp-left, :repo-touched, :results-in-room and :unobserved"
  (fn [ctx]
    (let [d (:defence (the-run-recorded ctx))]
      (s/check! (= #{:writes-outside :tmp-left :repo-touched :results-in-room :unobserved} (set (keys d)))
                (str "the defence's keys are " (pr-str (keys d))))
      nil)))

(defn- stray [ctx] (str (get-in ctx [:placeholders "W1"]) "/stray.txt"))

(defstep #"it exits 0 with a warning naming W1/stray\.txt"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % (stray ctx))) (s/err-lines r))
                (str "no warning names " (stray ctx) "\n" (s/show r)))
      nil)))

(defstep #"the run's :defence :writes-outside holds W1/stray\.txt"
  (fn [ctx]
    (let [w (get-in (the-run-recorded ctx) [:defence :writes-outside])]
      (s/check! (some #{(stray ctx)} w) (str ":writes-outside is " (pr-str w)))
      nil)))

(defstep #"the run's :defence :tmp-left is (\d+)"
  (fn [ctx n]
    (let [v (get-in (the-run-recorded ctx) [:defence :tmp-left])]
      (s/check! (= (parse-long n) v) (str ":tmp-left is " (pr-str v)))
      nil)))

(defstep #"the run's :defence :repo-touched holds (\S+)"
  (fn [ctx path]
    (let [v (get-in (the-run-recorded ctx) [:defence :repo-touched])]
      (s/check! (some #{path} v) (str ":repo-touched is " (pr-str v)))
      nil)))

(defstep #"the run's :defence :results-in-room is true"
  (fn [ctx]
    (let [v (get-in (the-run-recorded ctx) [:defence :results-in-room])]
      (s/check! (true? v) (str ":results-in-room is " (pr-str v)))
      nil)))

(defstep #"the run's :defence :unobserved names reads outside the room"
  (fn [ctx]
    (let [v (get-in (the-run-recorded ctx) [:defence :unobserved])]
      (s/check! (some #(str/includes? (str %) "reads outside the room") v) (str ":unobserved is " (pr-str v)))
      nil)))

(defstep #"it exits 2 and says no run of the role builder began in cycle 13"
  (fn [ctx] (kc/refused-naming! ctx "no run of the role builder began in cycle 13") nil))

;; ---------------------------------------------------------------------------
;; The report

(def ^:private clean {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room false
                      :unobserved ["reads outside the room: reads cannot be observed"]})

(defn- run-entry [role minutes tokens defence]
  {:role role :room "/rooms/r" :began "2026-10-26T08:00:00Z" :ended "2026-10-26T09:00:00Z"
   :minutes minutes :tokens tokens :defence defence})

(defstep #"cycle 12 recorded two runs, one of which wrote outside its room, and cycle 13 recorded one clean run"
  (fn [ctx]
    (let [dir (s/project!)
          runs {"12" [(run-entry "builder" 42.5 120000 (assoc clean :writes-outside ["/stray.txt"]))
                      (run-entry "grader" 12.25 30000 clean)]
                "13" [(run-entry "builder" 30.0 90000 clean)]}]
      (doseq [[c rs] runs] (s/write! dir (runs-rel c) (str (pr-str rs) "\n")))
      (assoc ctx :dir dir :runs-written runs))))

(defstep #"M12, M13, T12 and T13 are the sums of the cycles' runs"
  (fn [ctx]
    (let [b (:bound ctx)
          sum (fn [c k] (reduce + 0 (keep k (get-in ctx [:runs-written c]))))]
      (doseq [[ph c k] [["M12" "12" :minutes] ["M13" "13" :minutes] ["T12" "12" :tokens] ["T13" "13" :tokens]]]
        (s/check! (and (number? (get b ph)) (== (sum c k) (get b ph)))
                  (str ph " is " (pr-str (get b ph)) ", the sum is " (sum c k))))
      nil)))
