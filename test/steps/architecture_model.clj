(ns steps.architecture-model
  "Steps of the component Model (dhf/spec/04-model-the-architecture/model.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn features-for
  "One feature file per component of MODEL, at its :spec path (or, with
  SLICE, in that slice named by the component's name), ids from START."
  [model & [{:keys [slice start] :or {start 1}}]]
  (let [comps (concat (for [c (:containers model) ctx (:contexts c) comp (:components ctx)] comp) (:kernel model))]
    (into {} (map-indexed (fn [i comp]
                            [(if slice
                               (str "dhf/spec/" slice "/" (name (:id comp)) ".feature")
                               (str "dhf/spec/" (:spec comp) ".feature"))
                             (k/feature-text (:name comp) ["UN-001"] [{:id (format "spec-%04d" (+ start i)) :title (str (:name comp) " works")}])])
                          comps))))

(defn model-project!
  "A project whose model is MODEL, one feature file per component, its views drawn."
  [ctx model & [opts]]
  (k/spec-project! ctx (merge {:model model :features (features-for model opts)} opts)))

(defn model! [ctx f & args]
  (let [m (edn/read-string (k/slurp-rel ctx "dhf/spec/model.edn"))]
    (k/write-edn! ctx "dhf/spec/model.edn" (apply f m args))))

(defn redraw! [ctx] (k/exits! (k/run-rdm ctx "views") 0) ctx)

(def demo (k/context-model [[:demo [[:thing "Thing"]]]]))

(defstep #"a model file with an unclosed map on line 12"
  (fn [ctx]
    (let [ctx (model-project! ctx demo)]
      (k/write! ctx "dhf/spec/model.edn"
                (str/join "\n" [";; 1" ";; 2" "{:system {:id :demo :name \"demo\" :description \"A demo\"}"
                                " :people"
                                " [{:id :user :name \"User\" :description \"Uses it\"}]"
                                " :containers"
                                " [{:id :app :name \"app\" :kind :runs :technology \"x\" :description \"The program\""
                                "   :contexts"
                                "   [{:id :demo :name \"demo\" :description \"The demo\""
                                "     :components"
                                "     [{:id :demo/thing :name \"Thing\" :spec \"demo/thing\" :description \"Does it\"}"
                                "      {:id :demo/other :name \"Other\" :spec \"demo/other\" :description \"Unclosed\""
                                "     ]}]}]"
                                " :views []}" ""])))))

(defstep #"it exits 1 and names the file and line 12"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/model.edn:12:") ctx))

(defstep #"a model file with a \"\}\" on line 7 that closes nothing"
  (fn [ctx]
    (let [ctx (model-project! ctx demo)]
      (k/write! ctx "dhf/spec/model.edn"
                (str/join "\n" [";; 1" "{:system {:id :demo :name \"demo\" :description \"A demo\"}" " :people []"
                                " :containers []" " :views []}" "" "}" ""])))))

(defstep #"it exits 1 and names the file, line 7 and the \"\}\""
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/model.edn:7:" "}") ctx))

(defstep #"a container carrying the key :owner, which the model's shape does not declare"
  (fn [ctx] (let [ctx (model-project! ctx demo)] (model! ctx assoc-in [:containers 0 :owner] "me") (redraw! ctx))))

(defstep #"it exits 1 and names the container and the key"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx ":app" ":owner") ctx))

(defstep #"a container and a component both with the id :alpha"
  (fn [ctx]
    (let [m (-> (k/context-model [[:demo [[:alpha "Alpha"]]]])
                (assoc-in [:containers 0 :contexts 0 :components 0 :id] :alpha))
          ctx (model-project! ctx (k/context-model [[:demo [[:thing "Thing"]]]]))]
      (model! ctx (fn [_] (-> m (assoc-in [:containers 0 :id] :alpha) (assoc-in [:relationships 0 :to] :alpha))))
      ctx)))

(defstep #"it exits 1 and names the id and both elements"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx ":alpha" "container" "component") ctx))

(defstep #"a relationship from :spec to :archive, and no element :archive"
  (fn [ctx]
    (let [ctx (model-project! ctx (k/context-model [[:spec [[:parser "Parser"]]]]))]
      (model! ctx update :relationships conj {:from :spec/parser :to :archive :description "Archives"})
      ctx)))

(defstep #"it exits 1 and names the relationship and :archive"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx ":spec/parser" ":archive") ctx))

(defstep #"the component :spec/parser with the spec path \"spec/parser\""
  (fn [ctx] (model-project! ctx (k/context-model [[:spec [[:parser "Parser"]]]]))))

(defstep #"the component owns the feature file dhf/spec/spec/parser.feature, and \"rdm-spec check\" passes only when that file exists"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= "Parser" (get-in (k/run-rdm ctx "trace spec-0001") [:result :trace :component])) "the scenario's component is not Parser")
    (let [text (k/slurp-rel ctx "dhf/spec/spec/parser.feature")]
      (k/delete! ctx "dhf/spec/spec/parser.feature")
      (let [r (k/run-rdm ctx "check")]
        (k/exits! r 1)
        (k/err-line-has! r "a-scenario-owner" ":spec/parser"))
      (k/write! ctx "dhf/spec/spec/parser.feature" text))))

(defstep #"views generated from the model"
  (fn [ctx] (model-project! ctx demo)))

(defstep #"the model's comments and whitespace are changed and nothing else"
  (fn [ctx]
    (let [m (edn/read-string (k/slurp-rel ctx "dhf/spec/model.edn"))]
      (k/write! ctx "dhf/spec/model.edn" (str ";; a new comment\n\n" (pr-str m) "\n\n;; and another\n")))))

(defstep #"it reports no stale view"
  (fn [ctx] (k/exits! ctx 0) (k/check! (not-any? #(str/includes? % "stale") (k/err-lines ctx)) "a view is stale: " (k/shows ctx)) ctx))

(defstep #"one element's description is changed"
  (fn [ctx] (model! ctx assoc-in [:people 0 :description] "Uses it often")))

(defstep #"it fails and names every view as stale"
  (fn [ctx]
    (k/exits! ctx 1)
    (doseq [v ["context" "demo-components"]] (k/err-line-has! ctx (str "dhf/views/" v) "stale"))
    ctx))

;; ------------------------------------------------------------------ slices

(def slices-model
  (k/context-model [[:spec [[:parser "Parser"] [:stamper "Stamper"]]] [:measurement [[:checklist "Checklist"]]]]))

(def plan "# Slice\n\n## Goal\n\nA goal.\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n- a\n\n**Out:**\n\n- b\n\n## Decisions\n\nNone.\n")

(defn slices-project!
  "A slices-layout project: every component's feature file in 12-several-specs, its plan beside them."
  [ctx]
  (let [ctx (model-project! ctx slices-model {:slice "12-several-specs"})]
    (k/config! ctx assoc :spec-layout :slices)
    (k/write! ctx "dhf/spec/12-several-specs/plan.md" plan)))

(defstep #"a configuration with :spec-layout :slices, and dhf/spec/12-several-specs/ holding plan.md, parser.feature, stamper.feature and checklist.feature"
  (fn [ctx]
    (let [ctx (slices-project! ctx)]
      (k/write! ctx "dhf/spec/02-write-the-spec/plan.md" plan)
      (k/write! ctx "dhf/spec/02-write-the-spec/parser.feature" (k/feature-text "Parser" ["UN-001"] [{:id "spec-0009" :title "Parser in another slice"}]))
      (k/write! ctx "dhf/spec/ids.edn" "{:issued 9}\n"))))

(defstep #"the slice 12-several-specs reaches the components :spec/parser, :spec/stamper and :measurement/checklist, each found by its name in the model"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [[id comp] [["spec-0001" "Parser"] ["spec-0002" "Stamper"] ["spec-0003" "Checklist"]]]
      (k/check! (= comp (get-in (k/run-rdm ctx (str "trace " id)) [:result :trace :component])) id " is not owned by " comp))
    ctx))

(defstep #"a component owns every scenario of the feature files named for it, in every slice"
  (fn [ctx]
    (k/check! (= "Parser" (get-in (k/run-rdm ctx "trace spec-0009") [:result :trace :component])) "spec-0009 is not owned by Parser")
    ctx))

(defstep #"a configuration with :spec-layout :slices, and (dhf/spec/12-several-specs/parsr.feature, a name no component carries|dhf/spec/parser.feature, a feature file outside every slice|two components of the model named \"check\", in two contexts|a component of the model that no slice's feature file is named for)"
  (fn [ctx holds]
    (let [ctx (slices-project! ctx)]
      (case holds
        "dhf/spec/12-several-specs/parsr.feature, a name no component carries"
        (k/write! ctx "dhf/spec/12-several-specs/parsr.feature" (k/feature-text "Parsr" ["UN-001"] []))
        "dhf/spec/parser.feature, a feature file outside every slice"
        (k/write! ctx "dhf/spec/parser.feature" (k/feature-text "Parser" ["UN-001"] []))
        "two components of the model named \"check\", in two contexts"
        (do (model! ctx (fn [m] (-> m
                                    (update-in [:containers 0 :contexts 0 :components] conj {:id :spec/check :name "Check" :spec "spec/check" :description "Checks"})
                                    (update-in [:containers 0 :contexts 1 :components] conj {:id :measurement/check :name "Check" :spec "measurement/check" :description "Checks too"}))))
            (k/write! ctx "dhf/spec/12-several-specs/check.feature" (k/feature-text "Check" ["UN-001"] []))
            (redraw! ctx))
        "a component of the model that no slice's feature file is named for"
        (do (model! ctx update-in [:containers 0 :contexts 0 :components] conj {:id :spec/lonely :name "Lonely" :spec "spec/lonely" :description "Alone"})
            (redraw! ctx)))
      ctx)))

(defstep #"it exits 1 with the finding (slice-component-unknown|slice-missing|component-name-shared|component-unspecified), naming (.+)"
  (fn [ctx rule names]
    (k/exits! ctx 1)
    (case rule
      "slice-component-unknown" (k/err-line-has! ctx "slice-component-unknown" "dhf/spec/12-several-specs/parsr.feature")
      "slice-missing" (k/err-line-has! ctx "slice-missing" "dhf/spec/parser.feature")
      "component-name-shared" (k/err-line-has! ctx "component-name-shared" ":spec/check" ":measurement/check")
      "component-unspecified" (k/err-line-has! ctx "component-unspecified" ":spec/lonely"))
    ctx))

(defstep #"a configuration with no :spec-layout, and dhf/spec/spec/parser.feature named by the component's spec path"
  (fn [ctx] (model-project! ctx (k/context-model [[:spec [[:parser "Parser"]]]]))))

(defstep #"the component :spec/parser owns that file, as the model's spec path says"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= "Parser" (get-in (k/run-rdm ctx "trace spec-0001") [:result :trace :component])) "the scenario is not owned by Parser")
    ctx))
