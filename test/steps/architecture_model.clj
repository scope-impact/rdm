(ns steps.architecture-model
  "Step definitions for dhf/spec/architecture/model.feature. Each scenario
  changes the model of a copy of the room (its views generated first) and
  runs `rdm-spec check` through the shared When (kernel_command)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.support :as s]))

(defn- model-text!
  "Writes `lines` as the project's model; returns ctx."
  [ctx lines]
  (let [ctx (a/ensure-room! ctx)]
    (s/write! (:dir ctx) a/model-rel (str (str/join "\n" lines) "\n"))
    ctx))

(defn- filler [n] (vec (for [i (range n)] (str ";; line " (inc i) " of a model fixture"))))

(defn- line-of [dir n] (nth (str/split-lines (slurp (str (fs/file dir a/model-rel)))) (dec n)))

;; ---------------------------------------------------------------------------
;; The model is read whole or not at all

(defstep #"a model file with an unclosed map on line (\d+)"
  (fn [ctx n]
    (let [n (parse-long n)
          ctx (model-text! ctx (concat (filler (- n 3))
                                       ["{:system {:id :s :name \"S\" :description \"A system\"}"
                                        " :people [{:id :a :name \"A\" :description \"A person\"}"
                                        "          {:id :b :name \"B\" :description \"B person\""
                                        "          {:id :c :name \"C\" :description \"C person\"}]"
                                        " :views []}"]))]
      (s/check! (str/starts-with? (str/trim (line-of (:dir ctx) n)) "{:id :b") "the fixture's map is not on its line")
      (assoc ctx :line n))))

(defstep #"it exits 1 and names the file and line (\d+)"
  (fn [ctx n]
    (let [r (a/the-run ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (a/line-with r (str a/model-rel ":" n ":")) (str "no finding names " a/model-rel ":" n "\n" (s/show r))))))

(defstep #"a model file with a \"(.)\" on line (\d+) that closes nothing"
  (fn [ctx ch n]
    (let [n (parse-long n)
          ctx (model-text! ctx (concat (filler (- n 3))
                                       ["{:system {:id :s :name \"S\" :description \"A system\"}"
                                        " :people [{:id :a :name \"A\" :description \"A person\"}"
                                        (str "          " ch)
                                        "          {:id :b :name \"B\" :description \"B person\"}]"
                                        " :views []}"]))]
      (s/check! (= ch (str/trim (line-of (:dir ctx) n))) "the fixture's bracket is not on its line")
      ctx)))

(defstep #"it exits 1 and names the file, line (\d+) and the \"(.)\""
  (fn [ctx n ch]
    (let [r (a/the-run ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (a/line-with r (str a/model-rel ":" n ":") ch)
                (str "no finding names " a/model-rel ":" n " and " ch "\n" (s/show r))))))

(defstep #"a container carrying the key (:\S+), which the model's shape does not declare"
  (fn [ctx k]
    (-> (a/change-model! ctx #(a/update-element % :repository (fn [c] (assoc c (keyword (subs k 1)) "someone"))))
        (assoc :element :repository :key k))))

(defstep #"it exits 1 and names the container and the key"
  (fn [ctx]
    (let [r (a/the-run ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (a/line-with r a/model-rel (pr-str (:element ctx)) (:key ctx))
                (str "no finding names " (:element ctx) " and " (:key ctx) "\n" (s/show r))))))

(defstep #"a container and a component both with the id (:\S+)"
  (fn [ctx id]
    (let [k (keyword (subs id 1))]
      (-> (a/change-model! ctx (fn [m]
                                 (-> m
                                     (update :containers conj {:id k :name "Alpha store" :kind :stores :technology "files"
                                                               :description "A store of the fixture"})
                                     (a/add-component :spec {:id k :name "Alpha part" :spec "spec/parser"
                                                             :description "A component of the fixture"}))))
          (assoc :id id :names ["Alpha store" "Alpha part"])))))

(defstep #"it exits 1 and names the id and both elements"
  (fn [ctx]
    (let [r (a/the-run ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (apply a/line-with r (:id ctx) (:names ctx))
                (str "no finding names " (:id ctx) " and both of " (pr-str (:names ctx)) "\n" (s/show r))))))

(defstep #"a relationship from (:\S+) to (:\S+), and no element (:\S+)"
  (fn [ctx from to missing]
    (let [kw #(keyword (subs % 1))
          ctx (a/change-model! ctx (fn [m] (update m :relationships conj
                                                   {:from (kw from) :to (kw to) :description "Archives into"})))
          m (a/read-model (:dir ctx))
          ids (set (concat [(get-in m [:system :id])] (map :id (:people m)) (map :id (:external m))
                           (map :id (:containers m)) (map :id (mapcat :contexts (:containers m)))
                           (map :id (mapcat :components (mapcat :contexts (:containers m)))) (map :id (:kernel m))))]
      (s/check! (not (ids (kw missing))) (str "the model has an element " missing))
      (assoc ctx :rel [from to] :missing missing))))

(defstep #"it exits 1 and names the relationship and (:\S+)"
  (fn [ctx missing]
    (let [r (a/the-run ctx)
          [from to] (:rel ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (a/line-with r from to missing) (str "no finding names " from " -> " to " and " missing "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A component's feature file is found from the model, never guessed

(defstep #"the component (:\S+) with the spec path \"([^\"]+)\""
  (fn [ctx id spec]
    (let [ctx (a/ensure-room! ctx)
          k (keyword (subs id 1))
          m (a/read-model (:dir ctx))
          comp (first (for [c (:containers m) x (:contexts c) y (:components x) :when (= k (:id y))] y))]
      (s/check! (= spec (:spec comp)) (str "the model gives " id " the spec path " (pr-str (:spec comp))))
      (assoc ctx :element k :spec spec))))

(defstep #"the component owns the feature file (\S+), and \"rdm-spec check\" passes only when that file exists"
  (fn [ctx file]
    (let [r (a/the-run ctx)
          dir (:dir ctx)]
      (s/check! (= file (str "dhf/spec/" (:spec ctx) ".feature")) "the step names another file than the spec path")
      (s/check! (fs/exists? (fs/file dir file)) (str file " does not exist"))
      (s/check! (= 0 (:exit r)) (str "the check fails while " file " exists\n" (s/show r)))
      ;; moved away, the component owns no file and the file belongs to no component
      (fs/move (fs/file dir file) (fs/file dir "dhf/spec/spec/moved.feature"))
      (let [r2 (s/rdm-spec! dir "check")]
        (s/check! (= 1 (:exit r2)) (str "the check passes without " file "\n" (s/show r2)))
        (s/check! (a/line-with r2 ": a-scenario-owner: " (pr-str (:element ctx)) file)
                  (str "no finding says " (:element ctx) " owns no " file "\n" (s/show r2)))))))

;; ---------------------------------------------------------------------------
;; Views go stale when the model's content changes, never its formatting

(defstep #"views generated from the model"
  (fn [ctx]
    (let [ctx (a/ensure-room! ctx)
          dir (:dir ctx)
          r (s/rdm-spec! dir "check")]
      (s/check! (not (a/line-with r "stale")) (str "a view is stale right after rdm-spec views\n" (s/show r)))
      (assoc ctx :model-before (a/read-model dir)))))

(defstep #"the model's comments and whitespace are changed and nothing else"
  (fn [ctx]
    (let [dir (:dir ctx)
          f (str (fs/file dir a/model-rel))
          text (slurp f)
          reformatted (str ";; A comment added by the scenario\n\n"
                           (str/join "\n" (map #(if (str/blank? %) "" (str "   " % "   ")) (str/split-lines text)))
                           "\n\n;; and one at the end\n")]
      (spit f reformatted)
      (s/check! (not= text reformatted) "nothing was reformatted")
      (s/check! (= (:model-before ctx) (edn/read-string reformatted)) "the reformatting changed the model's content")
      ctx)))

(defstep #"it reports no stale view"
  (fn [ctx]
    (let [r (a/the-run ctx)]
      (s/check! (not (a/line-with r "stale")) (str "a view is reported stale\n" (s/show r)))
      (s/check! (= 0 (:exit r)) (str "the check fails\n" (s/show r))))))

(defstep #"one element's description is changed"
  (fn [ctx]
    (let [f (str (fs/file (:dir ctx) a/model-rel))
          text (slurp f)
          old "\"Writes the spec and its architecture\""]
      (s/check! (str/includes? text old) "the model has no description to change")
      (spit f (str/replace text old "\"Writes the spec and the model\""))
      ctx)))

(defstep #"it fails and names every view as stale"
  (fn [ctx]
    (let [r (a/the-run ctx)
          ks (map :key (:views (a/read-model (:dir ctx))))]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (seq ks) "the model has no view")
      (doseq [k ks]
        (s/check! (a/line-with r (pr-str k) "stale") (str "no finding names the view " k " as stale\n" (s/show r)))))))
