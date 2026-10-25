(ns steps.spec-stamper
  "Step definitions for dhf/spec/spec/stamper.feature: `rdm-spec stamp` in a
  spec project (steps.spec-support). Context keys: see steps.spec-support."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.spec-support :as sp]
            [steps.support :as s]))

(def ^:private stamped {:tags ["@spec-0001" "@e2e"] :title "A stamped scenario"})

(defn- project-with-ledger [n]
  (sp/spec-project! {:features {"desk" (sp/feature-text "Desk" [stamped])} :ledger n}))

(defn- add-scenario!
  "Appends the scenario `sc` to the desk feature of the project `dir`."
  [dir sc]
  (let [f (fs/file dir (sp/feature-rel "desk"))]
    (spit (str f) (str (slurp (str f)) "\n" (sp/scenario-text sc)))))

(defn- ids-of [sc prefix]
  (filterv #(re-matches (re-pattern (str "@" prefix "-\\d{4,}")) %) (:tags sc)))

(defn- tagged! [ctx title id]
  (let [sc (sp/scenario-titled! (:dir ctx) title)
        ids (ids-of sc "[A-Za-z][A-Za-z0-9-]*?")]
    (s/check! (= [(str "@" id)] ids) (str "the scenario " (pr-str title) " is tagged " ids ", not @" id
                                          "\n" (s/show (:run ctx))))
    nil))

;; Given: the ledger is set; Then (after a run): the ledger is checked.
(defstep #"the ledger records (\d+) as the highest id issued"
  (fn [ctx n]
    (let [n (parse-long n)]
      (if (:run ctx)
        (do (kc/exits! ctx 0)
            (s/check! (= n (sp/ledger-of (:dir ctx))) (str "the ledger records " (sp/ledger-of (:dir ctx)) ", not " n))
            nil)
        (if (:dir ctx)
          (do (sp/write-ledger! (:dir ctx) n) nil)
          (assoc ctx :dir (project-with-ledger n)))))))

(defstep #"a scenario in the spec carries no id tag"
  (fn [ctx]
    (let [title "An unstamped scenario"]
      (add-scenario! (:dir ctx) {:tags ["@e2e"] :title title})
      (assoc ctx :title title))))

(defstep #"the scenario is tagged @(spec-\d+)"
  (fn [ctx id] (kc/exits! ctx 0) (tagged! ctx (:title ctx) id)))

(defstep #"every scenario in the spec carries an id"
  (fn [ctx]
    (let [dir (sp/spec-project! {:features {"desk" (sp/feature-text "Desk" [stamped {:tags ["@spec-0002" "@e2e" "@UN-002"] :title "Another"}])
                                            "door" (sp/feature-text "Door" [{:tags ["@spec-0003" "@contract"] :title "At the door"}])}})]
      (s/check! (every? #(seq (ids-of % "spec")) (sp/scenarios-of dir)) "a fixture scenario carries no id")
      (assoc ctx :dir dir :ledger-before (slurp (str (fs/file dir "dhf/spec/ids.edn")))))))

(defstep #"the ledger does not change"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= (:ledger-before ctx) (slurp (str (fs/file (:dir ctx) "dhf/spec/ids.edn")))) "the ledger changed")
    nil))

(defstep #"the scenario tagged @(spec-\d+) has been deleted"
  (fn [ctx id]
    (let [dir (:dir ctx)
          title "A scenario deleted since"]
      (add-scenario! dir {:tags [(str "@" id) "@e2e"] :title title})
      (let [f (fs/file dir (sp/feature-rel "desk"))
            text (slurp (str f))
            i (str/index-of text (str "  @" id))]
        (spit (str f) (subs text 0 i))
        (s/check! (not (str/includes? (slurp (str f)) id)) (str id " is still in the spec"))
        nil))))

(defstep #"a new scenario carries no id tag"
  (fn [ctx]
    (let [title "A new scenario"]
      (add-scenario! (:dir ctx) {:tags ["@e2e"] :title title})
      (assoc ctx :title title))))

(defstep #"the new scenario is tagged @(spec-\d+)"
  (fn [ctx id] (kc/exits! ctx 0) (tagged! ctx (:title ctx) id)))

(defstep #"a scenario tagged @(spec-\d+) in one component's feature file"
  (fn [ctx id]
    (let [dir (sp/spec-project! {:features {"desk" (sp/feature-text "Desk" [stamped {:tags [(str "@" id) "@e2e"] :title "Moved later"}])
                                            "door" (sp/feature-text "Door" [{:tags ["@spec-0003" "@e2e"] :title "At the door"}])}})]
      (assoc ctx :dir dir :id id))))

(defstep #"its title is reworded and it is moved to another component's feature file"
  (fn [ctx]
    (let [dir (:dir ctx)
          desk (fs/file dir (sp/feature-rel "desk"))
          door (fs/file dir (sp/feature-rel "door"))
          text (slurp (str desk))
          i (str/index-of text (str "  @" (:id ctx)))
          title "Reworded, and at the door now"]
      (spit (str desk) (subs text 0 i))
      (spit (str door) (str (slurp (str door)) "\n" (sp/scenario-text {:tags [(str "@" (:id ctx)) "@e2e"] :title title})))
      (assoc ctx :title title))))

(defstep #"it is still tagged @(spec-\d+)"
  (fn [ctx id]
    (kc/exits! ctx 0)
    (tagged! ctx (:title ctx) id)
    (let [sc (sp/scenario-titled! (:dir ctx) (:title ctx))]
      (s/check! (= (sp/feature-rel "door") (:file sc)) "the scenario is no longer in the door's feature file"))
    nil))

(defstep #"the project's configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (let [dir (sp/spec-project! {:features {"desk" (sp/feature-text "Desk" [{:tags [(str "@" prefix "-0001") "@e2e"]
                                                                             :title "A stamped scenario"}])}
                                 :overrides {:id-prefix prefix}})]
      (assoc ctx :dir dir :prefix prefix))))

(defstep #"the scenario is tagged with an id starting @([A-Za-z][A-Za-z0-9-]*-)"
  (fn [ctx start]
    (kc/exits! ctx 0)
    (let [sc (sp/scenario-titled! (:dir ctx) (:title ctx))
          ids (filterv #(re-matches #"@[A-Za-z][A-Za-z0-9-]*-\d{4,}" %) (:tags sc))]
      (s/check! (and (= 1 (count ids)) (str/starts-with? (first ids) (str "@" start)))
                (str "the scenario is tagged " (:tags sc) "\n" (s/show (:run ctx))))
      nil)))
