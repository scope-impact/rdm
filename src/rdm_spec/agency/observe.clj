(ns rdm-spec.agency.observe
  "Observe, the agency context's one component (dhf/spec/agency/observe.feature;
  dhf/spec/glossary.md, \"Runs\"): each run of a cycle, a builder's, a
  grader's or the integrator's, observed from outside. It records; it never
  prevents and never stops a run.

    observe begin CYCLE ROLE ROOM     before the run: what it is compared with
    observe end CYCLE ROLE [--tokens N]
                                      after it: one entry appended to
                                      dhf/cycles/CYCLE/runs.edn
    observe report CYCLE CYCLE        two cycles' runs side by side

  What begin keeps (until end) is in dhf/cycles/CYCLE/observing.edn: the
  entries of each watched directory (the configuration's :agency :watch,
  \"~\" the home directory) and of the temporary directory (:agency :tmp),
  the project's git status, the room's results directory and the time.

  A run's entry, exactly {:role :room :began :ended :minutes :tokens
  :defence}, its defence exactly {:writes-outside [path] :tmp-left n
  :repo-touched [path] :results-in-room bool :unobserved [what]}:
    :writes-outside  the entries added to or removed from a watched
                     directory, but the room and what holds it
    :tmp-left        the entries added to the temporary directory
    :repo-touched    the project's paths git reports changed, outside the
                     cycle's own directory, that were not before
    :results-in-room result files written into the room's results directory
    :unobserved      what cannot be observed: reads, always; writes below a
                     watched directory's own entries; a directory or a
                     project that could not be read
  Each breach is a warning; the run is never failed for one (exit 0). The
  cost is what --tokens gives (nil when not given); :minutes the run's
  length, to the hundredth.

  The report: {:observe {:cycles [A B] :runs {A n} :minutes {A sum}
  :tokens {A sum} :breaches {A runs-that-breached}}}, never netted.

  Cannot run (exit 2): ending a run that never began, beginning one that
  has not ended, a room that is not a directory, an unknown role, a
  cycle with no runs.edn to report."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def roles #{"builder" "grader" "integrator"})

(def unobserved-reads "reads outside the room: reads cannot be observed")

(def unobserved-depth "writes below a watched directory's own entries: only its entries are compared")

;; ---------------------------------------------------------------------------
;; Pure

(defn changed-entries
  "The paths of the entries of `dir` added or removed between the names
  `before` and `after`, sorted."
  [dir before after]
  (let [b (set before) a (set after)]
    (vec (sort (map #(str (str/replace dir #"/+$" "") "/" %)
                    (concat (remove b a) (remove a b)))))))

(defn- under-or-at? [p q]
  (let [p (str/replace (str p) #"/+$" "") q (str/replace (str q) #"/+$" "")]
    (or (= p q) (str/starts-with? p (str q "/")))))

(defn outside?
  "Whether the path `p` is a write outside every one of `insides` (the room,
  the project): not one of them, under one, or holding one."
  [p insides]
  (not-any? #(or (under-or-at? p %) (under-or-at? % p)) insides))

(defn breached?
  "Whether the defence `d` holds any breach."
  [d]
  (boolean (or (seq (:writes-outside d)) (pos? (or (:tmp-left d) 0)) (seq (:repo-touched d))
               (:results-in-room d))))

(defn- round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn report-of
  "The report of the cycles `cycles`, their runs `runs` ({cycle [run]})."
  [cycles runs]
  (let [per (fn [f] (into {} (for [c cycles] [c (f (get runs c))])))]
    {:observe {:cycles (vec cycles)
               :runs (per count)
               :minutes (per #(round2 (reduce + 0 (keep :minutes %))))
               :tokens (per #(reduce + 0 (keep :tokens %)))
               :breaches (per #(count (filter (comp breached? :defence) %)))}}))

;; ---------------------------------------------------------------------------
;; Looking

(defn- home [req] (or (not-empty (get (:env req) "HOME")) (System/getProperty "user.home")))

(defn- expand [req d]
  (let [d (str d)]
    (str (fs/normalize (fs/absolutize
                        (cond (= "~" d) (home req)
                              (str/starts-with? d "~/") (str (home req) (subs d 1))
                              :else (command/path req d)))))))

(defn- entries [d]
  (try (when (fs/directory? d) (vec (sort (map #(str (fs/file-name %)) (fs/list-dir d)))))
       (catch Exception _ nil)))

(defn- repo-status
  "The project's paths git reports changed or untracked, or nil when it is
  not a git repository."
  [root]
  (let [r (try (p/shell {:dir (str root) :out :string :err :string :continue true :in ""}
                        "git" "status" "--porcelain=v1" "-z" "--untracked-files=all")
               (catch Exception _ nil))]
    (when (and r (zero? (:exit r)))
      (let [parts (str/split (:out r) #"\u0000")]
        (loop [[x & more] parts out []]
          (cond (nil? x) (vec (sort (distinct out)))
                (str/blank? x) (recur more out)
                :else (let [code (subs x 0 (min 2 (count x)))
                            path (subs x (min 3 (count x)))]
                        ;; a rename carries its source as the next entry
                        (if (re-find #"[RC]" code)
                          (recur (rest more) (conj out path))
                          (recur more (conj out path))))))))))

(defn- result-files [room results-dir]
  (let [d (fs/file room results-dir)]
    (if (fs/directory? d)
      (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (str (fs/relativize d f)))))
      [])))

(defn- snapshot [req room]
  (let [agency (command/config-value req :agency)
        watch (mapv #(expand req %) (:watch agency))
        tmp (expand req (or (:tmp agency) "/tmp"))]
    {:watch (into {} (for [d watch] [d (entries d)]))
     :tmp {:dir tmp :entries (entries tmp)}
     :repo (repo-status (:root req))
     :results (result-files room (command/config-value req :results-dir))}))

;; ---------------------------------------------------------------------------
;; Files

(defn- cycle-dir [req cycle]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle))

(defn- read-edn-file [req rel what]
  (let [f (command/path req rel)]
    (when (fs/exists? f)
      (try (edn/read-string (slurp (str f)))
           (catch Exception e (command/cannot-run! (str rel " is not readable EDN (" what "): " (ex-message e))))))))

(defn- write-edn! [req rel x]
  (let [f (command/path req rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (binding [*print-namespace-maps* false] (str (pr-str x) "\n")))))

(defn- runs-text [runs]
  (binding [*print-namespace-maps* false]
    (str "[" (str/join "\n " (map pr-str runs)) "]\n")))

(defn- check-cycle! [cycle]
  (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" (str cycle))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle's name, as dhf/cycles/NN names it"))))

(defn- check-role! [role]
  (when-not (roles role)
    (command/cannot-run! (str "the role " role " is not one a run is observed in: "
                              (str/join ", " (sort roles))))))

;; ---------------------------------------------------------------------------
;; The tasks

(defn- begin! [req [cycle role room-arg]]
  (check-cycle! cycle)
  (check-role! role)
  (let [room (str (fs/normalize (fs/absolutize (command/path req room-arg))))
        _ (when-not (fs/directory? room)
            (command/cannot-run! (str "ROOM " room-arg " is not a directory: a run is observed from its room")))
        rel (str (cycle-dir req cycle) "/observing.edn")
        pending (or (read-edn-file req rel "the runs begun") {})]
    (when (contains? pending role)
      (command/cannot-run! (str "a run of the role " role " already began in cycle " cycle
                                " and has not ended: rdm-spec observe end " cycle " " role)))
    (let [now (java.time.Instant/now)]
      (write-edn! req rel (assoc pending role {:room room :began (str now) :began-ms (.toEpochMilli now)
                                               :before (snapshot req room)}))
      {:result {:observe {:cycle cycle :role role :room room :began (str now)}} :findings []})))

(defn- defence [req cycle {:keys [room before]} after]
  (let [own (cycle-dir req cycle)
        insides [room (str (fs/normalize (fs/absolutize (:root req))))]
        tmp-dir (get-in after [:tmp :dir])
        unread (for [[d es] (:watch after) :when (or (nil? es) (nil? (get-in before [:watch d])))] d)
        writes (vec (sort (distinct
                           (for [[d es] (:watch after)
                                 :let [was (get-in before [:watch d])]
                                 :when (and es was)
                                 p (changed-entries d was es)
                                 :when (and (outside? p insides) (not (under-or-at? p tmp-dir)))]
                             p))))
        tmp-before (set (get-in before [:tmp :entries]))
        tmp-left (count (remove tmp-before (get-in after [:tmp :entries])))
        repo (when (and (:repo before) (:repo after))
               (vec (sort (remove #(under-or-at? % own) (remove (set (:repo before)) (:repo after))))))
        results (seq (remove (set (:results before)) (:results after)))]
    {:writes-outside writes
     :tmp-left tmp-left
     :repo-touched (or repo [])
     :results-in-room (boolean results)
     :unobserved (vec (concat [unobserved-reads unobserved-depth]
                              (for [d unread] (str "the watched directory " d " could not be read"))
                              (when-not (and (:repo before) (:repo after))
                                ["changes to the project: it is not a git repository"])
                              (when (nil? (get-in after [:tmp :entries]))
                                [(str "the temporary directory " tmp-dir " could not be read")])))}))

(defn- breach-warnings [role d]
  (vec (concat
        (for [p (:writes-outside d)]
          (command/warning "write-outside-room" (str "the " role "'s run changed " p ", outside its room")))
        (when (pos? (:tmp-left d))
          [(command/warning "tmp-left" (str "the " role "'s run left " (:tmp-left d)
                                            " entries in the temporary directory"))])
        (for [p (:repo-touched d)]
          (command/warning p "repo-touched" (str "the " role "'s run changed the project outside its cycle")))
        (when (:results-in-room d)
          [(command/warning "results-in-room" (str "the " role "'s run wrote results inside its room"))]))))

(defn- end! [req [cycle role & more]]
  (check-cycle! cycle)
  (check-role! role)
  (when (seq more)
    (command/cannot-run! "observe end takes CYCLE ROLE [--tokens N]"))
  (let [tokens (when-let [t (get-in req [:opts :tokens])]
                 (or (when (re-matches #"\d+" t) (parse-long t))
                     (command/cannot-run! (str "--tokens " t " is not a number of tokens"))))
        rel (str (cycle-dir req cycle) "/observing.edn")
        pending (or (read-edn-file req rel "the runs begun") {})
        begun (or (get pending role)
                  (command/cannot-run! (str "no run of the role " role " began in cycle " cycle
                                            ": rdm-spec observe begin " cycle " " role " ROOM comes first")))
        now (java.time.Instant/now)
        d (defence req cycle begun (snapshot req (:room begun)))
        run {:role role :room (:room begun) :began (:began begun) :ended (str now)
             :minutes (round2 (/ (- (.toEpochMilli now) (:began-ms begun)) 60000.0))
             :tokens tokens
             :defence d}
        runs-rel (str (cycle-dir req cycle) "/runs.edn")
        runs (or (read-edn-file req runs-rel "the cycle's runs") [])
        _ (when-not (vector? runs) (command/cannot-run! (str runs-rel " is not a vector of runs")))
        f (command/path req runs-rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (runs-text (conj runs run)))
    (let [left (dissoc pending role)]
      (if (empty? left)
        (fs/delete-if-exists (command/path req rel))
        (write-edn! req rel left)))
    {:result {:observe {:cycle cycle :role role :run run :breached? (breached? d)}}
     :findings (breach-warnings role d)}))

(defn- report! [req [a b & more]]
  (when (seq more) (command/cannot-run! "observe report takes CYCLE CYCLE"))
  (check-cycle! a)
  (check-cycle! b)
  (let [runs (into {} (for [c [a b]
                            :let [rel (str (cycle-dir req c) "/runs.edn")
                                  rs (read-edn-file req rel "the cycle's runs")]]
                        (do (when (nil? rs)
                              (command/cannot-run! (str "there is no " rel ": no run of cycle " c " was observed")))
                            (when-not (vector? rs) (command/cannot-run! (str rel " is not a vector of runs")))
                            [c rs])))]
    {:result (report-of [a b] runs) :findings []}))

(defn observe
  "The observe task's stage."
  [req]
  (let [[sub & args] (:args req)]
    (case sub
      "begin" (if (= 3 (count args))
                (begin! req args)
                (command/cannot-run! "observe begin takes CYCLE ROLE ROOM"))
      "end" (end! req args)
      "report" (report! req args)
      (command/cannot-run! (str "observe takes begin, end or report, not " sub
                                ": rdm-spec observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N],"
                                " or report CYCLE CYCLE")))))
