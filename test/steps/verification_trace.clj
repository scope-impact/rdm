(ns steps.verification-trace
  "Steps of the components Trace and Release check (05-verify-by-results)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [steps.verification-status :as vs]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def three-files
  {"shop/cart.feature" (k/feature-text {:name "Cart" :tags ["UN-003"] :scenarios [{:id "spec-0001" :title "Under the feature"} {:id "spec-0002" :title "Also under it"}]})
   "shop/till.feature" (str "@UN-001\nFeature: Till\n\n  @UN-003\n  Rule: Traced\n\n    @spec-0003 @contract\n    Scenario: Under the rule\n      Given a thing\n\n"
                            "  Rule: Not traced\n\n    @spec-0004 @e2e\n    Scenario: Elsewhere\n      Given a thing\n")
   "shop/shelf.feature" (k/feature-text {:name "Shelf" :scenarios [{:id "spec-0005" :title "Tagged itself" :tags ["UN-003"]} {:id "spec-0006" :title "Not tagged"}]})})

(def three-model
  (update-in k/shop-model [:containers 0 :contexts 0 :components] conj {:id :shop/shelf :name "Shelf" :description "Shows" :spec "shop/shelf"}))

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [_] (k/spec-project! three-files :model three-model)))

(defn trace-ids [ctx] (map :id (get-in ctx [:edn :trace :scenarios])))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= #{"spec-0001" "spec-0002" "spec-0003" "spec-0005"} (set (trace-ids ctx))) ctx (str "it lists " (trace-ids ctx))) ctx))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (let [s (some #(when (= "spec-0003" (:id %)) %) (get-in ctx [:edn :trace :scenarios]))]
      (k/expect! (= {:id "spec-0003" :title "Under the rule" :component "Till" :level :contract} (select-keys s [:id :title :component :level])) ctx (str "it is " s)))
    ctx))

(defstep #"the declared need UN-007 and no scenario tracing to it"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})}
                           :needs (assoc k/default-needs "UN-007" {:text "Nobody needs it." :sources ["loyly:README.md@17d68d2"]}))))

(defstep #"the trace says no scenario traces to UN-007"
  (fn [ctx] (k/exits! ctx 0) (k/out-has! ctx "no scenario traces to UN-007") ctx))

(def lint-model
  {:system {:id :s :name "S" :description "d"} :people [{:id :p :name "P" :actor #{:human} :description "d"}]
   :containers [{:id :c :name "C" :kind :runs :technology "t" :description "d"
                 :contexts [{:id :architecture :name "architecture" :description "d"
                             :components [{:id :architecture/lint :name "Lint" :description "d" :spec "architecture/lint"}]}]}]
   :relationships [{:from :p :to :c :description "uses"}]
   :views [{:key "architecture-components" :level :component :context :architecture :description "d"}]})

(defn lint-project! [tag]
  (k/spec-project! {"architecture/lint.feature" (k/feature-text {:name "Lint" :tags [tag] :scenarios [{:id "spec-0012" :title "Lint names the view"}]})}
                   :model lint-model :needs (assoc k/default-needs "UN-005" {:text "Five." :sources ["loyly:README.md@17d68d2"]})))

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005"
  (fn [_] (lint-project! "UN-005")))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (get-in ctx [:edn :trace])]
      (k/expect! (= {:kind :scenario :id "spec-0012" :needs ["UN-005"] :component "Lint" :context "architecture" :level :e2e}
                    (select-keys t [:kind :id :needs :component :context :level])) ctx (str "the trace is " t)))
    ctx))

(defstep #"an Allure results directory with two results labelled spec=spec-0012, one passed and one failed"
  (fn [_]
    (let [ctx (k/var-dir! (lint-project! "UN-005") "DIR") d (get-in ctx [:vars "DIR"])]
      (k/result! d "spec-0012" "passed")
      (k/result! d "spec-0012" "failed")
      ctx)))

(defstep #"the trace gives the status failed"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= :failed (get-in ctx [:edn :trace :status])) ctx "not failed") ctx))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [rs (get-in ctx [:edn :trace :results])]
      (k/expect! (= #{"passed" "failed"} (set (map :status rs))) ctx "not both results")
      (k/expect! (every? #(str/starts-with? (:file %) (get-in ctx [:vars "DIR"])) rs) ctx "the files are not named"))
    ctx))

(defstep #"UN-003 tagged on one scenario, and a results directory with a passing result for it"
  (fn [_]
    (let [ctx (k/var-dir! (vs/project-of ["spec-0001"]) "DIR")]
      (k/write! (:dir ctx) "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :tags [] :scenarios [{:id "spec-0001" :tags ["UN-003"]}]}))
      (k/result! (get-in ctx [:vars "DIR"]) "spec-0001" "passed")
      ctx)))

(defstep #"standard output is \{:trace \{:kind :need :id \"UN-003\" :text \.\.\. :scenarios \[\.\.\.\]\}\}"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (:edn ctx)]
      (k/expect! (= #{:trace} (set (keys t))) ctx "not one map under :trace")
      (k/expect! (= {:kind :need :id "UN-003" :text "A buyer sees."} (select-keys (:trace t) [:kind :id :text])) ctx "not the need")
      (k/expect! (vector? (get-in t [:trace :scenarios])) ctx "no :scenarios"))
    ctx))

(defstep #"each of its scenarios is a map with :id, :title, :component, :context, :level and :status"
  (fn [ctx]
    (k/expect! (= [{:id "spec-0001" :title "Scenario spec-0001" :component "Cart" :context "shop" :level :e2e :status :verified}]
                  (map #(select-keys % [:id :title :component :context :level :status]) (get-in ctx [:edn :trace :scenarios]))) ctx "not the scenario's map")
    ctx))

(defstep #"no scenario tagged @spec-0999 and no need UN-999"
  (fn [_] (vs/project-of ["spec-0001"])))

(defstep #"it exits 2 and names spec-0999"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "spec-0999") ctx))

(defstep #"it exits 2 and names the directory"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx (get-in ctx [:vars "DIR"])) ctx))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (get-in ctx [:edn :trace])]
      (k/expect! (and (seq (:needs t)) (= "Lint" (:component t)) (= :e2e (:level t))) ctx (str "the trace is " t)))
    ctx))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx] (k/out-has! ctx "no status was derived because no results were given") ctx))

;; ------------------------------------------------------------------ release

(defstep #"a stamped scenario tagged @wip, and a passing result for every other stamped scenario"
  (fn [_] (let [ctx (vs/project-of ["spec-0001" "spec-0002"] :wip ["spec-0001"])]
            (k/result! (vs/results-dir ctx) "spec-0002" "passed")
            (assoc ctx :subject "spec-0001"))))

(defstep #"it exits 1 and names the scenario's id"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx (:subject ctx)) ctx))

(defstep #"a stamped scenario whose result failed"
  (fn [_] (let [ctx (vs/project-of ["spec-0001" "spec-0002"])]
            (k/result! (vs/results-dir ctx) "spec-0001" "failed")
            (k/result! (vs/results-dir ctx) "spec-0002" "passed")
            (assoc ctx :subject "spec-0001" :status "failed"))))

(defstep #"a stamped scenario with no result"
  (fn [_] (let [ctx (vs/project-of ["spec-0001" "spec-0002"])]
            (k/result! (vs/results-dir ctx) "spec-0002" "passed")
            (assoc ctx :subject "spec-0001" :status "unverified"))))

(defstep #"it exits 1 and names the scenario's id and its status"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx (:subject ctx) (:status ctx)) ctx))

(defstep #"every stamped scenario has a passing result and none is tagged @wip"
  (fn [_] (let [ctx (vs/project-of ["spec-0001" "spec-0002"])]
            (k/result! (vs/results-dir ctx) "spec-0001" "passed")
            (k/result! (vs/results-dir ctx) "spec-0002" "passed")
            ctx)))

;; ------------------------------------------------------------------ 10: the regulated release

(defstep #"a regulated project whose every stamped scenario has a passing result, and a clause no audit tag names"
  (fn [_] (k/pass-all! (k/regulated-project! :clauses "STD:5.1 Development plan\nSTD:5.2 Requirements analysis\n") ["spec-0001" "spec-0098"])))

(defstep #"it exits 1 and names the clause and its clause list"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/clauses/std.txt" "STD:5.2") ctx))

(defstep #"a regulated project whose every stamped scenario has a passing result and every clause is named, and a document with no id in its frontmatter"
  (fn [_] (let [ctx (k/pass-all! (k/regulated-project!) ["spec-0001" "spec-0098"])]
            (k/write! (:dir ctx) "dhf/documents/note.md" (k/doc-text {:id nil :title "A note" :body "A note.\n"}))
            ctx)))

(defstep #"it exits 1 and names the document"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/documents/note.md") ctx))

;; ------------------------------------------------------------------ 12: several specs

(defn two-specs-results!
  "Two specs, auth (every scenario verified) and infra (one unverified), with results R."
  []
  (let [ctx (k/var-dir! (k/two-specs! :auth-features {"auth/core.feature" (k/feature-text {:name "Core" :scenarios [{:id "auth-0001"} {:id "auth-0002"}]})}
                                      :infra-features {"infra/core.feature" (k/feature-text {:name "Core" :scenarios [{:id "infra-0001"} {:id "infra-0002"}]})})
                        "R")
        r (get-in ctx [:vars "R"])]
    (doseq [id ["auth-0001" "auth-0002" "infra-0001"]] (k/result! r id "passed"))
    ctx))

(defstep #"two specs, auth with every scenario verified and infra with one unverified"
  (fn [_] (two-specs-results!)))

(defstep #"it refuses, naming infra's unverified scenario"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "infra-0002" "unverified") ctx))

(defstep #"it lists every stamped scenario of both specs, each with its status and its spec's name"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= #{["auth-0001" :verified "auth"] ["auth-0002" :verified "auth"] ["infra-0001" :verified "infra"] ["infra-0002" :unverified "infra"]}
                  (set (map (juxt :id :status :spec) (get-in ctx [:edn :status :scenarios])))) ctx "not every scenario of both")
    ctx))

(defstep #"\"rdm-spec status --results R --spec auth\" lists only auth's"
  (fn [ctx]
    (let [r (k/rdm! ctx "status --results R --spec auth")]
      (k/exits! r 0)
      (k/expect! (= ["auth-0001" "auth-0002"] (map :id (get-in r [:edn :status :scenarios]))) r "not only auth's"))
    ctx))

(defstep #"two specs, auth and infra, each with a need UN-001 and a scenario tagged with it"
  (fn [_]
    (let [ctx (k/var-dir! (k/two-specs! :auth-features {"auth/core.feature" (k/feature-text {:name "Core" :scenarios [{:id "auth-0001"}]})}
                                        :infra-features {"infra/core.feature" (k/feature-text {:name "Core" :scenarios [{:id "infra-0001"}]})})
                          "R")]
      (k/result! (get-in ctx [:vars "R"]) "infra-0001" "passed")
      ctx)))

(defstep #"it lists infra's scenario and its status, and none of auth's"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= [["infra-0001" :verified]] (map (juxt :id :status) (get-in ctx [:edn :trace :scenarios]))) ctx "not infra's scenario")
    ctx))

(defstep #"\"rdm-spec trace UN-001 --results R\", with no --spec, exits 2 and names both specs that hold UN-001"
  (fn [ctx]
    (let [r (k/rdm! ctx "trace UN-001 --results R")] (k/exits! r 2) (k/err-has! r "auth" "infra"))
    ctx))

(defstep #"a scenario id, unique across specs, is traced with no --spec"
  (fn [ctx]
    (let [r (k/rdm! ctx "trace infra-0001 --results R")]
      (k/exits! r 0)
      (k/expect! (= ["infra-0001" :verified] ((juxt :id :status) (get-in r [:edn :trace]))) r "not traced"))
    ctx))

;; ------------------------------------------------------------------ 13: slices

(defn slices-results!
  "Slices 11-project-in-a-folder (5 scenarios, status building) and
  12-several-specs (status done), each with results R as given."
  [verified-11 status-12]
  (let [ctx (k/var-dir! (k/slices-project! {"11-project-in-a-folder/plan.md" (str/replace (str/replace k/plan-text "Slice 12" "Slice 11") "status: building" "status: building")
                                            "11-project-in-a-folder/decisions.md" "# D\n"
                                            "11-project-in-a-folder/parser.feature" (k/feature-text {:name "Parser" :scenarios (vec (for [i (range 21 26)] {:id (format "spec-%04d" i)}))})
                                            "12-several-specs/plan.md" (str/replace k/plan-text "status: building" (str "status: " status-12))})
                        "R")
        r (get-in ctx [:vars "R"])]
    (doseq [i (concat (range 7 11) (take verified-11 (range 21 26)))] (k/result! r (format "spec-%04d" i) "passed"))
    ctx))

(defstep #"a configuration with :spec-layout :slices; the slice 12-several-specs with every scenario verified by results R; and the slice 11-project-in-a-folder with one unverified"
  (fn [_] (slices-results! 4 "done")))

(defstep #"\"rdm-spec release --results R\" refuses, naming 11-project-in-a-folder's unverified scenario"
  (fn [ctx]
    (let [r (k/rdm! ctx "release --results R")]
      (k/exits! r 1)
      (k/line-has! r "dhf/spec/11-project-in-a-folder/parser.feature" "spec-0025" "unverified"))
    ctx))

(defstep #"a configuration with :spec-layout :slices; the slice 11-project-in-a-folder, \"status: building\", with 5 stamped scenarios of which results R verify 3; and the slice 12-several-specs, \"status: done\", with every scenario verified"
  (fn [_] (slices-results! 3 "done")))

(defstep #"its result holds :slices \[\{:slice \"11-project-in-a-folder\" :status \"building\" :verified 3 :scenarios 5\} \{:slice \"12-several-specs\" :status \"done\" :verified N :scenarios N\}\], in the order of their numbers, beside every scenario's status"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [rows (filter #(#{"11-project-in-a-folder" "12-several-specs"} (:slice %)) (get-in ctx [:edn :status :slices]))]
      (k/expect! (= [{:slice "11-project-in-a-folder" :status "building" :verified 3 :scenarios 5}
                     {:slice "12-several-specs" :status "done" :verified 4 :scenarios 4}] rows) ctx (str "the rows are " rows))
      (k/expect! (= (map :slice (get-in ctx [:edn :status :slices])) (sort (map :slice (get-in ctx [:edn :status :slices])))) ctx "not in the order of their numbers")
      (k/expect! (seq (get-in ctx [:edn :status :scenarios])) ctx "no scenario's status"))
    ctx))

(defstep #"\"rdm-spec status --results R --slice 11-project-in-a-folder\" reports that slice and its scenarios only"
  (fn [ctx]
    (let [r (k/rdm! ctx "status --results R --slice 11-project-in-a-folder")]
      (k/exits! r 0)
      (k/expect! (= ["11-project-in-a-folder"] (map :slice (get-in r [:edn :status :slices]))) r "not that slice only")
      (k/expect! (= (for [i (range 21 26)] (format "spec-%04d" i)) (map :id (get-in r [:edn :status :scenarios]))) r "not its scenarios only"))
    ctx))
