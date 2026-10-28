(ns steps.regeneration-fence
  "Steps of the component Fence (06-regenerate/fence.feature, 11 and 13)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def impl-model
  "A model whose contexts and kernel the implementation fixture's code layout places."
  {:system {:id :s :name "S" :description "d"}
   :people [{:id :p :name "P" :actor #{:human} :description "d"}]
   :containers [{:id :c :name "C" :kind :runs :technology "t" :description "d"
                 :contexts (vec (for [[ctx comps] [[:spec ["parser" "check"]] [:knowledge ["sources" "wiki"]] [:publishing ["site"]] [:verification ["status" "verify"]]]]
                                  {:id ctx :name (name ctx) :description "d"
                                   :components (vec (for [c comps] {:id (keyword (name ctx) c) :name c :description "d" :spec (str (name ctx) "/" c)}))}))}]
   :kernel [{:id :kernel/bus :name "Event bus" :description "d" :spec "kernel/bus"}
            {:id :kernel/command :name "Command" :description "d" :spec "kernel/command"}]
   :relationships [{:from :p :to :c :description "uses"}]
   :views (vec (for [c [:spec :knowledge :publishing :verification]] {:key (str (name c) "-components") :level :component :context c :description "d"}))})

(def code-layout
  {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
   :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj" "test/steps/{context}_{component}.clj"]})

(defn impl-files []
  (into {} (concat
            (for [c (concat (mapcat :components (:contexts (first (:containers impl-model)))) (:kernel impl-model))
                  :let [ctx (namespace (:id c)) n (str/replace (name (:id c)) "-" "_")]
                  [f t] [[(str "src/rdm_spec/" ctx "/" n ".clj") (str "(ns rdm-spec." ctx "." n ")\n")]
                         [(str "test/rdm_spec/" ctx "/" n "_test.clj") (str "(ns rdm-spec." ctx "." n "-test)\n")]
                         [(str "test/steps/" ctx "_" n ".clj") (str ";; steps of " ctx "/" n "\n")]]]
              [f t])
            [["test.clj" ";; runs the tests\n"] ["bb.edn" "{:paths [\"src\"]}\n"]])))

(defn impl-project!
  "A project whose commit tagged spec-3 holds an implementation laid out by the code layout."
  [& {:keys [extra]}]
  (let [ctx (k/spec-project! (into {} (for [[i c] (map-indexed vector (concat (mapcat :components (:contexts (first (:containers impl-model)))) (:kernel impl-model)))]
                                        [(str (:spec c) ".feature") (k/feature-text {:name (:name c) :scenarios [{:id (format "spec-%04d" (inc i))}]})]))
                             :model impl-model)
        d (:dir ctx)]
    (k/edit-config! d #(assoc % :implementation ["src" "test" "test.clj" "bb.edn"] :code-layout code-layout))
    (doseq [[f t] (merge (impl-files) extra)] (k/write! d f t))
    (k/git! d "init" "-q")
    (k/commit-all! d "spec-3" "spec-3")
    (assoc ctx :impl (set (keys (impl-files))))))

(defn room [ctx] (get-in ctx [:vars "ROOM"]))
(defn room-files [ctx] (set (keys (k/tree-snapshot (room ctx)))))

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [_] (let [ctx (k/spec-project! {})] (k/git! (:dir ctx) "init" "-q") (k/commit-all! (:dir ctx) "not a cut") ctx)))

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "regeneration reads only a cut") (k/expect! (not (fs/exists? (room ctx))) ctx "a room was built") ctx))

(defstep #"the configuration denies the implementation, the measurements dhf/cycles and the generated site, and keeps every other path"
  (fn [_] (impl-project!)))

(defstep #"the cut also holds dhf/cycles/01/score.edn"
  (fn [ctx]
    (k/write! (:dir ctx) "dhf/cycles/01/score.edn" "{:cycle \"01\"}\n")
    (k/write! (:dir ctx) "site/index.html" "<html></html>\n")
    (k/git! (:dir ctx) "tag" "-d" "spec-3")
    (k/commit-all! (:dir ctx) "with a score" "spec-3")
    ctx))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= "1" (k/git! (room ctx) "rev-list" "--count" "--all")) ctx "not a single commit")
    ctx))

(defstep #"its working tree holds every file committed at the cut that is not denied, unchanged, and no other file"
  (fn [ctx]
    (let [at-cut (set (str/split-lines (k/git! (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3")))
          denied? (fn [f] (or (contains? (:impl ctx) f) (str/starts-with? f "dhf/cycles/") (str/starts-with? f "site/")))
          kept (set (remove denied? at-cut))]
      (k/expect! (= kept (room-files ctx)) ctx (str "the room differs: " (sort (concat (remove (room-files ctx) kept) (remove kept (room-files ctx))))))
      (doseq [f kept]
        (k/expect! (= (k/git! (:dir ctx) "show" (str "spec-3:" f)) (str/trim (k/slurp-in (room ctx) f))) ctx (str f " changed"))))
    ctx))

(defstep #"no object reachable in it belongs to a denied path"
  (fn [ctx]
    (let [objs (k/git! (room ctx) "rev-list" "--objects" "--all")]
      (k/expect! (not (re-find #"(?m) (src|test|dhf/cycles|site)(/|$)" objs)) ctx "a denied path is reachable"))
    ctx))

(defstep #"a project whose working tree holds an untracked file notes.md and a file git ignores, beside its commit tagged spec-3"
  (fn [_] (let [ctx (impl-project!)]
            (k/write! (:dir ctx) "notes.md" "mine\n")
            (k/write! (:dir ctx) "dhf/allure-results/x-result.json" "{}")
            ctx)))

(defstep #"ROOM holds neither file"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (not (or (fs/exists? (k/path (room ctx) "notes.md")) (fs/exists? (k/path (room ctx) "dhf/allure-results")))) ctx "ROOM holds one")
    ctx))

(defstep #"a project whose commit tagged spec-3 holds dhf/wiki/pages/copy.md with the same bytes as an implementation file"
  (fn [_] (impl-project! :extra {"dhf/wiki/pages/copy.md" "(ns rdm-spec.spec.parser)\n"})))

(defstep #"it exits 1, names dhf/wiki/pages/copy.md and the implementation file, and builds no room"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/wiki/pages/copy.md" "src/rdm_spec/spec/parser.clj")
    (k/expect! (not (fs/exists? (room ctx))) ctx "a room was built") ctx))

(defstep #"a project whose configuration denies the implementation and the measurements and keeps dhf/spec, and a committed top-level file notes.md"
  (fn [_] (let [ctx (impl-project! :extra {"notes.md" "notes\n"})] ctx)))

(defstep #"it exits 1 with a finding naming (notes.md|dhf/scratch) as neither denied nor kept"
  (fn [ctx p] (k/exits! ctx 1) (k/line-has! ctx (str p ": ") "neither denied nor kept") ctx))

(defstep #"a project whose configuration keeps dhf/spec, and a committed directory dhf/scratch"
  (fn [_] (impl-project! :extra {"dhf/scratch/a.txt" "scratch\n"})))

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [_] (impl-project!)))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx] (k/exits! ctx 0)
    (doseq [p ["src" "test" "test.clj" "bb.edn"]] (k/expect! (not (fs/exists? (k/path (room ctx) p))) ctx (str "ROOM holds " p)))
    ctx))

(defstep #"the code layout places (?:context|component) code at .+"
  (fn [_] (impl-project!)))

(defn- impl-in-room [ctx] (set (filter #(contains? (:impl ctx) %) (room-files ctx))))

(defn- assert-removed [ctx prefixes]
  (k/exits! ctx 0)
  (let [removed (set (filter (fn [f] (some #(str/starts-with? f %) prefixes)) (:impl ctx)))]
    (k/expect! (seq removed) ctx "the scope removes nothing")
    (k/expect! (empty? (filter removed (room-files ctx))) ctx "ROOM still holds the scope's files")
    (assoc ctx :removed removed)))

(defstep #"ROOM holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/"
  (fn [ctx] (assert-removed ctx ["src/rdm_spec/verification/" "test/rdm_spec/verification/" "test/steps/verification_"])))

(defstep #"ROOM holds neither src/rdm_spec/(spec/parser|kernel/bus).clj nor test/rdm_spec/\S+_test.clj"
  (fn [ctx c] (let [[x n] (str/split c #"/")]
                (assert-removed ctx [(str "src/rdm_spec/" x "/" n ".clj") (str "test/rdm_spec/" x "/" n "_test.clj") (str "test/steps/" x "_" n ".clj")]))))

(defstep #"ROOM holds no file of the bounded contexts knowledge and publishing or of the component spec/parser"
  (fn [ctx] (assert-removed ctx ["src/rdm_spec/knowledge/" "test/rdm_spec/knowledge/" "test/steps/knowledge_"
                                 "src/rdm_spec/publishing/" "test/rdm_spec/publishing/" "test/steps/publishing_"
                                 "src/rdm_spec/spec/parser.clj" "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj"])))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [others (remove (:removed ctx) (:impl ctx))]
      (doseq [f others]
        (k/expect! (= (k/git! (:dir ctx) "show" (str (or (:cut ctx) "spec-3") ":" f)) (str/trim (str (k/slurp-in (room ctx) f)))) ctx (str f " is missing or changed"))))
    ctx))

(defstep #"the cut spec-4's model no longer names ratification/ratify, and the cut still holds src/rdm_spec/ratification/ratify.clj and test/rdm_spec/ratification/ratify_test.clj"
  (fn [_]
    (let [ctx (impl-project! :extra {"src/rdm_spec/ratification/ratify.clj" "(ns rdm-spec.ratification.ratify)\n"
                                     "test/rdm_spec/ratification/ratify_test.clj" "(ns rdm-spec.ratification.ratify-test)\n"})]
      (k/commit-all! (:dir ctx) "spec-4" "spec-4")
      (assoc ctx :cut "spec-4" :impl (conj (:impl ctx) "src/rdm_spec/ratification/ratify.clj" "test/rdm_spec/ratification/ratify_test.clj")))))

(defstep #"ROOM holds neither file, and its result names ratification/ratify as a removed component"
  (fn [ctx]
    (let [ctx (assert-removed ctx ["src/rdm_spec/ratification/" "test/rdm_spec/ratification/"])]
      (k/expect! (= [:ratification/ratify] (get-in ctx [:edn :fence :removed-components])) ctx "not named as removed")
      ctx)))

(defstep #"it exits 2 and names archive and the model's bounded contexts and kernel"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "archive" "spec" "knowledge" "publishing" "verification" "kernel") ctx))

(defstep #"so does a component the model does not name whose files the code layout finds none of"
  (fn [ctx]
    (let [r (k/rdm! (update ctx :vars dissoc "ROOM") ["fence" (k/path (:top ctx) "room2") "spec-3" "--component" "spec/nothing"])]
      (k/exits! r 2) (k/err-has! r "spec/nothing" "kernel"))
    ctx))

;; ------------------------------------------------------------------ 11

(defstep #"a project in the folder wallet/ whose working tree has an uncommitted change to a feature file and an untracked file, and whose HEAD is not the commit tagged spec-3"
  (fn [_]
    (let [ctx (k/wallet-repo!)]
      (k/write! (:dir ctx) "dhf/spec/glossary.md" "# Glossary\n\nafter the cut\n")
      (k/commit-all! (:repo ctx) "after the cut")
      (k/write! (:dir ctx) "dhf/spec/shop/till.feature" "Feature: changed, uncommitted\n")
      (k/write! (:dir ctx) "dhf/spec/untracked.md" "untracked\n")
      ctx)))

(defstep #"ROOM holds the feature file as committed at spec-3, and neither the change nor the untracked file"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (str/includes? (k/slurp-in (room ctx) "dhf/spec/shop/till.feature") "spec-0003") ctx "not as committed at spec-3")
    (k/expect! (not (fs/exists? (k/path (room ctx) "dhf/spec/untracked.md"))) ctx "the untracked file reached ROOM")
    (k/expect! (not (str/includes? (k/slurp-in (room ctx) "dhf/spec/glossary.md") "after the cut")) ctx "a later commit reached ROOM")
    ctx))

;; ------------------------------------------------------------------ 13

(defstep #"a configuration with :spec-layout :slices, and the slice 12-several-specs reaching :spec/parser, :spec/stamper and :measurement/checklist"
  (fn [_]
    (let [ctx (k/slices-project!) d (:dir ctx)
          files {"src/rdm_spec/spec/parser.clj" "(ns p)\n" "src/rdm_spec/spec/stamper.clj" "(ns s)\n" "src/rdm_spec/spec/check.clj" "(ns c)\n"
                 "src/rdm_spec/measurement/checklist.clj" "(ns l)\n" "src/rdm_spec/measurement/scorer.clj" "(ns x)\n"
                 "test/rdm_spec/spec/parser_test.clj" "(ns pt)\n" "test/steps/spec_parser.clj" ";; p\n" "test/steps/measurement_checklist.clj" ";; l\n"
                 "test/steps/spec_check.clj" ";; c\n"}]
      (k/edit-config! d #(assoc % :implementation ["src" "test"] :code-layout code-layout))
      (doseq [[f t] files] (k/write! d f t))
      (k/git! d "init" "-q")
      (k/commit-all! d "spec-3" "spec-3")
      (assoc ctx :impl (set (keys files))))))

(defstep #"ROOM holds no code, unit test or step definition of those three components"
  (fn [ctx] (assert-removed ctx ["src/rdm_spec/spec/parser.clj" "src/rdm_spec/spec/stamper.clj" "src/rdm_spec/measurement/checklist.clj"
                                 "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj" "test/steps/measurement_checklist.clj"])))

