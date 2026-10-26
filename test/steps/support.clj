(ns steps.support
  "The helpers every step file uses. No step is defined here.

  Fixtures: every directory a step makes (tmp-dir, copy-of-room!, project!)
  lies under one directory per run, the fixture root, removed when the run
  ends. test.clj names it (RDM_SPEC_FIXTURES) and removes it; a run without
  test.clj makes its own and removes it when the process exits.

  Commands: rdm-spec is run by path (bb -cp <room>/src), never one on PATH,
  in the directory the scenario names. A scenario's commands inherit the
  tests' environment, except RESULTS_DIR, plus what its steps set (a nil
  value unsets a variable).

  API: room-root, bb, fixture-root, tmp-dir, check!, fail!, write!,
  room-config, built?, needs-built!, copy-of-room!, project!, hooks-off!,
  env-for, command, rdm-spec!, err-lines, show, split-args.

  A fixture's git hooks never run (dhf/spec/README.md): a fixture made by
  init or adopt carries the skeleton's pre-commit hook, which runs whatever
  rdm-spec is on PATH, so a scenario that commits in one turns its hooks off
  (hooks-off!) unless it is about the hook; every commit a step makes passes
  --no-verify as well."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Where things are

(def room-root
  "The repository the tests run in (the room, or ratify's checkout): the
  directory holding test/steps/support.clj."
  (let [f (some-> *file* str not-empty fs/absolutize)]
    (str (if (and f (str/ends-with? (str f) "test/steps/support.clj"))
           (-> f fs/parent fs/parent fs/parent)
           (fs/absolutize ".")))))

(def bb
  "The bb executable these tests run under."
  (or (try (let [exe (str (fs/read-link "/proc/self/exe"))]
             (when (re-find #"(^|/)bb$" exe) exe))
           (catch Exception _ nil))
      (some-> (fs/which "bb") str)
      "bb"))

;; ---------------------------------------------------------------------------
;; Failing a step

(defn fail!
  "Fails the step, saying why."
  [msg]
  (throw (ex-info (str msg) {::failed true})))

(defn check!
  "Fails the step with `msg` unless `ok`; returns `ok`."
  [ok msg]
  (when-not ok (fail! msg))
  ok)

;; ---------------------------------------------------------------------------
;; Fixtures, all under one directory per run

(defonce ^:private own-root (atom nil))

(defn fixture-root
  "The directory every fixture of this run lies under."
  []
  (or (not-empty (System/getenv "RDM_SPEC_FIXTURES"))
      @own-root
      (locking own-root
        (or @own-root
            (let [d (str (fs/create-temp-dir {:prefix "rdm-spec-fixtures-"}))]
              (.addShutdownHook (Runtime/getRuntime)
                                (Thread. ^Runnable (fn [] (try (fs/delete-tree d) (catch Exception _ nil)))))
              (reset! own-root d))))))

(defn tmp-dir
  "A new empty directory under the fixture root."
  []
  (let [root (fixture-root)]
    (fs/create-dirs root)
    (str (fs/create-temp-dir {:dir root :prefix "fx-"}))))

(defn write!
  "Writes `text` to `rel` under `dir`, making its parents; returns the file."
  [dir rel text]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str text))
    (str f)))

;; ---------------------------------------------------------------------------
;; The room

(defn room-config
  "The room's dhf/harness/config.edn, read."
  []
  (edn/read-string (slurp (str (fs/file room-root "dhf/harness/config.edn")))))

(defn- ns-file [sym]
  (fs/file room-root "src" (str (-> (str sym) (str/replace "-" "_") (str/replace "." "/")) ".clj")))

(defn built?
  "Whether the namespace `sym` is built: its file is in the room's src/."
  [sym]
  (fs/regular-file? (ns-file sym)))

(defn needs-built!
  "Fails the step unless every namespace of `syms` is built."
  [& syms]
  (let [missing (remove built? syms)]
    (check! (empty? missing) (str "not built: " (str/join ", " missing))))
  nil)

(defn- write-config! [dir c]
  (write! dir "dhf/harness/config.edn" (str (pr-str c) "\n")))

(defn copy-of-room!
  "A new directory holding what a clean room holds: a copy of the room's
  kept paths (its :clean-room :keep), never the denied ones, and its
  configuration without :oracle: a fixture never carries the room's oracle
  unless its scenario sets one."
  []
  (let [dir (tmp-dir)
        cfg (room-config)]
    (doseq [a (get-in cfg [:clean-room :keep])
            :let [src (fs/file room-root a)
                  dst (fs/file dir a)]
            :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src dst {:replace-existing true})
        (do (fs/create-dirs (fs/parent dst))
            (fs/copy src dst {:replace-existing true}))))
    (write-config! dir (dissoc cfg :oracle))
    dir))

(defn project!
  "A new directory holding only the room's configuration, without :oracle,
  merged with `overrides`."
  ([] (project! {}))
  ([overrides]
   (let [dir (tmp-dir)]
     (write-config! dir (merge (dissoc (room-config) :oracle) overrides))
     dir)))

;; ---------------------------------------------------------------------------
;; Git hooks

(defn hooks-off!
  "Turns the git hooks of the repository `dir` off: core.hooksPath names a
  new empty directory under the fixture root, so no hook runs, the
  skeleton's pre-commit hook included. Returns `dir`."
  [dir]
  (let [empty-hooks (tmp-dir)
        r (p/shell {:dir (str dir) :out :string :err :string :continue true :in ""}
                   "git" "config" "--local" "core.hooksPath" empty-hooks)]
    (check! (zero? (:exit r)) (str "the hooks of " dir " cannot be turned off: " (:err r)))
    dir))

;; ---------------------------------------------------------------------------
;; Running rdm-spec

(defn env-for
  "The environment of a scenario's command: the tests' own without
  RESULTS_DIR, with `extra` ({name value}, a nil value unsetting it)."
  [extra]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m (str k) (str v))))
             (dissoc (into {} (System/getenv)) "RESULTS_DIR")
             (or extra {})))

(defn command
  "Runs rdm-spec `args` in `dir` with the environment (env-for `extra-env`):
  {:dir :args :exit :out :err}."
  [dir args extra-env]
  (let [args (mapv str args)
        cmd (concat [bb (str "-Djava.io.tmpdir=" (fixture-root)) "-cp" (str (fs/file room-root "src"))
                     "-m" "rdm-spec.kernel.command"]
                    args)
        r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :env (env-for extra-env)}
                 cmd)]
    {:dir (str dir) :args args :exit (:exit r) :out (:out r) :err (:err r)}))

(defn rdm-spec!
  "Runs `rdm-spec args...` in `dir`: {:dir :args :exit :out :err}."
  [dir & args]
  (command dir args nil))

(defn err-lines
  "The lines of a run's error stream."
  [run]
  (vec (remove str/blank? (str/split-lines (str (:err run))))))

(defn show
  "A run, as a failure message shows it."
  [run]
  (if (map? run)
    (str "$ rdm-spec " (str/join " " (:args run)) "   (in " (:dir run) ")\n"
         "exit " (:exit run) "\n"
         "--- standard output\n" (:out run)
         (when-not (str/ends-with? (str (:out run)) "\n") "\n")
         "--- error stream\n" (:err run))
    (pr-str run)))

(defn split-args
  "The words of a command line, as a shell splits them: single or double
  quotes keep a word's spaces."
  [line]
  (let [s (str line) n (count s)]
    (loop [i 0 cur nil quote-ch nil out []]
      (if (>= i n)
        (cond-> out cur (conj cur))
        (let [c (.charAt s i)]
          (cond
            quote-ch (if (= c quote-ch)
                       (recur (inc i) (or cur "") nil out)
                       (recur (inc i) (str cur c) quote-ch out))
            (#{\' \"} c) (recur (inc i) (or cur "") c out)
            (Character/isWhitespace c) (recur (inc i) nil nil (cond-> out cur (conj cur)))
            :else (recur (inc i) (str cur c) nil out)))))))
