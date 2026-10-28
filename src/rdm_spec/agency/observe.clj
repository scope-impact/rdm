(ns rdm-spec.agency.observe
  "Component Observe (agency context): a run of a cycle, a builder's, a
  grader's or the integrator's, observed from outside, begin to end: its
  time, its cost, and its defence, what it touched outside its room (the
  watched directories' entries, the temporary directory's, the project
  outside its cycle, results written inside the room). It records, never
  prevents, never stops a run; reads are not observed, and the record says
  so. Two cycles' runs are set side by side, never netted."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(defn expand [p]
  (let [home (or (project/env "HOME") (System/getProperty "user.home"))]
    (cond (= "~" p) home
          (str/starts-with? (str p) "~/") (str home (subs p 1))
          :else (project/abs p))))

(defn entries [dir]
  (if (fs/directory? dir)
    (vec (sort (for [f (try (fs/list-dir dir) (catch Exception _ []))] (str (fs/file-name f)))))
    []))

(defn repo-status []
  (let [r (project/git (project/root) "status" "--porcelain=v1" "-z" "--untracked-files=all")]
    (if (= 0 (:exit r))
      (vec (sort (for [e (str/split (:out r) #"\u0000") :when (> (count e) 3)] e)))
      [])))

(defn state-file [cfg cycle role] (str (:cycles-dir cfg) "/" cycle "/observing-" role ".edn"))

(defn now-iso [] (str (java.time.Instant/ofEpochMilli (System/currentTimeMillis))))

(defn agency [cfg] (merge {:watch ["/" "~"] :tmp "/tmp"} (:agency cfg)))

(defn begin! [cfg cycle role room]
  (let [{:keys [watch tmp]} (agency cfg)
        st {:role role :room room :began (now-iso) :began-ms (System/currentTimeMillis)
            :watch (into {} (for [w watch] [w (entries (expand w))]))
            :tmp (entries (expand tmp))
            :repo (repo-status)}
        f (project/abs (state-file cfg cycle role))]
    (fs/create-dirs (fs/parent f))
    (spit f (pr-str st))
    (project/report {:observe {:began {:cycle cycle :role role :room room :at (:began st)}}})))

(defn results-in-room? [cfg room]
  (let [d (project/abs room (:results-dir cfg))]
    (boolean (or (and (fs/directory? d) (seq (fs/list-dir d)))
                 (and (fs/directory? room)
                      (some #(not (str/includes? (str %) "/.git/"))
                            (fs/glob room "**-result.json")))))))

(defn end! [cfg cycle role tokens]
  (let [f (project/abs (state-file cfg cycle role))]
    (when-not (fs/exists? f)
      (project/cannot-run! (str "no run of the role " role " began in cycle " cycle ": run rdm-spec observe begin " cycle " " role " ROOM first")))
    (let [st (edn/read-string (slurp f))
          {:keys [tmp]} (agency cfg)
          room (project/abs (:room st))
          ended-ms (System/currentTimeMillis)
          writes (vec (sort (for [[w before] (:watch st)
                                  :let [d (expand w) now (entries d)]
                                  n (remove (set before) now)
                                  :let [p (str (fs/path d n))]
                                  :when (not= (str (fs/normalize (fs/path p))) (str (fs/normalize (fs/path room))))]
                              p)))
          tmp-left (count (remove (set (:tmp st)) (entries (expand tmp))))
          cycle-dir (str (:cycles-dir cfg) "/" cycle "/")
          prefix (project/git-prefix-or-blank)
          touched (vec (sort (distinct (for [e (remove (set (:repo st)) (repo-status))
                                             :let [p (subs e 3)
                                                   p (if (str/starts-with? p prefix) (subs p (count prefix)) p)]
                                             :when (not (str/starts-with? p cycle-dir))]
                                         p))))
          in-room? (results-in-room? cfg room)
          run {:role role :room (:room st) :began (:began st) :ended (str (java.time.Instant/ofEpochMilli ended-ms))
               :minutes (/ (Math/round (/ (- ended-ms (:began-ms st)) 600.0)) 100.0)
               :tokens tokens
               :defence {:writes-outside writes :tmp-left tmp-left :repo-touched touched :results-in-room in-room?
                         :unobserved ["reads outside the room: observing records writes, never reads, so a read is not seen"]}}
          runs-file (project/abs (str (:cycles-dir cfg) "/" cycle "/runs.edn"))
          runs (if (fs/exists? runs-file) (let [v (edn/read-string (slurp runs-file))] (if (vector? v) v [])) [])]
      (spit runs-file (str "[" (str/join "\n " (map pr-str (conj runs run))) "]\n"))
      (fs/delete f)
      (project/report {:observe {:ended (dissoc run :defence) :defence (:defence run)}}
                      []
                      (concat (for [w writes] (project/warning w "observe-write-outside" (str "the " role " run wrote " w " outside its room")))
                              (when (pos? tmp-left) [(project/warning "observe-tmp-left" (str "the " role " run left " tmp-left " entries in the temporary directory"))])
                              (for [p touched] (project/warning p "observe-repo-touched" (str "the " role " run changed " p " in the project, outside its cycle")))
                              (when in-room? [(project/warning (:room st) "observe-results-in-room" (str "the " role " run wrote results inside its room"))]))))))

(defn breach? [run]
  (let [d (:defence run)]
    (boolean (or (seq (:writes-outside d)) (pos? (or (:tmp-left d) 0)) (seq (:repo-touched d)) (:results-in-room d)))))

(defn report [cfg a b]
  (let [runs (fn [c] (let [f (project/abs (str (:cycles-dir cfg) "/" c "/runs.edn"))]
                       (if (fs/exists? f) (edn/read-string (slurp f)) [])))
        ra (runs a) rb (runs b)
        sum (fn [rs k] (reduce + 0 (keep k rs)))]
    (project/report {:observe {:cycles [a b]
                               :runs {a (count ra) b (count rb)}
                               :minutes {a (sum ra :minutes) b (sum rb :minutes)}
                               :tokens {a (sum ra :tokens) b (sum rb :tokens)}
                               :breaches {a (count (filter breach? ra)) b (count (filter breach? rb))}}})))

(defn observe-task [{:keys [cfg args opts]}]
  (let [[verb & more] args]
    (case verb
      "begin" (let [[cycle role room] more]
                (when-not room (project/cannot-run! "observe begin CYCLE ROLE ROOM"))
                (begin! cfg cycle role room))
      "end" (let [[cycle role] more
                  tokens (some-> (get opts "--tokens") parse-long)]
              (when (and (get opts "--tokens") (nil? tokens)) (project/cannot-run! "--tokens is a number of tokens"))
              (end! cfg cycle role tokens))
      "report" (let [[a b] more] (report cfg a b))
      (project/cannot-run! (str "unknown observe task " verb ": observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE")))))
