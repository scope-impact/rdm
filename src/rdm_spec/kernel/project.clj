(ns rdm-spec.kernel.project
  "Kernel helpers every context may use: the project's root and configuration,
  how a task refuses, how a finding is written, and running a command with a
  time limit. Kernel code, in no bounded context (ADR 0002)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint]))

;; ------------------------------------------------------------------ refusing

(defn cannot-run!
  "A task that cannot run as asked: exit 2, `cannot-run: why` on the error stream."
  [why & {:as data}]
  (throw (ex-info (str why) (merge {:rdm-spec/exit 2} data))))

(defn one-line [s] (str/trim (str/replace (str s) #"\s*[\r\n]+\s*" " ")))

;; ------------------------------------------------------------------ findings

(defn finding
  "A finding {:file :line :rule :message}; file and line may be nil."
  ([file line rule message] {:file file :line line :rule rule :message (one-line message)})
  ([file rule message] (finding file nil rule message)))

(defn warning [file line rule message] (assoc (finding file line rule message) :warning? true))

(defn finding-line
  "FILE:LINE: RULE: what, FILE: RULE: what, or RULE: what; a warning carries `warning: ` before its rule."
  [{:keys [file line rule message warning? raw]}]
  (or raw
      (let [head (cond (and file line) (str file ":" line ": ")
                       file (str file ": ")
                       :else "")]
        (str head (when warning? "warning: ") rule ": " message))))

;; ------------------------------------------------------------------ environment

(defn tmp-root [] (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))

(defn temp-dir [prefix] (str (fs/create-temp-dir {:dir (tmp-root) :prefix prefix})))

(defn today [] (str (java.time.LocalDate/now)))

;; ------------------------------------------------------------------ EDN

(defn edn-str [x] (binding [*print-namespace-maps* false *print-length* nil *print-level* nil] (pr-str x)))

(defn pprint-str [x]
  (binding [*print-namespace-maps* false]
    (with-out-str (clojure.pprint/pprint x))))

(defn read-edn-file
  "Reads one EDN form from a file; throws ex-info {:line :message} when it cannot."
  [path]
  (let [text (slurp (str path))]
    (try (edn/read-string text)
         (catch Exception e
           (let [m (ex-message e)
                 line (some-> (re-find #"line (\d+)" (str m)) second parse-long)]
             (throw (ex-info (one-line m) {:line (or line 1) :file (str path)})))))))

;; ------------------------------------------------------------------ the configuration

(def config-path "dhf/harness/config.edn")

(def defaults
  {:id-prefix "spec" :spec-dir "dhf/spec" :views-dir "dhf/views" :raw-dir "dhf/raw" :wiki-dir "dhf/wiki"
   :steps-dir "test/steps" :results-dir "dhf/allure-results" :cycles-dir "dhf/cycles"
   :stack-words [] :implementation []
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :c4 {:view-element-limit 20 :description-max-chars 45}
   :agency {:watch ["/" "~"] :tmp "/tmp"}})

(defn with-defaults [cfg]
  (let [c (merge defaults cfg)
        sd (:spec-dir c)]
    (merge {:ledger (str sd "/ids.edn") :model (str sd "/model.edn") :events (str sd "/events.edn")
            :needs (str sd "/needs.edn")}
           c
           {:c4 (merge (:c4 defaults) (:c4 cfg))})))

(defn read-config
  "The configuration in DIR (the project's root), with defaults. Cannot run without one."
  [dir]
  (let [f (fs/path dir config-path)]
    (when-not (fs/exists? f)
      (cannot-run! (str "no " config-path " here: rdm-spec runs in a project's root, the folder that holds its configuration")))
    (let [cfg (try (edn/read-string (slurp (str f)))
                   (catch Exception e (cannot-run! (str config-path " cannot be read: " (one-line (ex-message e))))))]
      (when-not (map? cfg) (cannot-run! (str config-path " is not a map")))
      (with-defaults cfg))))

;; ------------------------------------------------------------------ processes

(defn- kill-tree [proc]
  (try (p/destroy-tree proc) (catch Exception _ nil))
  (try (p/destroy proc) (catch Exception _ nil)))

(defn run-cmd
  "Runs argv in dir with stdin `in` and extra env, for at most `seconds` (nil = no limit).
  Returns {:exit :out :err :timed-out? :not-found? :seconds}."
  [argv {:keys [dir in env seconds]}]
  (let [start (System/nanoTime)]
    (try
      (let [proc (p/process (vec argv) {:dir (str (or dir ".")) :in (or in "")
                                        :out :string :err :string
                                        :extra-env (or env {})})
            fin (if seconds
                  (deref (future (deref proc)) (long (* 1000 seconds)) ::timeout)
                  @proc)]
        (if (= fin ::timeout)
          (do (kill-tree proc)
              (let [r (deref (future (deref proc)) 2000 nil)]
                {:exit nil :timed-out? true
                 :out (str (when r (:out r))) :err (str (when r (:err r)))
                 :seconds (/ (- (System/nanoTime) start) 1e9)}))
          {:exit (:exit fin) :out (str (:out fin)) :err (str (:err fin))
           :seconds (/ (- (System/nanoTime) start) 1e9)}))
      (catch java.io.IOException e
        {:exit nil :not-found? true :out "" :err (ex-message e) :seconds 0})
      (catch Exception e
        (if (re-find #"(?i)no such file|cannot run program|error=2" (str (ex-message e) (some-> e ex-cause ex-message)))
          {:exit nil :not-found? true :out "" :err (str (ex-message e)) :seconds 0}
          (throw e))))))

(defn sh
  "Runs a command to completion; returns {:exit :out :err}."
  [dir & argv]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} (map str argv))]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn git
  "git in dir, hooks off; returns {:exit :out :err}."
  [dir & args]
  (apply sh dir "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
         "-c" "user.name=rdm-spec" "-c" "user.email=rdm-spec@localhost" args))

(defn git!
  "git in dir; cannot run when git fails."
  [dir & args]
  (let [r (apply git dir args)]
    (when-not (zero? (:exit r))
      (cannot-run! (str "git " (str/join " " args) ": " (one-line (:err r)))))
    (:out r)))

;; ------------------------------------------------------------------ files

(defn rel
  "path relative to root, with / separators."
  [root path]
  (str/replace (str (fs/relativize (fs/absolutize root) (fs/absolutize path))) "\\" "/"))

(defn files-under
  "Every regular file under dir (relative to root), sorted."
  [root dir & [ext]]
  (let [d (fs/path root dir)]
    (if (fs/directory? d)
      (->> (fs/glob d (if ext (str "**." ext) "**") {:hidden true})
           (filter fs/regular-file?)
           (map #(rel root %))
           sort vec)
      [])))

(defn line-of
  "The 1-based line of the first occurrence of needle in text at or after line `from`, else nil."
  ([text needle] (line-of text needle 1))
  ([text needle from]
   (let [lines (str/split-lines (str text))]
     (some (fn [i] (when (str/includes? (nth lines i) needle) (inc i)))
           (range (max 0 (dec from)) (count lines))))))
