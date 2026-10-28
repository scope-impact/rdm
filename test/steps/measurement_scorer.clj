(ns steps.measurement-scorer
  "Steps of the components Scorer and Scoreboard (07-measure, 13-slice-development/scorer.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn score-edn [ctx & [n]] (some-> (k/slurp-in (:dir ctx) (str "dhf/cycles/" (or n "03") "/score.edn")) edn/read-string))

(defn checklist! [ctx n ids & {:keys [roles]}]
  (k/write! (:dir ctx) (str "dhf/cycles/" n "/checklist.json")
            (json/generate-string {"cycle" n "cut" "spec-3"
                                   "items" (vec (for [[i id] (map-indexed vector ids)]
                                                  (cond-> {"id" id "title" (str "Item " id) "context" "shop" "component" "Cart" "level" "e2e"}
                                                    roles (assoc "slice" (if (< i (first roles)) "12-several-specs" "02-write-the-spec")
                                                                 "role" (if (< i (first roles)) "slice" "regression")))))
                                   "planned" []}))
  ctx)

(defn verdicts! [ctx file vs] (k/write! (:dir ctx) file (pr-str (vec vs))) ctx)
(defn pass [id] {:id id :verdict :pass :evidence (str id " held")})

(defn score-project!
  "A project whose cycle 03 (cut spec-3) was verified on the room tree of nothing, with a checklist of ids."
  [ids & {:keys [cycle ratified roles]}]
  (let [ctx (k/spec-project! {})]
    (k/cycle-edn! (:dir ctx) "03" (merge {:cut "spec-3" :scope :project} cycle))
    (checklist! ctx "03" ids :roles roles)
    (when-not (= ratified :none)
      (k/write! (:dir ctx) "dhf/cycles/03/ratified.edn"
                (pr-str (merge {:cut "spec-3" :room-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904" :outcome :passed
                                :checkout "dhf/cycles/03/build" :checks []} ratified))))
    (verdicts! ctx "verdicts.edn" (map pass ids))
    ctx))

(defn writes-nothing! [ctx] (k/expect! (nil? (score-edn ctx)) ctx "score.edn was written") ctx)

;; ------------------------------------------------------------------ cycles

(defstep #"dhf/cycles/03/cycle.edn holds:"
  (fn [_ doc]
    (let [m (assoc k/shop-model :containers
                   [(assoc (first (:containers k/shop-model)) :contexts
                           [(first (:contexts (first (:containers k/shop-model))))
                            {:id :verification :name "verification" :description "Verifies"
                             :components [{:id :verification/status :name "Status" :description "Derives" :spec "verification/status"}]}])])
          m (update m :views conj {:key "verification-components" :level :component :context :verification :description "d"})
          ctx (k/cycle-project! :model m :features {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})
                                                     "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002"}]})
                                                     "verification/status.feature" (k/feature-text {:name "Status" :scenarios [{:id "spec-0003"}]})})]
      (k/write! (:dir ctx) "dhf/cycles/03/cycle.edn" doc)
      ctx)))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec verify ROOM 03\" are run"
  (fn [ctx] (let [c (k/rdm! ctx "checklist 03") v (k/rdm! ctx "verify ROOM 03")] (assoc c :verify-run v))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/exits! (:verify-run ctx) 0)
    (let [j (k/json-file (k/path (:dir ctx) "dhf/cycles/03/checklist.json"))]
      (k/expect! (= "spec-3" (get j "cut")) ctx "the checklist is not spec-3's")
      (k/expect! (= ["spec-0003"] (map #(get % "id") (get j "items"))) ctx "not verification's items"))
    (k/expect! (= "spec-3" (get-in (:verify-run ctx) [:edn :verify :cut])) ctx "verify did not work on spec-3")
    ctx))

(def scope-model
  (-> k/shop-model
      (assoc-in [:containers 0 :contexts]
                [{:id :verification :name "verification" :description "d" :components [{:id :verification/status :name "Status" :description "d" :spec "verification/status"}]}
                 {:id :knowledge :name "knowledge" :description "d" :components [{:id :knowledge/sources :name "Sources" :description "d" :spec "knowledge/sources"}]}
                 {:id :spec :name "spec" :description "d" :components [{:id :spec/parser :name "Parser" :description "d" :spec "spec/parser"}]}])
      (assoc :views (vec (for [c [:verification :knowledge :spec]] {:key (str (name c) "-components") :level :component :context c :description "d"})))))

(defstep #"dhf/cycles/03/cycle.edn holds the scope (.+)"
  (fn [_ scope]
    (let [ctx (k/spec-project! {"verification/status.feature" (k/feature-text {:name "Status" :scenarios [{:id "spec-0001"}]})
                                "knowledge/sources.feature" (k/feature-text {:name "Sources" :scenarios [{:id "spec-0002"}]})
                                "spec/parser.feature" (k/feature-text {:name "Parser" :scenarios [{:id "spec-0003"}]})}
                               :model scope-model)]
      (k/git! (:dir ctx) "init" "-q")
      (k/commit-all! (:dir ctx) "spec-3" "spec-3")
      (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope (edn/read-string scope)})
      (assoc ctx :scope (edn/read-string scope)))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [ids (set (map #(get % "id") (get (k/json-file (k/path (:dir ctx) "dhf/cycles/03/checklist.json")) "items")))
          want (case (pr-str (:scope ctx))
                 ":project" #{"spec-0001" "spec-0002" "spec-0003"}
                 "{:context :verification}" #{"spec-0001"}
                 "{:component :spec/parser}" #{"spec-0003"}
                 #{"spec-0002" "spec-0003"})]
      (k/expect! (= want ids) ctx (str "the items are " ids)))
    ctx))

;; ------------------------------------------------------------------ the score

(defstep #"cycle 03's checklist holds 2 items and its verification passed on the room tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904"
  (fn [_] (score-project! ["spec-0012" "spec-0014"])))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx] (verdicts! ctx "verdicts.edn" [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
                                           {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}])))

(defstep #"dhf/cycles/03/score.edn holds:"
  (fn [ctx doc]
    (k/exits! ctx 0)
    (k/expect! (= (edn/read-string doc) (score-edn ctx)) ctx (str "score.edn is " (score-edn ctx)))
    ctx))

(defstep #"a verdicts file holding:"
  (fn [_ doc] (let [ctx (score-project! ["spec-0012" "spec-0014"])] (k/write! (:dir ctx) "verdicts.edn" doc) ctx)))

(defstep #"both verdicts are filed"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= [[:pass false] [:fail false]] (map (juxt :verdict #(boolean (:ungraded %))) (:items (score-edn ctx)))) ctx "not both filed") ctx))

(defstep #"two verdicts files, one grading spec-0012 and spec-0013, the other spec-0014"
  (fn [_] (let [ctx (score-project! ["spec-0012" "spec-0013" "spec-0014"])]
            (verdicts! ctx "a.edn" [(pass "spec-0012") (pass "spec-0013")])
            (verdicts! ctx "b.edn" [(pass "spec-0014")]))))

(defstep #"all three verdicts are filed in one score.edn"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= {:passed 3 :scored 3} (select-keys (score-edn ctx) [:passed :scored])) ctx "not three") ctx))

(defstep #"two verdicts files where (both grade spec-0012|b.edn's third line holds a string with an escape EDN refuses)"
  (fn [_ wrong]
    (let [ctx (score-project! ["spec-0012" "spec-0013"])]
      (verdicts! ctx "a.edn" [(pass "spec-0012")])
      (if (str/starts-with? wrong "both")
        (verdicts! ctx "b.edn" [(pass "spec-0012") (pass "spec-0013")])
        (k/write! (:dir ctx) "b.edn" "[{:id \"spec-0013\"\n  :verdict :pass\n  :evidence \"a bad \\q escape\"}]\n"))
      ctx)))

(defstep #"it exits 1, writes nothing, and says (spec-0012 is graded twice, naming both files|b.edn is not readable EDN, naming its line, as FILE:LINE)"
  (fn [ctx says]
    (k/exits! ctx 1)
    (writes-nothing! ctx)
    (if (str/starts-with? says "spec-0012")
      (k/line-has! ctx "spec-0012" "a.edn" "b.edn")
      (k/line-has! ctx "b.edn:3:"))
    ctx))

(defstep #"a verdicts file that (is a map, not a vector|grades spec-0012 twice|gives spec-0012 the verdict :maybe|gives spec-0012 no evidence)"
  (fn [_ m]
    (let [ctx (score-project! ["spec-0012"])]
      (k/write! (:dir ctx) "verdicts.edn"
                (case m
                  "is a map, not a vector" (pr-str {:id "spec-0012" :verdict :pass :evidence "x"})
                  "grades spec-0012 twice" (pr-str [(pass "spec-0012") (pass "spec-0012")])
                  "gives spec-0012 the verdict :maybe" (pr-str [{:id "spec-0012" :verdict :maybe :evidence "x"}])
                  "gives spec-0012 no evidence" (pr-str [{:id "spec-0012" :verdict :pass}])))
      (assoc ctx :malformed m))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx]
    (k/exits! ctx 1)
    (writes-nothing! ctx)
    (k/line-has! ctx "verdicts.edn" (case (:malformed ctx)
                                      "is a map, not a vector" "vector"
                                      "grades spec-0012 twice" "twice"
                                      "gives spec-0012 the verdict :maybe" ":maybe"
                                      "gives spec-0012 no evidence" "evidence"))
    ctx))

(defstep #"a verdicts file with no verdict in it"
  (fn [_] (verdicts! (score-project! ["spec-0012"]) "verdicts.edn" [])))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (k/exits! ctx 2) (writes-nothing! ctx) (k/err-has! ctx "the grading did not happen") ctx))

(def twelve (vec (for [i (range 1 13)] (format "spec-%04d" i))))

(defstep #"cycle 03's checklist holds 12 items" (fn [_] (score-project! twelve)))

(defstep #"a verdicts file grading 11 of them, all passed"
  (fn [ctx] (verdicts! ctx "verdicts.edn" (map pass (take 11 twelve)))))

(defstep #"the score is 11 of 12, and the ungraded item is recorded as failed"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= {:passed 11 :scored 12} (select-keys (score-edn ctx) [:passed :scored])) ctx "not 11 of 12") ctx))

(defstep #"score.edn holds that item as \{:id ID :verdict :fail :evidence \"ungraded: no verdict was given\" :ungraded true\}"
  (fn [ctx]
    (k/expect! (= {:id "spec-0012" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true} (last (:items (score-edn ctx)))) ctx "not that item")
    ctx))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [_] (verdicts! (score-project! ["spec-0012"]) "verdicts.edn" [(pass "spec-0012") (pass "spec-0999")])))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/line-has! ctx "spec-0999") ctx))

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [_] (score-project! ["spec-0012"])))

(defstep #"score.edn names each of them with their role and whether human or agent"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= k/roles (select-keys (score-edn ctx) [:builder :grader :spec-owner])) ctx "not the three roles") ctx))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [_] (score-project! ["spec-0012"] :cycle {:grader {:name "builder-a" :actor :agent}})))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/err-has! ctx "may not grade or read its own score") ctx))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [_] (let [ctx (score-project! ["spec-0012"])]
            (k/edit-edn! (:dir ctx) "dhf/cycles/03/cycle.edn" #(dissoc % :spec-owner))
            ctx)))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/err-has! ctx "spec owner") ctx))

(defstep #"dhf/cycles/03/ratified.edn (holds :outcome :refused|does not exist)"
  (fn [_ state] (score-project! ["spec-0012"] :ratified (if (str/starts-with? state "holds") {:outcome :refused :reason "tests failed"} :none))))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing verification"
  (fn [ctx] (k/exits! ctx 1) (writes-nothing! ctx) (k/err-has! ctx "no passing verification") ctx))

;; ------------------------------------------------------------------ 13

(defstep #"a configuration with :spec-layout :slices, and dhf/cycles/03/cycle.edn whose :scope is \[\{:slice \"12-several-specs\"\}\]"
  (fn [_] (let [ctx (k/slices-project!)]
            (k/git! (:dir ctx) "init" "-q")
            (k/commit-all! (:dir ctx) "spec-3" "spec-3")
            (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope [{:slice "12-several-specs"}]})
            ctx)))

(defstep #"it accepts the scope, and a slice the cut does not hold is refused with exit 2, naming it"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= 10 (count (get (k/json-file (k/path (:dir ctx) "dhf/cycles/03/checklist.json")) "items"))) ctx "not the slice's items")
    (k/slice-refused! ctx)))

(defstep #"a configuration with :spec-layout :slices, and dhf/cycles/03/cycle.edn holding :slice \"12-several-specs\" and no :scope"
  (fn [_] (let [ctx (k/slices-project!)]
            (k/git! (:dir ctx) "init" "-q")
            (k/commit-all! (:dir ctx) "spec-3" "spec-3")
            (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :slice "12-several-specs"})
            ctx)))

(defstep #"it reads the cycle as scoped to \[\{:slice \"12-several-specs\"\}\]"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= [{:slice "12-several-specs"}] (get-in ctx [:edn :checklist :scope])) ctx (str "the scope is " (get-in ctx [:edn :checklist :scope])))
    ctx))

(defstep #"a cycle holding both :slice and :scope is refused with exit 2, naming both"
  (fn [ctx]
    (k/edit-edn! (:dir ctx) "dhf/cycles/03/cycle.edn" #(assoc % :scope :project))
    (let [r (k/rdm! ctx "checklist 03")]
      (k/exits! r 2) (k/err-has! r ":slice" ":scope"))
    ctx))

(defstep #"a verified slice cycle 03 whose checklist holds 4 items of its slice and 6 regression items, and verdicts passing 3 of the slice's and all 6 regression items"
  (fn [_]
    (let [ids (vec (for [i (range 1 11)] (format "spec-%04d" i)))
          ctx (score-project! ids :roles [4] :cycle {:slice "12-several-specs"})]
      (k/edit-edn! (:dir ctx) "dhf/cycles/03/cycle.edn" #(dissoc % :scope))
      (-> (verdicts! ctx "v.edn" (concat [(pass "spec-0001") (pass "spec-0002") (pass "spec-0003") {:id "spec-0004" :verdict :fail :evidence "no"}]
                                         (map pass (drop 4 ids))))
          (assoc-in [:vars "V"] (k/path (:dir ctx) "v.edn"))))))

(defstep #"dhf/cycles/03/score.edn holds :passed 9 and :scored 10, and :slice \{:name \"12-several-specs\" :passed 3 :scored 4\} and :regression \{:passed 6 :scored 6\}"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= {:passed 9 :scored 10 :slice {:name "12-several-specs" :passed 3 :scored 4} :regression {:passed 6 :scored 6}}
                  (select-keys (score-edn ctx) [:passed :scored :slice :regression])) ctx (str "score.edn is " (score-edn ctx)))
    ctx))

;; ------------------------------------------------------------------ the scoreboard

(defn scored!
  "Cycle n scored items {id verdict} with scope."
  [ctx n items & {:keys [scope bootstrap]}]
  (k/cycle-edn! (:dir ctx) n (cond-> {:cut "spec-3" :scope (or scope :project)} bootstrap (assoc :bootstrap true)))
  (k/write! (:dir ctx) (str "dhf/cycles/" n "/score.edn")
            (k/pp {:cycle n :cut "spec-3" :scope (or scope :project) :room-tree "t"
                   :builder {:name "b" :actor :agent} :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}
                   :items (vec (for [[id v] items] {:id id :verdict v :evidence "e"}))
                   :passed (count (filter #{:pass} (vals items))) :scored (count items)}))
  ctx)

(defn ids [from to] (for [i (range from (inc to))] (format "spec-%04d" i)))

(defstep #"cycle 01 and cycle 02 scored over the same 12 items"
  (fn [_] (let [ctx (k/spec-project! {})]
            (scored! ctx "01" (zipmap (ids 1 12) (concat (repeat 3 :fail) [:pass] (repeat 8 :pass))))
            ctx)))

(defstep #"3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01"
  (fn [ctx] (scored! ctx "02" (zipmap (ids 1 12) (concat (repeat 3 :pass) [:fail] (repeat 8 :pass))))))

(defstep #"it reports 3 got better and 1 got worse"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= [3 1] ((juxt :better :worse) (get-in ctx [:edn :scoreboard]))) ctx "not 3 and 1") ctx))

(defstep #"it reports no net figure"
  (fn [ctx] (k/expect! (not (re-find #"(?i)net" (str (:out ctx)))) ctx "a net figure") ctx))

(defstep #"cycle 01 scored 12 items and cycle 02 scored 15"
  (fn [_] (let [ctx (k/spec-project! {})]
            (scored! ctx "01" (zipmap (ids 1 12) (repeat :pass)))
            (scored! ctx "02" (zipmap (ids 1 15) (repeat :pass))))))

(defstep #"it compares only the 12 items both scored"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= 12 (get-in ctx [:edn :scoreboard :compared])) ctx "not 12") ctx))

(defstep #"it states that the denominator moved from 12 to 15"
  (fn [ctx] (k/expect! (some #(re-find #"denominator moved from 12 to 15" %) (get-in ctx [:edn :scoreboard :statements])) ctx "not stated") ctx))

(defstep #"it counts the 3 new items separately"
  (fn [ctx] (k/expect! (= (vec (ids 13 15)) (get-in ctx [:edn :scoreboard :only-in "02"])) ctx "not the 3 new items") ctx))

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [_] (let [ctx (k/spec-project! {})]
            (scored! ctx "01" (zipmap (ids 1 6) [:fail :pass :pass :pass :pass :pass]))
            (scored! ctx "02" (zipmap (ids 1 2) [:pass :fail]) :scope {:context :verification}))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= {:compared 2 :better 1 :worse 1} (select-keys (get-in ctx [:edn :scoreboard :target]) [:compared :better :worse])) ctx "not verification's") ctx))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx] (k/expect! (some #(str/includes? % "the rest was not scored in cycle 02") (get-in ctx [:edn :scoreboard :statements])) ctx "not stated") ctx))

(defstep #"cycle 00's cycle.edn holds :bootstrap true"
  (fn [_] (let [ctx (k/spec-project! {})]
            (scored! ctx "00" (zipmap (ids 1 3) (repeat :pass)) :bootstrap true)
            (scored! ctx "01" (zipmap (ids 1 3) [:pass :fail :pass])))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= ["00"] (get-in ctx [:edn :scoreboard :bootstrap])) ctx "not marked") ctx))

(defstep #"it reports no better or worse count against it"
  (fn [ctx] (let [s (get-in ctx [:edn :scoreboard])] (k/expect! (not (or (contains? s :better) (contains? s :worse))) ctx "a count")) ctx))

(defstep #"cycle 01 and cycle 02 each scored 5 items of the whole project, 1 better and 1 worse"
  (fn [_] (let [ctx (k/spec-project! {})]
            (scored! ctx "01" (zipmap (ids 1 5) [:pass :fail :pass :pass :pass]))
            (scored! ctx "02" (zipmap (ids 1 5) [:fail :pass :pass :pass :pass])))))

(defstep #"cycle 02 scored 5 items of the whole project and cycle 03, scoped to the component shop/till, scored 2 of them, 1 better"
  (fn [_] (let [ctx (k/spec-project! {})]
            (scored! ctx "02" (zipmap (ids 1 5) [:pass :pass :pass :pass :fail]))
            (scored! ctx "03" (zipmap (ids 4 5) [:pass :pass]) :scope {:component :shop/till}))))

