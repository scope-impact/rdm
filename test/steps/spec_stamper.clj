(ns steps.spec-stamper
  "Step definitions of dhf/spec/spec/stamper.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def core "dhf/spec/kernel/core.feature")

(defn append-scenario! [ctx text]
  (let [f (fs/file (k/guard! (:dir ctx)) core)]
    (spit f (str (slurp f) "\n" text))
    ctx))

(defn tags-above
  "The tags on the line above the scenario titled `title`, in `file`."
  [ctx file title]
  (let [ls (str/split-lines (slurp (fs/file (k/guard! (:dir ctx)) file)))
        i (first (keep-indexed (fn [i l] (when (str/includes? l (str "Scenario: " title)) i)) ls))]
    (when-not i (fail! (str "no scenario " title " in " file)))
    (loop [j (dec i)]
      (cond (< j 0) ""
            (str/starts-with? (str/trim (nth ls j)) "@") (str/trim (nth ls j))
            (str/blank? (nth ls j)) ""
            :else (recur (dec j))))))

(defn ledger [ctx] (:issued (edn/read-string (slurp (fs/file (k/guard! (:dir ctx)) "dhf/spec/ids.edn")))))

(defstep #"the ledger records (\d+) as the highest id issued"
  (fn [ctx n]
    (if (contains? ctx :exit)
      (do (assert (= (parse-long n) (ledger ctx)) (ledger ctx)) ctx)
      (k/project! ctx {:issued (parse-long n)}))))

(defstep #"a scenario in the spec carries no id tag"
  (fn [ctx] (append-scenario! (k/project! ctx) "  @e2e\n  Scenario: Unstamped\n    Given a step\n")))

(defstep #"the scenario is tagged @spec-0042"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [t (tags-above ctx core "Unstamped")]
      (assert (some #{"@spec-0042"} (str/split t #"\s+")) t))
    ctx))

(defstep #"every scenario in the spec carries an id"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (assoc ctx :spec-before (k/spec-snapshot ctx)))))

(defstep #"the ledger does not change"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= 100 (ledger ctx)))
    ctx))

(defstep #"the scenario tagged @spec-0042 has been deleted"
  (fn [ctx]
    (let [f (fs/file (k/guard! (:dir ctx)) core)]
      (assert (not (str/includes? (slurp f) "@spec-0042")))
      ctx)))

(defstep #"a new scenario carries no id tag"
  (fn [ctx] (append-scenario! ctx "  @e2e\n  Scenario: New\n    Given a step\n")))

(defstep #"the new scenario is tagged @spec-0043"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [t (tags-above ctx core "New")]
      (assert (some #{"@spec-0043"} (str/split t #"\s+")) t))
    ctx))

(defstep #"a scenario tagged @spec-0007 in one component's feature file"
  (fn [ctx]
    (k/project! ctx {:issued 7
                     :model-spec {:kernel ["core" "other"]}
                     :features {core (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 7 "Seven")]})
                                "dhf/spec/kernel/other.feature" (k/feature-text {:name "Other" :tags ["UN-001"] :scenarios [(k/sc 1 "One")]})}})))

(defstep #"its title is reworded and it is moved to another component's feature file"
  (fn [ctx]
    (k/write-files! (:dir ctx) {core (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios []})
                                "dhf/spec/kernel/other.feature"
                                (k/feature-text {:name "Other" :tags ["UN-001"] :scenarios [(k/sc 1 "One") (k/sc 7 "Seven, reworded")]})})
    ctx))

(defstep #"it is still tagged @spec-0007"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [t (tags-above ctx "dhf/spec/kernel/other.feature" "Seven, reworded")]
      (assert (= "@spec-0007 @e2e" t) t))
    (assert (= 7 (ledger ctx)))
    ctx))

(defstep #"the project's configuration sets the id prefix to \"loyly\""
  (fn [ctx]
    (let [ctx (k/project! ctx {:features {core (k/feature-text {:name "Core" :tags ["UN-001"]
                                                                 :scenarios [{:tags ["loyly-0001" "e2e"] :title "One" :steps ["Given a step"]}]})}
                               :issued 1})]
      (k/update-edn! ctx "dhf/harness/config.edn" assoc :id-prefix "loyly"))))

(defstep #"the scenario is tagged with an id starting @loyly-"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [t (tags-above ctx core "Unstamped")]
      (assert (some #(re-matches #"@loyly-\d{4,}" %) (str/split t #"\s+")) t))
    ctx))
