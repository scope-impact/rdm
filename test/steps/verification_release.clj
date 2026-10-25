(ns steps.verification-release
  "Step definitions for dhf/spec/verification/release.feature: `rdm-spec
  release` in a small project with its own spec, its Allure results written
  by hand."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

(defn- project-of
  "A project holding three stamped scenarios, `special` (an id) among them
  carrying `tags`, and a passing result for every other one."
  [ctx special tags]
  (let [ids ["spec-0001" "spec-0002" "spec-0003"]
        dir (fx/one-feature (str/join "\n" (for [id ids]
                                             (fx/scenario-text {:id id :tags (when (= id special) tags)}))))]
    (reduce #(fx/add-result! %1 %2 "passed")
            (assoc ctx :dir dir :id special)
            (remove #{special} ids))))

(defn- err-line-naming
  "The error stream's line naming every one of `words`, or nil."
  [r words]
  (first (filter (fn [l] (every? #(str/includes? l %) words)) (s/err-lines r))))

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [ctx]
    (project-of ctx "spec-0002" ["@wip"])))

(defstep #"it exits 1 and names the scenario's id"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (err-line-naming r [(:id ctx)]) (str "no finding names " (:id ctx) "\n" (s/show r)))
      (s/check! (= 1 (count (s/err-lines r))) (str "other scenarios are named\n" (s/show r))))))

(defstep #"a stamped scenario whose result failed"
  (fn [ctx]
    (-> (project-of ctx "spec-0002" [])
        (fx/add-result! "spec-0002" "failed")
        (assoc :status "failed"))))

(defstep #"a stamped scenario with no result"
  (fn [ctx]
    (assoc (project-of ctx "spec-0002" []) :status "unverified")))

(defstep #"it exits 1 and names the scenario's id and its status"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (err-line-naming r [(:id ctx) (:status ctx)])
                (str "no finding names " (:id ctx) " and " (:status ctx) "\n" (s/show r)))
      (s/check! (= 1 (count (s/err-lines r))) (str "other scenarios are named\n" (s/show r))))))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [ctx]
    (let [ctx (-> (project-of ctx "spec-0002" [])
                  (fx/add-result! "spec-0002" "passed"))]
      (s/check! (empty? (fx/scenario-ids (:dir ctx) #(some #{"@wip"} (:all-tags %)))) "a scenario is tagged @wip")
      ctx)))
