(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature.

  A scopes fixture is a throwaway repository holding a copy of the room's
  allow-listed paths, committed and tagged as the earlier cut; the files the
  Given names are then changed (a comment line appended) and nothing else,
  committed and tagged as the later cut. The context keeps :dir (the
  repository, where the shared When runs the command) and :changed (the
  changed paths, from the repository's root).

  Until the kernel's task table has the task scopes, the command refuses it
  as unknown; the Thens then mark the scenario broken (it cannot be verified
  yet), never failed."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- needs-scopes! [] (s/needs-built! 'rdm-spec.regeneration.scopes 'rdm-spec.regeneration.cut))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- spec-dir [] (str/replace (:spec-dir (s/room-config)) #"/+$" ""))

(defn- repo-path
  "The path `p` names from the repository's root: as given when it starts
  with dhf/, else under the spec directory."
  [p]
  (if (str/starts-with? p "dhf/") p (str (spec-dir) "/" p)))

(defn- comment-line [p]
  (cond (str/ends-with? p ".edn") ";; changed between the cuts\n"
        (str/ends-with? p ".md") "\n<!-- changed between the cuts -->\n"
        :else "# changed between the cuts\n"))

(defn- fixture!
  "ctx with a repository whose cut `from` holds the room's allow-list and
  whose cut `to` changes only `paths`."
  [ctx from to paths]
  (needs-scopes!)
  (let [dir (s/copy-of-room!)
        changed (mapv repo-path paths)]
    (rs/git! dir "init" "-q")
    (rs/commit! dir (str "the spec at " from))
    (rs/git! dir "tag" from)
    (doseq [p changed]
      (s/check! (fs/regular-file? (fs/file dir p)) (str "the room holds no " p " to change"))
      (spit (str (fs/file dir p)) (comment-line p) :append true))
    (rs/commit! dir (str "the spec at " to))
    (rs/git! dir "tag" to)
    (let [diff (set (rs/lines (rs/git! dir "diff" "--name-only" from to)))]
      (s/check! (= (set changed) diff) (str "the fixture changed " (sort diff) ", not " changed)))
    (assoc ctx :dir dir :changed changed)))

(defn- listed!
  "The run's result: it exited 0 and printed EDN. Broken while the kernel
  has no task scopes."
  [ctx]
  (let [r (the-run ctx)]
    (when (and (= 2 (:exit r)) (str/includes? (str (:err r)) "unknown task scopes"))
      (throw (ex-info "cannot be verified yet: the kernel's task table has no task scopes"
                      {:rdm-spec.kernel.runner/broken true})))
    (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
    (let [out (kc/stdout-edn r)]
      (s/check! (map? out) (str "the standard output is not an EDN map\n" (s/show r)))
      out)))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"the cuts (spec-\d+) and (spec-\d+), between which (?:only )?(\S+) and (\S+) changed"
  (fn [ctx from to a b] (fixture! ctx from to [a b])))

;; ---------------------------------------------------------------------------
;; Thens

(defn- scope-maps
  "The EDN maps written in `s`."
  [s]
  (mapv edn/read-string (re-seq #"\{[^}]*\}" s)))

(defstep #"it lists the scopes (.+), and nothing else"
  (fn [ctx listed]
    (let [out (listed! ctx)
          expected (scope-maps listed)]
      (s/check! (seq expected) (str "the step names no scope: " listed))
      (s/check! (= (set expected) (set (:scopes out))) (str "expected the scopes " expected "\n" (s/show (the-run ctx))))
      (s/check! (= (count expected) (count (:scopes out))) (str "a scope is listed twice\n" (s/show (the-run ctx))))
      ctx)))

(defstep #"it lists no scope, and names (\S+) and (\S+) as changed shared files whose readers a person must judge"
  (fn [ctx a b]
    (let [r (the-run ctx)
          out (listed! ctx)
          shared (vec (:shared out))
          lines (s/err-lines r)]
      (s/check! (empty? (:scopes out)) (str "expected no scope\n" (s/show r)))
      (doseq [n [a b]]
        (let [p (first (filter #(= n (str (fs/file-name %))) shared))]
          (s/check! p (str n " is not among the shared files " shared "\n" (s/show r)))
          (s/check! (some #(and (str/starts-with? % (str p ":")) (str/includes? % "warning")) lines)
                    (str "the error stream does not name " p " for a person\n" (s/show r)))))
      (s/check! (= 2 (count shared)) (str "expected exactly two shared files: " shared "\n" (s/show r)))
      ctx)))
