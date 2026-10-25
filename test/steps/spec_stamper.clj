(ns steps.spec-stamper
  "Step definitions for dhf/spec/spec/stamper.feature, in minimal projects:
  the room's configuration, a spec of demo feature files and a ledger."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.spec-support :as f]
            [steps.support :as s]))

(defn- project [ctx]
  (s/needs-built! 'rdm-spec.spec.stamper)
  (if (:dir ctx) ctx (assoc ctx :dir (f/project!))))

(defn- add-feature!
  "Writes a demo feature file at dhf/spec/<rel> holding scenarios [[name tags]]."
  [ctx rel scs]
  (s/write! (:dir ctx) (str "dhf/spec/" rel) (f/scenarios-feature scs))
  ctx)

;; Given (before the command ran) it writes the ledger; Then it checks it.
(defstep #"the ledger records (\d+) as the highest id issued"
  (fn [ctx n]
    (let [n (parse-long n)]
      (if (:run ctx)
        (s/check! (= n (f/ledger (:dir ctx)))
                  (str "the ledger records " (f/ledger (:dir ctx)) "\n" (s/show (:run ctx))))
        (let [ctx (project ctx)]
          (f/write-ledger! (:dir ctx) n)
          (assoc ctx :ledger n))))))

(defstep #"a scenario in the spec carries no id tag"
  (fn [ctx]
    (let [ctx (project ctx)]
      (add-feature! ctx "demo/unstamped.feature"
                    [["An already stamped scenario" (format "@%s-0001 @e2e" (get-in ctx [:prefix] "spec"))]
                     ["The unstamped scenario" "@e2e"]])
      (assoc ctx :name "The unstamped scenario"))))

(defstep #"the scenario is tagged @(\S+)"
  (fn [ctx id]
    (let [r (:run ctx)
          tags (f/tags-of (:dir ctx) (:name ctx))]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (some #{(str "@" id)} tags) (str "the scenario carries " (pr-str tags) "\n" (s/show r))))))

(defstep #"every scenario in the spec carries an id"
  (fn [ctx]
    (let [ctx (project ctx)]
      (f/write-ledger! (:dir ctx) 3)
      (add-feature! ctx "demo/a.feature" [["First" "@spec-0001 @e2e"] ["Second" "@spec-0002 @contract"]])
      (add-feature! ctx "demo/b.feature" [["Third" "@e2e @spec-0003"]])
      (f/ensure-shared-steps!)
      (assoc ctx :spec-before (f/spec-snapshot (:dir ctx)) :ledger-text (slurp (f/ledger-file (:dir ctx)))))))

;; "no file in the spec changes" is shared with
;; dhf/spec/verification/status.feature: registered at run time by
;; f/ensure-shared-steps! (comparing :spec-before), unless another step file
;; defines it.

(defstep #"the ledger does not change"
  (fn [ctx]
    (s/check! (= 0 (:exit (:run ctx))) (s/show (:run ctx)))
    (s/check! (= (:ledger-text ctx) (slurp (f/ledger-file (:dir ctx)))) "the ledger changed")))

(defstep #"the scenario tagged @spec-0042 has been deleted"
  (fn [ctx]
    (let [ctx (project ctx)]
      (add-feature! ctx "demo/a.feature" [["A remaining scenario" "@spec-0041 @e2e"]])
      (s/check! (not (some #(str/includes? (slurp (str %)) "@spec-0042")
                           (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature")))
                "@spec-0042 is still in the spec")
      ctx)))

(defstep #"a new scenario carries no id tag"
  (fn [ctx]
    (add-feature! ctx "demo/b.feature" [["The new scenario" "@e2e"]])
    (assoc ctx :name "The new scenario")))

(defstep #"the new scenario is tagged @(\S+)"
  (fn [ctx id]
    (let [r (:run ctx)
          tags (f/tags-of (:dir ctx) (:name ctx))]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (some #{(str "@" id)} tags) (str "the new scenario carries " (pr-str tags) "\n" (s/show r))))))

(defstep #"a scenario tagged @spec-0007 in one component's feature file"
  (fn [ctx]
    (let [ctx (project ctx)]
      (f/write-ledger! (:dir ctx) 7)
      (add-feature! ctx "one/first.feature" [["The original title" "@spec-0007 @e2e"]]))))

(defstep #"its title is reworded and it is moved to another component's feature file"
  (fn [ctx]
    (let [dir (:dir ctx)]
      (add-feature! ctx "one/first.feature" [["A scenario that stays" "@spec-0006 @e2e"]])
      (add-feature! ctx "two/second.feature" [["The reworded title" "@spec-0007 @e2e"]])
      (assoc ctx :name "The reworded title" :dir dir))))

(defstep #"it is still tagged @(\S+)"
  (fn [ctx id]
    (let [r (:run ctx)
          tags (f/tags-of (:dir ctx) (:name ctx))]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (= [(str "@" id)] (filter #(re-matches #"@spec-\d+" %) tags))
                (str "the scenario carries " (pr-str tags))))))

(defstep #"the project's configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (s/needs-built! 'rdm-spec.spec.stamper)
    (assoc ctx :dir (f/project! {:id-prefix prefix}) :prefix prefix)))

(defstep #"the scenario is tagged with an id starting (@\S+-)"
  (fn [ctx start]
    (let [r (:run ctx)
          tags (f/tags-of (:dir ctx) (:name ctx))]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (some #(re-matches (re-pattern (str (java.util.regex.Pattern/quote start) "\\d{4,}")) %) tags)
                (str "the scenario carries " (pr-str tags))))))
