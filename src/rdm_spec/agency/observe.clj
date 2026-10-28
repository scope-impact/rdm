(ns rdm-spec.agency.observe
  "Records each run's time, cost and what it touched outside its room, beside
  what it achieved, never netted; it observes, never prevents, never stops a
  run (dhf/spec/07-measure/observe.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(def unobserved ["reads outside the room are not observed" "a write below a watched directory's own entries is not observed"])

(defn- expand [d] (if (= "~" d) (System/getProperty "user.home") (str/replace d #"^~/" (str (System/getProperty "user.home") "/"))))

(defn- entries [d] (if (fs/directory? d) (vec (sort (map #(str (fs/file-name %)) (fs/list-dir d)))) []))

(defn new-entries
  "The entries of each watched directory at the end that it did not hold at the beginning, minus skip."
  [before after skip]
  (vec (sort (for [[d es] after
                   e es
                   :let [p (str (fs/path d e))]
                   :when (and (not (some #{e} (get before d))) (not (skip p)))]
               p))))

(defn breach? [d]
  (boolean (or (seq (:writes-outside d)) (pos? (or (:tmp-left d) 0)) (seq (:repo-touched d)) (:results-in-room d))))

(defn sums [runs]
  {:runs (count runs)
   :minutes (reduce + 0 (keep :minutes runs))
   :tokens (reduce + 0 (keep :tokens runs))
   :breaches (count (filter (comp breach? :defence) runs))})

(defn- repo-status [root]
  (let [pre ((requiring-resolve 'rdm-spec.regeneration.cut/prefix) root)
        r (project/git root "status" "--porcelain" "-uall" "--" ".")]
    (if (zero? (:exit r))
      (vec (sort (for [l (str/split-lines (:out r)) :when (> (count l) 3)
                       :let [p (subs l 3) p (if (str/includes? p " -> ") (second (str/split p #" -> ")) p)]]
                   (if (str/starts-with? p pre) (subs p (count pre)) p))))
      [])))

(defn- snapshot [root cfg room]
  (let [ag (:agency cfg)]
    {:watch (into {} (for [d (:watch ag)] (let [x (expand d)] [x (entries x)])))
     :tmp (entries (expand (:tmp ag)))
     :repo (repo-status root)
     :room room}))

(defn- state-file [root cfg cycle role] (fs/path root (:cycles-dir cfg) cycle (str ".observe-" role ".edn")))

(defn observe [run]
  (let [root (:root run) cfg (:cfg run)
        [sub cycle role room] (:args run)]
    (case sub
      "begin"
      (do (when-not (and cycle role room) (project/cannot-run! "observe begin CYCLE ROLE ROOM"))
          (let [abs (str (fs/absolutize (fs/path root room)))
                f (state-file root cfg cycle role)]
            (fs/create-dirs (fs/parent f))
            (spit (str f) (project/edn-str (assoc (snapshot root cfg abs) :began (str (java.time.Instant/now)) :nanos (System/nanoTime))))
            {:result {:observe {:began cycle :role role :room room}}}))
      "end"
      (let [_ (when-not (and cycle role) (project/cannot-run! "observe end CYCLE ROLE [--tokens N]"))
            f (state-file root cfg cycle role)
            _ (when-not (fs/exists? f) (project/cannot-run! (str "no run of the role " role " began in cycle " cycle ": run rdm-spec observe begin first")))
            before (edn/read-string (slurp (str f)))
            room (:room before)
            now (snapshot root cfg room)
            cdir (str (:cycles-dir cfg) "/" cycle "/")
            skip (fn [p] (or (= p room) (str/starts-with? p (str room "/")) (= p (str root)) (= p (expand (get-in cfg [:agency :tmp])))))
            outside (new-entries (:watch before) (:watch now) skip)
            tmp-left (count (remove (set (:tmp before)) (:tmp now)))
            touched (vec (remove #(or (some #{%} (:repo before)) (str/starts-with? % cdir)) (:repo now)))
            results-in-room (boolean (or (seq (fs/glob (fs/path room (:results-dir cfg)) "*"))
                                         (seq (remove #(str/includes? (str %) "/.git/") (fs/glob room "**-result.json")))))
            tokens (some-> (get-in run [:opts "--tokens"]) parse-long)
            minutes (/ (Math/round (/ (- (System/nanoTime) (:nanos before)) 6e9)) 10.0)
            entry {:role role :room room :began (:began before) :ended (str (java.time.Instant/now)) :minutes minutes :tokens tokens
                   :defence {:writes-outside outside :tmp-left tmp-left :repo-touched touched
                             :results-in-room results-in-room :unobserved unobserved}}
            rf (fs/path root (:cycles-dir cfg) cycle "runs.edn")
            runs (if (fs/exists? rf) (edn/read-string (slurp (str rf))) [])]
        (spit (str rf) (project/pprint-str (conj (vec runs) entry)))
        (fs/delete f)
        {:warnings (vec (concat (for [p outside] (project/warning p nil "write-outside" (str "the run wrote " p ", outside its room")))
                                (when (pos? tmp-left) [(project/warning nil nil "tmp-left" (str "the run left " tmp-left " entries in the temporary directory"))])
                                (for [p touched] (project/warning p nil "repo-touched" "the run changed the project outside its cycle"))
                                (when results-in-room [(project/warning room nil "results-in-room" "the run wrote results inside its room")])))
         :result {:observe {:ended cycle :role role :breach (breach? (:defence entry))}}})
      "report"
      (let [[a b] [cycle role]
            _ (when-not (and a b) (project/cannot-run! "observe report CYCLE CYCLE"))
            read (fn [n] (let [rf (fs/path root (:cycles-dir cfg) n "runs.edn")]
                           (if (fs/exists? rf) (edn/read-string (slurp (str rf))) (project/cannot-run! (str "cycle " n " recorded no run")))))
            sa (sums (read a)) sb (sums (read b))]
        {:result {:observe {:cycles [a b]
                            :runs {a (:runs sa) b (:runs sb)}
                            :minutes {a (:minutes sa) b (:minutes sb)}
                            :tokens {a (:tokens sa) b (:tokens sb)}
                            :breaches {a (:breaches sa) b (:breaches sb)}}}})
      (project/cannot-run! (str "observe " sub " is no observe task: observe begin, end or report")))))
