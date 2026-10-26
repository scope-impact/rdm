(ns rdm-spec.agency.observe
  "Observe (dhf/spec/agency/observe.feature): a run observed from outside,
  begin to end: its time, its cost and its defence (writes outside its room,
  entries left in the temporary directory, the project touched outside the
  cycle, results written in the room), each run one entry of runs.edn.
  Observing never prevents and never stops a run, and says what it cannot see."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]))

(def unobserved
  ["reads outside the room: no read is observed"
   "writes below a watched directory's own entries, and anything undone before the run ended"])

(defn- entries [dir]
  (if (fs/directory? dir)
    (vec (sort (map str (fs/list-dir dir))))
    []))

(defn- expand [d] (if (= "~" d) (System/getProperty "user.home") (str/replace-first d #"^~/" (str (System/getProperty "user.home") "/"))))

(defn new-entries
  "The entries of `after` no `before` held, except the excluded paths."
  [before after excluded]
  (vec (sort (for [[d es] after e es :when (and (not (some #{e} (get before d))) (not (excluded e)))] e))))

(defn- status-paths [root]
  (let [{:keys [exit out]} (cmd/git root "status" "--porcelain" "--untracked-files=all")]
    (if (= 0 exit) (vec (remove str/blank? (str/split-lines out))) [])))

(defn touched
  "The paths of status lines that were not there before, outside the cycle."
  [before after cycle-dir]
  (vec (sort (for [l after :when (not (some #{l} before))
                   :let [p (str/trim (subs l (min 3 (count l))))]
                   :when (not (str/starts-with? p (str cycle-dir "/")))]
               p))))

(defn- round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn breach? [run]
  (let [d (:defence run)]
    (boolean (or (seq (:writes-outside d)) (pos? (or (:tmp-left d) 0)) (seq (:repo-touched d)) (:results-in-room d)))))

(defn report [a ra b rb]
  {:cycles [a b]
   :runs {a (count ra) b (count rb)}
   :minutes {a (round2 (reduce + 0 (keep :minutes ra))) b (round2 (reduce + 0 (keep :minutes rb)))}
   :tokens {a (reduce + 0 (keep :tokens ra)) b (reduce + 0 (keep :tokens rb))}
   :breaches {a (count (filter breach? ra)) b (count (filter breach? rb))}})

(defn- runs-of [root dir]
  (or (try (cmd/read-edn (cmd/read-at root nil (str dir "/runs.edn"))) (catch Exception _ nil)) []))

(defn stage [{:keys [root config args opts]}]
  (let [[verb cycle role room-arg] args
        cycles (:cycles-dir config "dhf/cycles")
        cycle-dir (str cycles "/" cycle)
        watch (mapv expand (get-in config [:agency :watch] []))
        tmp (expand (get-in config [:agency :tmp] "/tmp"))
        open-file (fs/file root cycle-dir (str "observing-" role ".edn"))]
    (case verb
      "begin"
      (let [room (or room-arg (cmd/cannot-run! "observe begin CYCLE ROLE ROOM names the run's room"))
            room-abs (str (fs/absolutize (fs/path root room)))]
        (when-not (fs/directory? room-abs) (cmd/cannot-run! (str "the room " room " does not exist")))
        (fs/create-dirs (fs/parent open-file))
        (spit open-file (pr-str {:role role :room room-abs :began (str (java.time.Instant/now)) :t0 (System/currentTimeMillis)
                                 :watch (into {} (for [d watch] [d (entries d)]))
                                 :tmp (entries tmp)
                                 :status (status-paths root)}))
        {:result {:observe {:began role :cycle cycle :room room-abs}}})

      "end"
      (let [open (or (try (cmd/read-edn (slurp open-file)) (catch Exception _ nil))
                     (cmd/cannot-run! (str "no run of the role " role " began in cycle " cycle ": run \"rdm-spec observe begin\" first")))
            room (:room open)
            writes (new-entries (:watch open) (into {} (for [d watch] [d (entries d)]))
                                (into #{room (str (fs/absolutize root)) tmp}
                                      (for [p (iterate fs/parent (fs/path room)) :while p] (str p))))
            tmp-left (count (remove (set (:tmp open)) (entries tmp)))
            repo (touched (:status open) (status-paths root) cycle-dir)
            results-dir (fs/path room (:results-dir config "dhf/allure-results"))
            in-room (boolean (and (fs/directory? results-dir) (seq (fs/list-dir results-dir))))
            minutes (round2 (/ (- (System/currentTimeMillis) (:t0 open)) 60000.0))
            tokens (some-> (:tokens opts) parse-long)
            run {:role role :room room :began (:began open) :ended (str (java.time.Instant/now))
                 :minutes minutes :tokens tokens
                 :defence {:writes-outside writes :tmp-left tmp-left :repo-touched repo
                           :results-in-room in-room :unobserved unobserved}}
            runs (conj (vec (runs-of root cycle-dir)) run)]
        (spit (fs/file root cycle-dir "runs.edn") (with-out-str (pp/pprint runs)))
        (fs/delete-if-exists open-file)
        {:result {:observe {:cycle cycle :run run}}
         :warnings (vec (concat
                         (for [w writes] (cmd/finding nil nil "write-outside" (str "the run wrote " w " outside its room")))
                         (when (pos? tmp-left) [(cmd/finding nil nil "tmp-left" (str "the run left " tmp-left " entries in " tmp))])
                         (for [p repo] (cmd/finding p nil "repo-touched" "the run changed the project outside its cycle"))
                         (when in-room [(cmd/finding nil nil "results-in-room" (str "the run wrote results inside its room, " results-dir))])))})

      "report"
      (let [other room-arg]
        (when (or (nil? cycle) (nil? role) other)
          (cmd/cannot-run! "usage: rdm-spec observe report CYCLE CYCLE"))
        {:result {:observe (report cycle (runs-of root cycle-dir) role (runs-of root (str cycles "/" role)))}})

      (cmd/cannot-run! (str "observe takes begin, end or report, not " verb)))))
