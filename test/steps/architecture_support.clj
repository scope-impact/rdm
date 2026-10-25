(ns steps.architecture-support
  "Helpers the architecture context's step definitions share: a copy of the
  room whose model a step edits, and reading findings off a run."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [steps.support :as s]))

(def model-rel "dhf/spec/model.edn")
(def events-rel "dhf/spec/events.edn")

(defn needs-check!
  "`rdm-spec check` is owned by the spec context; while it is not built the
  command cannot run check at all, so nothing about check can be verified."
  []
  (s/needs-built! 'rdm-spec.spec.check))

(defn dir-of
  "The project of the scenario: the one a step already made, else a fresh copy
  of the room's spec."
  [ctx]
  (or (:dir ctx) (s/copy-of-room!)))

(defn read-model [dir]
  (edn/read-string (slurp (str (fs/file dir model-rel)))))

(defn write-model! [dir m]
  (s/write! dir model-rel
        (with-out-str (binding [pprint/*print-right-margin* 110] (pprint/pprint m)))))

(defn edit-model
  "A copy of the room (or the scenario's project) whose model is `(f model)`."
  [ctx f]
  (needs-check!)
  (let [dir (dir-of ctx)]
    (write-model! dir (f (read-model dir)))
    (assoc ctx :dir dir)))

(defn update-where
  "Updates every map in the vector `xs` for which `pred` holds."
  [xs pred f]
  (mapv #(if (pred %) (f %) %) xs))

(defn update-context
  "Updates the bounded context `ctx-id` of every container."
  [m ctx-id f]
  (update m :containers
          (fn [cs] (mapv (fn [c] (if (:contexts c)
                                   (update c :contexts (fn [ctxs] (update-where (vec ctxs) #(= ctx-id (:id %)) f)))
                                   c))
                         cs))))

(defn rule-lines
  "The error-stream lines of a run that report `rule`."
  [run rule]
  (filterv #(str/includes? % (str ": " rule ": ")) (s/err-lines run)))

(defn check-names!
  "Fails unless the run exited `exit` and some line under `rule` names every
  string of `names`."
  [run exit rule names]
  (s/check! (= exit (:exit run)) (str "expected exit " exit "\n" (s/show run)))
  (let [lines (rule-lines run rule)]
    (s/check! (seq lines) (str "no finding under rule " rule "\n" (s/show run)))
    (s/check! (some (fn [l] (every? #(str/includes? l %) names)) lines)
              (str "no " rule " finding names all of " (pr-str names) "\n" (s/show run)))))

(defn stdout-edn [run]
  (try (edn/read-string (:out run))
       (catch Exception e (s/fail! (str "stdout is not EDN: " (ex-message e) "\n" (s/show run))))))
