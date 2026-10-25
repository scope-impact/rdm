(ns rdm-spec.project.hooks
  "Hooks (dhf/spec/project/hooks.feature): the pre-commit hook that runs
  `rdm-spec check` on every commit and refuses the commit when the check
  exits other than 0. Init and adopt write it into the skeleton
  (.githooks/pre-commit) and install it by setting git's core.hooksPath to
  .githooks."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def hooks-path ".githooks")

(def hook-file ".githooks/pre-commit")

(defn- git [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" args))

(defn toplevel
  "The top of the git repository `dir` lies in, or nil when it lies in none."
  [dir]
  (let [r (git dir "rev-parse" "--show-toplevel")]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn init-repository!
  "Makes `dir` a git repository; true when it is one."
  [dir]
  (zero? (:exit (git dir "init" "-q"))))

(defn hooks-path-of
  "The core.hooksPath of the repository `dir`, or nil when none is set."
  [dir]
  (let [r (git dir "config" "--local" "--get" "core.hooksPath")]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn install!
  "Points git's core.hooksPath at .githooks in the repository `dir`, unless
  another is already set: {:installed? bool :hooks-path the path in force}."
  [dir]
  (let [current (hooks-path-of dir)]
    (cond
      (= hooks-path current) {:installed? true :hooks-path current}
      current {:installed? false :hooks-path current}
      :else (let [r (git dir "config" "--local" "core.hooksPath" hooks-path)]
              {:installed? (zero? (:exit r)) :hooks-path (when (zero? (:exit r)) hooks-path)}))))
