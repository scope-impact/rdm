(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature.

  Context keys set here: :dir (the project: a copy of the room, a git
  repository with the cuts spec-3 and spec-4), :changed (the paths changed
  between them, relative to the project).

  The task's result, as the steps read it: {:scopes [scope] :shared [path]},
  a scope {:component :context/component}; each shared file is also a
  warning line naming it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- spec-path
  "A path as a step names it, relative to the project: one not starting
  with dhf/ is under the spec directory."
  [p]
  (if (str/starts-with? p "dhf/") p (str "dhf/spec/" p)))

(defstep #"the cuts spec-3 and spec-4, between which (?:only )?(.+) changed"
  (fn [ctx listed]
    (let [paths (mapv spec-path (remove str/blank? (map str/trim (str/split listed #",\s*|\s+and\s+"))))
          dir (rs/repo! (s/copy-of-room!))]
      (rs/commit! dir "the cut spec-3")
      (rs/tag! dir "spec-3")
      (doseq [p paths]
        (s/write! dir p (str (let [f (java.io.File. (str dir) p)] (when (.exists f) (slurp f)))
                             (if (str/ends-with? p ".edn") "\n;; changed\n" "\n# changed\n"))))
      (rs/commit! dir "the cut spec-4")
      (rs/tag! dir "spec-4")
      (s/check! (= (sort paths) (sort (str/split-lines (rs/git! dir "diff" "--name-only" "spec-3" "spec-4"))))
                "the cuts differ in other files")
      (assoc ctx :dir dir :changed paths))))

(defn- scopes-of [ctx]
  (let [r (kc/exits! ctx 0)
        out (kc/stdout-edn r)]
    (s/check! (vector? (:scopes out)) (str "the result holds no :scopes vector\n" (s/show r)))
    (:scopes out)))

(defn- scopes-text [text]
  (vec (map edn/read-string (re-seq #"\{[^}]*\}" text))))

(defstep #"it lists the scopes? (\{.*\})(?:, and nothing else)?"
  (fn [ctx text]
    (let [want (scopes-text text)
          got (scopes-of ctx)]
      (s/check! (= (set want) (set got)) (str "it lists " (pr-str got) ", not " (pr-str want) "\n" (s/show (:run ctx))))
      (s/check! (= (count got) (count (set got))) (str "it lists a scope twice: " (pr-str got)))
      nil)))

(defstep #"it lists no scope, and names (\S+) and (\S+) as changed shared files whose readers a person must judge"
  (fn [ctx a b]
    (let [got (scopes-of ctx)
          r (kc/the-run ctx)
          warnings (filter kc/warning-line? (s/err-lines r))]
      (s/check! (empty? got) (str "it lists " (pr-str got)))
      (doseq [f [a b]
              :let [full (first (filter #(str/ends-with? % (str "/" f)) (:changed ctx)))]]
        (s/check! (some #(and (str/starts-with? % (str full ": ")) (str/includes? % "a person must judge")) warnings)
                  (str "no warning names " full " as a changed shared file a person must judge\n" (s/show r))))
      nil)))
