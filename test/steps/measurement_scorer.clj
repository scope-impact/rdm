(ns steps.measurement-scorer
  "Step definitions of dhf/spec/measurement/scorer.feature."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn scored-cycle!
  "Cycle 03 of cut spec-3: its checklist holds `ids`, its verification
  passed on the empty tree, and the roles are declared; opts may replace the
  cycle's declaration and the verdicts."
  ([ctx] (scored-cycle! ctx {}))
  ([ctx {:keys [ids cycle ratified]}]
   (if (::ready ctx)
     ctx
     (let [ctx (k/project! ctx)
           ids (or ids ["spec-0012" "spec-0014"])]
       (k/write-files! (:dir ctx)
                       {"dhf/cycles/03/cycle.edn" (k/pr-edn (or cycle (merge {:cut "spec-3" :scope :project} k/roles)))
                        "dhf/cycles/03/checklist.json"
                        (json/generate-string {"cycle" "03" "cut" "spec-3"
                                               "items" (for [i ids] {"id" i "title" (str "About " i) "context" "kernel" "component" "Core" "level" "e2e"})
                                               "planned" []})})
       (when-not (= ratified :none)
         (k/write-files! (:dir ctx) {"dhf/cycles/03/ratified.edn" (k/pr-edn (or ratified {:cut "spec-3" :room-tree empty-tree :outcome :passed
                                                                                            :checkout "dhf/cycles/03/build" :checks []}))}))
       (assoc ctx ::ready true ::ids ids)))))

(defn verdicts! [ctx text]
  (k/write-files! (:dir ctx) {"verdicts.edn" text})
  ctx)

(defn all-pass [ids] (k/pr-edn (vec (for [i ids] {:id i :verdict :pass :evidence "it held"}))))

(defn score-file [ctx] (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03/score.edn"))
(defn score [ctx]
  (let [f (score-file ctx)]
    (when-not (fs/exists? f) (fail! (str "no score.edn: " (:err ctx))))
    (edn/read-string (slurp f))))

(defn writes-nothing [ctx]
  (assert (not (fs/exists? (score-file ctx))) "score.edn was written")
  ctx)

(defstep #"dhf/cycles/03/cycle.edn holds:"
  (fn [ctx doc]
    (let [ctx (k/project! ctx {:model-spec {:contexts [["verification" ["status"]] ["spec" ["parser"]]]}
                               :features {"dhf/spec/verification/status.feature"
                                          (k/feature-text {:name "Status" :tags ["UN-001"] :scenarios [(k/sc 1 "Status works") (k/sc 2 "And more")]})
                                          "dhf/spec/spec/parser.feature"
                                          (k/feature-text {:name "Parser" :tags ["UN-001"] :scenarios [(k/sc 3 "Parser works")]})}})]
      (fs/delete (fs/path (:dir ctx) "dhf/spec/kernel/core.feature"))
      (let [ctx (k/room! ctx)]
        (k/write-files! (:dir ctx) {"dhf/cycles/03/cycle.edn" doc})
        (k/tag-cut! ctx "spec-3")))))

(defstep #"\"rdm-spec checklist 03\" and \"rdm-spec verify ROOM 03\" are run"
  (fn [ctx]
    (let [a (k/rdm ctx ["checklist" "03"])
          b (k/rdm ctx ["verify" (get-in ctx [:ph "ROOM"]) "03"])]
      (assoc ctx ::checklist a ::verify b))))

(defstep #"both work on the cut spec-3, and the checklist holds the items of that scope"
  (fn [ctx]
    (k/check-exit (::checklist ctx) 0)
    (k/check-exit (::verify ctx) 0)
    (let [c (json/parse-string (slurp (fs/file (:dir ctx) "dhf/cycles/03/checklist.json")))]
      (assert (= "spec-3" (get c "cut")))
      (assert (= ["spec-0001" "spec-0002"] (map #(get % "id") (get c "items"))) (pr-str c))
      (assert (= "spec-3" (get-in (k/edn-out (::verify ctx)) [:verify :cut]))))
    ctx))

(defstep #"dhf/cycles/03/cycle.edn holds the scope (.+)"
  (fn [ctx scope]
    (let [ctx (k/project! ctx {:model-spec {:contexts [["spec" ["parser"]] ["knowledge" ["sources"]] ["verification" ["status"]]]}
                               :features (into {} (for [[i c] (map-indexed vector ["spec/parser" "knowledge/sources" "verification/status"])]
                                                    [(str "dhf/spec/" c ".feature")
                                                     (k/feature-text {:name c :tags ["UN-001"] :scenarios [(k/sc (inc i) c)]})]))})]
      (fs/delete (fs/path (:dir ctx) "dhf/spec/kernel/core.feature"))
      (k/cycle! ctx "03" (merge {:cut "spec-3" :scope (edn/read-string scope)} k/roles))
      (k/tag-cut! ctx "spec-3"))))

(defstep #"it accepts the scope"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/checklist.json")))
    ctx))

(defstep #"cycle 03's checklist holds 2 items and its verification passed on the room tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904"
  (fn [ctx] (scored-cycle! ctx)))

(defstep #"a verdicts file grading spec-0012 pass and spec-0014 fail"
  (fn [ctx]
    (verdicts! ctx (k/pr-edn [{:id "spec-0012" :verdict :pass :evidence "check exited 1 naming the view"}
                              {:id "spec-0014" :verdict :fail :evidence "no finding was printed"}]))))

(defstep #"dhf/cycles/03/score.edn holds:"
  (fn [ctx doc]
    (k/check-exit ctx 0)
    (assert (= (edn/read-string doc) (score ctx)) (pr-str (score ctx)))
    ctx))

(defstep #"a verdicts file holding:"
  (fn [ctx doc] (verdicts! (scored-cycle! ctx) doc)))

(defstep #"both verdicts are filed"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= [[:pass "check exited 1 naming the view"] [:fail "no finding was printed"]]
               (map (juxt :verdict :evidence) (:items (score ctx)))))
    ctx))

(def malformed
  {"is a map, not a vector" ["{:id \"spec-0012\" :verdict :pass :evidence \"x\"}" "vector"]
   "grades spec-0012 twice" ["[{:id \"spec-0012\" :verdict :pass :evidence \"x\"} {:id \"spec-0012\" :verdict :fail :evidence \"y\"}]" "twice"]
   "gives spec-0012 the verdict :maybe" ["[{:id \"spec-0012\" :verdict :maybe :evidence \"x\"}]" ":maybe"]
   "gives spec-0012 no evidence" ["[{:id \"spec-0012\" :verdict :pass}]" "evidence"]})

(defstep #"a verdicts file that (.+)"
  (fn [ctx what]
    (let [[text says] (or (malformed what) (fail! what))]
      (-> (scored-cycle! ctx) (verdicts! text) (assoc ::says says)))))

(defstep #"it exits 1, writes nothing, and says what is malformed"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx (::says ctx)) (writes-nothing ctx)))

(defstep #"a verdicts file with no verdict in it"
  (fn [ctx] (verdicts! (scored-cycle! ctx) "[]\n")))

(defstep #"it exits 2, writes nothing, and says the grading did not happen"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "grading did not happen") (writes-nothing ctx)))

(def twelve (vec (for [i (range 1 13)] (format "spec-%04d" i))))

(defstep #"cycle 03's checklist holds 12 items"
  (fn [ctx] (scored-cycle! ctx {:ids twelve})))

(defstep #"a verdicts file grading 11 of them, all passed"
  (fn [ctx] (verdicts! ctx (all-pass (butlast twelve)))))

(defstep #"the score is 11 of 12, and the ungraded item is recorded as failed"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [s (score ctx)]
      (assert (= [11 12] [(:passed s) (:scored s)]) (pr-str s))
      ctx)))

(defstep #"score.edn holds that item as \{:id ID :verdict :fail :evidence \"ungraded: no verdict was given\" :ungraded true\}"
  (fn [ctx]
    (assert (= {:id "spec-0012" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}
               (last (:items (score ctx)))))
    ctx))

(defstep #"a verdicts file grading spec-0999, which is not on cycle 03's checklist"
  (fn [ctx] (verdicts! (scored-cycle! ctx) (k/pr-edn [{:id "spec-0999" :verdict :pass :evidence "x"}]))))

(defstep #"it exits 1, writes nothing, and names spec-0999"
  (fn [ctx] (k/check-exit ctx 1) (k/err-has ctx "spec-0999") (writes-nothing ctx)))

(defn roles-cycle! [ctx decl]
  (let [ctx (scored-cycle! ctx {:cycle (merge {:cut "spec-3" :scope :project} decl)})]
    (verdicts! ctx (all-pass ["spec-0012" "spec-0014"]))))

(defstep #"cycle 03 declares builder-a as an agent builder, grader-b as an agent grader and Sami as a human spec owner"
  (fn [ctx] (roles-cycle! ctx k/roles)))

(defstep #"score.edn names each of them with their role and whether human or agent"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= k/roles (select-keys (score ctx) [:builder :grader :spec-owner])))
    ctx))

(defstep #"cycle 03 declares builder-a as both its builder and its grader"
  (fn [ctx] (roles-cycle! ctx (assoc k/roles :grader {:name "builder-a" :actor :agent}))))

(defstep #"it exits 1, writes nothing, and says the builder may not grade or read its own score"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "builder may not grade or read its own score") (writes-nothing ctx)))

(defstep #"cycle 03 declares a builder and a grader and no spec owner"
  (fn [ctx] (roles-cycle! ctx (dissoc k/roles :spec-owner))))

(defstep #"it exits 1, writes nothing, and names the missing role"
  (fn [ctx] (k/check-exit ctx 1) (k/err-has ctx ":spec-owner") (writes-nothing ctx)))

(defstep #"dhf/cycles/03/ratified.edn (holds :outcome :refused|does not exist)"
  (fn [ctx state]
    (let [ctx (scored-cycle! ctx {:ratified (if (= state "does not exist") :none
                                                {:cut "spec-3" :room-tree empty-tree :outcome :refused :reason "the test command failed"})})]
      (verdicts! ctx (all-pass ["spec-0012" "spec-0014"])))))

(defstep #"it exits 1, writes nothing, and says the cycle has no passing verification"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "no passing verification") (writes-nothing ctx)))
