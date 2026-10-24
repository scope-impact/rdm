(ns harness.verify
  "`harness verify [--base REF]` (DI-95 to DI-99): ratify a build round.
  Admitting the round concludes its independent rules together (a suite is
  declared; with --base, no slice's plan or spec changed since). Then each step
  is a command a reaction issues on the previous step's success event, so a
  step that fails starts nothing after it (slice 01, decision D9):

    Round Admitted        -> check the instrument
    Instrument Checked    -> run the suite
    Suite Passed          -> start the served graph
    Served Graph Answered -> prove identity

  The verdict is Build Ratified when Identity Proven happens, else Build Not
  Ratified, naming the fail event."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [harness.config :as config]
            [harness.events :refer [event fail?]]
            [harness.spec :as spec]))

(def round-admitted "Round Admitted")
(def no-suite "Round Not Admitted / No Suite Declared")
(def spec-changed "Round Not Admitted / Spec Changed")
(def instrument-checked "Instrument Checked")
(def wrong-answer "Instrument Not Checked / Wrong Known Answer")
(def suite-passed "Suite Passed")
(def suite-red "Suite Not Passed / Suite Red")
(def answered "Served Graph Answered")
(def port-taken "Served Graph Not Started / Port Taken")
(def budget-exceeded "Served Graph Not Started / Boot Budget Exceeded")
(def identity-proven "Identity Proven")
(def not-this-record "Identity Not Proven / Not This Record")
(def ratified "Build Ratified")
(def not-ratified "Build Not Ratified")

(defn- option [args name] (second (drop-while #(not= name %) args)))

;; -- admitting the round ----------------------------------------------------------

(defn- changed-plan-files
  "The slices' plans and specs changed since `base`, committed or not."
  [root dhf base]
  (let [git (fn [& args] (:out (apply p/sh {:dir root} "git" args)))
        plan-dir (str (spec/relative root dhf) "/" spec/plan-dir "/")
        files (concat (str/split-lines (apply git ["diff" "--name-only" base "--"]))
                      (str/split-lines (git "ls-files" "--others" "--exclude-standard")))]
    (->> files (filter #(str/starts-with? % plan-dir))
         (filter #(re-find #"/(plan\.md|spec\.feature)$" %)) distinct sort)))

(defn admit [root {:keys [suite dhf]} args]
  (let [base (option args "--base")
        changed (when base (changed-plan-files root (str (fs/path root dhf)) base))
        fails (concat (when-not suite [(event no-suite (str config/file " declares no :suite, the one command "
                                                            "that runs the whole suite: nothing to ratify"))])
                      (when (seq changed)
                        [(event spec-changed (str "since " base ", this round changed " (str/join ", " changed)
                                                  ": specs change in planning rounds, not in a build"))]))]
    (if (seq fails)
      (vec fails)
      [(event round-admitted (if base (str "no plan or spec changed since " base)
                                 "no --base given: spec-lock not checked"))])))

;; -- the instrument ---------------------------------------------------------------

(defn check-instrument [root {:keys [known-answers rdm]}]
  (let [results (for [{:keys [record gate verdict results]} known-answers
                      :let [command (if (= :release gate)
                                      (concat rdm ["story" "release-gate" "--dhf" record "--allure-results" results])
                                      (concat rdm ["story" "design-gate" "--dhf" record]))
                            got (if (zero? (:exit (apply p/sh {:dir root} command))) :pass :fail)]]
                  {:record record :gate (or gate :design) :verdict verdict :got got})
        wrong (remove #(= (:verdict %) (:got %)) results)]
    (cond
      (empty? results)
      [(event wrong-answer (str config/file " declares no :known-answers: an instrument nobody "
                                "checked grades nothing"))]
      (seq wrong)
      (vec (for [{:keys [record gate verdict got]} wrong]
             (event wrong-answer (str record ": the " (name gate) " gate must " (name verdict)
                                      " it and gave " (name got)))))
      :else [(event instrument-checked (str (count results) " known answer(s) given as declared"))])))

;; -- the suite ----------------------------------------------------------------------

(defn run-suite [root {:keys [suite]}]
  (println (str "running the suite: " suite)) (flush)
  (let [{:keys [exit]} (p/shell {:dir root :continue true} "sh" "-c" suite)]
    (if (zero? exit)
      [(event suite-passed (str "'" suite "' exited 0"))]
      [(event suite-red (str "'" suite "' exited " exit ", whatever it printed"))])))

;; -- the served graph ---------------------------------------------------------------

(defn- holder
  "Who listens on a port: pid and command, from /proc (Linux), else unknown."
  [port]
  (let [hex (str/upper-case (format "%04x" port))
        inodes (set (for [f ["/proc/net/tcp" "/proc/net/tcp6"] :when (fs/exists? f)
                          ;; procfs reports a size of 0, which the JVM's reader refuses: cat reads it
                          line (rest (str/split-lines (:out (p/sh "cat" f))))
                          :let [cols (str/split (str/trim line) #"\s+")]
                          :when (and (str/ends-with? (nth cols 1 "") (str ":" hex)) (= "0A" (nth cols 3 "")))]
                      (nth cols 9)))
        pids (for [d (try (fs/list-dir "/proc") (catch Exception _ []))
                   :let [pid (fs/file-name d)] :when (re-matches #"\d+" pid)
                   fd (try (fs/list-dir (fs/path d "fd")) (catch Exception _ []))
                   :let [target (try (str (fs/read-link fd)) (catch Exception _ ""))]
                   :when (some #(= target (str "socket:[" % "]")) inodes)]
               pid)]
    (if-let [pid (first pids)]
      (str "pid " pid " (" (str/trim (try (slurp (str "/proc/" pid "/comm")) (catch Exception _ "?"))) ")")
      "a process this host does not name")))

(defn- taken? [port]
  (try (with-open [_ (java.net.ServerSocket. port 50 (java.net.InetAddress/getByName "127.0.0.1"))] false)
       (catch java.io.IOException _ true)))

(defn- answers? [url]
  (try (= 200 (:status (http/get url {:throw false :timeout 400
                                      :query-params {"query" "ASK { }"}
                                      :headers {"Accept" "application/sparql-results+json"}})))
       (catch Exception _ false)))

(defn start-served-graph
  "Build the graph into a temporary store and serve it; the clock covers both.
  Returns the events and the started process (to stop), if any."
  [root {:keys [port boot-budget-seconds poll-seconds rdm dhf]}]
  (if (taken? port)
    {:events [(event port-taken (str "port " port " is already held by " (holder port)
                                     "; the gate would measure that process, so it starts nothing and stops nothing"))]}
    (let [store (str (fs/create-temp-dir {:prefix "harness-graph"}))
          q #(str "'" (str/replace % "'" "'\\''") "'")
          line (str (str/join " " (map q rdm)) " graph build --dhf " (q dhf) " --store " (q store) " && exec "
                    (str/join " " (map q rdm)) " graph serve --store " (q store) " --bind 127.0.0.1:" port)
          proc (p/process {:dir root :out :discard :err :discard} "sh" "-c" line)
          polls (long (Math/floor (/ boot-budget-seconds poll-seconds)))
          url (str "http://127.0.0.1:" port "/")
          hit (loop [n 1]
                (cond (answers? url) n
                      (>= n polls) nil
                      :else (do (Thread/sleep (long (* 1000 poll-seconds))) (recur (inc n)))))]
      (if hit
        {:proc proc :url url
         :events [(event answered (str url " answered on poll " hit " of " polls ", leaving " (- polls hit)
                                       " unused (budget " boot-budget-seconds "s)"))]}
        {:proc proc
         :events [(event budget-exceeded (str url " never answered within the boot budget of " boot-budget-seconds
                                              "s (" polls " polls every " poll-seconds "s)"))]}))))

;; -- identity -------------------------------------------------------------------------

(defn- first-design-input
  "The record's first design input a slice's Rule declares, with its text."
  [dhf]
  (first (for [s (spec/slices dhf), sc (spec/read-spec (spec/spec-file s))
               :let [di (spec/design-input (:rule-tags sc))] :when di]
           {:id di :text (:rule sc)})))

(defn prove-identity [root {:keys [dhf]} url]
  (let [{:keys [id text]} (first-design-input (str (fs/path root dhf)))
        query (str "PREFIX dcterms: <http://purl.org/dc/terms/> PREFIX rdm: <https://github.com/scope-impact/rdm/ns#> "
                   "SELECT ?text WHERE { ?i dcterms:identifier \"" id "\" ; rdm:text ?text }")
        served (try (-> (http/get url {:query-params {"query" query} :throw false
                                       :headers {"Accept" "application/sparql-results+json"}})
                        :body (json/parse-string true) :results :bindings first :text :value)
                    (catch Exception _ nil))]
    (if (and id (= served text))
      [(event identity-proven (str id ": the served graph holds the record's text \"" text "\""))]
      [(event not-this-record (str (or id "no design input") ": served \"" served "\", the record holds \""
                                   text "\""))])))

;; -- the chain ------------------------------------------------------------------------

(defn run [root _dhf args]
  (let [{:keys [settings events]} (config/read-config root)]
    (if (seq events)
      events
      (do
        (config/print-settings settings)
        (let [stopped (atom nil)
              step (fn [acc f] (if (some fail? acc) acc (into acc (f))))
              chain (-> []
                        (step #(admit root settings args))
                        (step #(check-instrument root settings))
                        (step #(run-suite root settings))
                        (step #(let [{:keys [events proc url]} (start-served-graph root settings)]
                                 (reset! stopped proc)
                                 (if (some fail? events) events
                                     (into events (prove-identity root settings url))))))]
          (when-let [proc @stopped] (p/destroy-tree proc) (deref proc 5000 nil))
          (conj chain (if (some #(= identity-proven (:name %)) chain)
                        (event ratified "the round was admitted, the instrument checked, the suite passed, and the served graph is this record")
                        (event not-ratified (str "stopped at " (:name (first (filter fail? chain))))))))))))
