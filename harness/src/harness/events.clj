(ns harness.events
  "Domain events, after the PensionBee DDD workshop (CONTEXT.md, \"Domain
  event\"): a map of a :name and a :message. A name with a slash is a fail
  event, the business rule it broke after the slash; one with \"Warned\" before
  the slash is a warning, which never fails a command."
  (:require [clojure.string :as str]))

(defn event [name message] {:name name :message message})

(defn warning? [{:keys [name]}] (str/includes? name " Warned / "))

(defn fail? [{:keys [name] :as e}] (and (str/includes? name " / ") (not (warning? e))))

(defn report!
  "Print each event on standard error as `[Name] message`."
  [events]
  (binding [*out* *err*]
    (doseq [{:keys [name message]} events]
      (println (str "[" name "] " message)))
    (flush)))

(defn conclude!
  "Report the events and exit: 1 when any is a fail event, else 0. A command
  applies its success (writes its file) before it concludes."
  [events]
  (report! events)
  (System/exit (if (some fail? events) 1 0)))
