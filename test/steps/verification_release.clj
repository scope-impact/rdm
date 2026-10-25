(ns steps.verification-release
  "Step definitions for dhf/spec/verification/release.feature: `rdm-spec
  release` in a small project with its own spec and its Allure results
  written by hand; under the regulated profile, with its own clause list and
  documents."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

(defn- scenarios-text [scs] (str/join "\n" (map fx/scenario-text scs)))

(defn- passing!
  "A passing result for each of `ids`."
  [ctx ids]
  (reduce #(fx/add-result! %1 %2 "passed") ctx ids))

(defn- regulated!
  "Makes the project `dir` regulated: the clause list dhf/clauses/std.txt
  holding `clauses` and the documents `docs` {rel text}."
  [dir clauses docs]
  (let [cfg-rel "dhf/harness/config.edn"
        cfg (edn/read-string (slurp (str (fs/file dir cfg-rel))))]
    (s/write! dir cfg-rel (pr-str (assoc cfg :regulated {:clause-lists ["dhf/clauses/std.txt"]
                                                         :documents "dhf/documents"})))
    (s/write! dir "dhf/clauses/std.txt" clauses)
    (doseq [[rel text] docs] (s/write! dir rel text))
    dir))

(defn- document [front body]
  (str "---\n" (str/join (for [[k v] front] (str k ": " v "\n"))) "---\n\n" body))

(def ^:private plan
  (document [["id" "PLAN-001"] ["revision" "1"] ["title" "Development plan"]]
            "# Plan\n\nWe plan the work [[STD:5.1]].\n"))

(defn- line-holding! [r & words]
  (s/check! (some (fn [l] (every? #(str/includes? l %) words)) (s/err-lines r))
            (str "no line of the error stream names " (str/join ", " words) "\n" (s/show r))))

;; --- A release needs every scenario verified ---------------------------------

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [ctx]
    (-> (assoc ctx :id "spec-0001"
               :dir (fx/one-feature (scenarios-text [{:id "spec-0001" :tags ["@wip"]} {:id "spec-0002"}])))
        (passing! ["spec-0002"]))))

(defstep #"a stamped scenario whose result failed"
  (fn [ctx]
    (-> (assoc ctx :id "spec-0001" :status "failed"
               :dir (fx/one-feature (scenarios-text [{:id "spec-0001"} {:id "spec-0002"}])))
        (fx/add-result! "spec-0001" "failed")
        (passing! ["spec-0002"]))))

(defstep #"a stamped scenario with no result"
  (fn [ctx]
    (-> (assoc ctx :id "spec-0001" :status "unverified"
               :dir (fx/one-feature (scenarios-text [{:id "spec-0001"} {:id "spec-0002"}])))
        (passing! ["spec-0002"]))))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [ctx]
    (-> (assoc ctx :dir (fx/one-feature (scenarios-text [{:id "spec-0001"} {:id "spec-0002"}])))
        (passing! ["spec-0001" "spec-0002"]))))

(defstep #"it exits 1 and names the scenario's id"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (line-holding! r (:id ctx))
      nil)))

(defstep #"it exits 1 and names the scenario's id and its status"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (line-holding! r (:id ctx) (:status ctx))
      nil)))

;; --- Under the regulated profile, a release needs every clause named ---------

(defstep #"a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names"
  (fn [ctx]
    (let [ctx (-> (assoc ctx :dir (fx/one-feature (scenarios-text [{:id "spec-0001"} {:id "spec-0002"}])))
                  (passing! ["spec-0001" "spec-0002"]))]
      (regulated! (:dir ctx) "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n"
                  {"dhf/documents/plan.md" plan})
      (assoc ctx :clause "STD:5.2" :clause-list "dhf/clauses/std.txt"))))

(defstep #"it exits 1 and names the clause and its clause list"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (line-holding! r (:clause ctx) (:clause-list ctx))
      (s/check! (not-any? #(str/includes? % "STD:5.1 ") (s/err-lines r))
                (str "a named clause is reported\n" (s/show r)))
      nil)))

(defstep #"a regulated project whose every stamped scenario has a passing result and every clause is named, and a document with no id in its frontmatter"
  (fn [ctx]
    (let [ctx (-> (assoc ctx :dir (fx/one-feature (scenarios-text [{:id "spec-0001"} {:id "spec-0002"}])))
                  (passing! ["spec-0001" "spec-0002"]))]
      (regulated! (:dir ctx) "STD:5.1 Development plan\n"
                  {"dhf/documents/plan.md" plan
                   "dhf/documents/notes.md" (document [["revision" "1"] ["title" "Notes"]] "Some notes.\n")})
      (assoc ctx :document "dhf/documents/notes.md"))))

(defstep #"it exits 1 and names the document"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (line-holding! r (:document ctx))
      nil)))
