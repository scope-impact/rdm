(ns steps.architecture-model
  "Step definitions for dhf/spec/architecture/model.feature: a copy of the
  room's spec whose model a step breaks, edits or reformats."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.support :as s]))

;; --- The model is read whole or not at all -----------------------------------

(defstep #"a model file with an unclosed map on line (\d+)"
  (fn [ctx n]
    (a/needs-check!)
    (let [dir (a/dir-of ctx)
          n (parse-long n)
          head (for [i (range 1 n)] (str ";; line " i " of a model whose top-level map is never closed"))
          text (str (str/join "\n" head) "\n"
                    "{:system {:id :rdm-spec :name \"rdm-spec\" :description \"Regenerates a build\"}\n"
                    " :people [{:id :engineer :name \"Engineer\" :description \"Writes the spec\"}]\n"
                    " :views [{:key \"context\" :level :context :description \"The context\"}]\n")]
      (spit (str (fs/file dir a/model-rel)) text)
      (assoc ctx :dir dir))))

(defstep #"it exits 1 and names the file and line (\d+)"
  (fn [ctx n]
    (let [r (:run ctx)]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (some #(str/starts-with? % (str a/model-rel ":" n ": ")) (s/err-lines r))
                (str "no finding names " a/model-rel ":" n "\n" (s/show r))))))

(defstep #"a container carrying the key (:\S+), which the model's shape does not declare"
  (fn [ctx k]
    (-> (a/edit-model ctx (fn [m] (update m :containers a/update-where #(= :repository (:id %))
                                          #(assoc % (keyword (subs k 1)) "Engineer"))))
        (assoc :expect [":repository" k]))))

(defstep #"it exits 1 and names the container and the key"
  (fn [ctx]
    (a/check-names! (:run ctx) 1 "model-shape" (:expect ctx))))

(defstep #"a container and a component both with the id (:\S+)"
  (fn [ctx id]
    (let [k (keyword (subs id 1))]
      (-> (a/edit-model ctx (fn [m]
                              (-> m
                                  (update :containers conj {:id k :name "Spec store" :kind :stores :technology "files"
                                                            :description "Holds the spec"})
                                  (a/update-context :architecture
                                                    #(update % :components conj
                                                             {:id k :name "Spec reader" :spec "architecture/model"
                                                              :description "Reads the spec"})))))
          (assoc :expect [id "container" "component"])))))

(defstep #"it exits 1 and names the id and both elements"
  (fn [ctx]
    (a/check-names! (:run ctx) 1 "model-id" (:expect ctx))))

(defstep #"a relationship from (:\S+) to (:\S+), and no element (:\S+)"
  (fn [ctx from to missing]
    (let [k #(keyword (subs % 1))
          ids (fn [m] (set (concat [(get-in m [:system :id])]
                                   (map :id (:people m)) (map :id (:external m)) (map :id (:kernel m))
                                   (for [c (:containers m) x (cons c (:contexts c))] (:id x))
                                   (for [c (:containers m) x (:contexts c) y (:components x)] (:id y)))))]
      (-> (a/edit-model ctx (fn [m]
                              (s/check! (not ((ids m) (k missing))) (str "the model has an element " missing))
                              (s/check! ((ids m) (k from)) (str "the model has no element " from))
                              (update m :relationships conj {:from (k from) :to (k to) :description "Archives to"})))
          (assoc :expect [(str from " -> " to) missing])))))

(defstep #"it exits 1 and names the relationship and (:\S+)"
  (fn [ctx _]
    (a/check-names! (:run ctx) 1 "model-reference" (:expect ctx))))

;; --- A component's feature file is found from the model ----------------------

(defstep #"the component (:\S+) with the spec path \"([^\"]+)\""
  (fn [ctx id spec]
    ;; "passes" needs every stage of check built
    (s/needs-built! 'rdm-spec.knowledge.sources 'rdm-spec.spec.check 'rdm-spec.spec.parser
                    'rdm-spec.architecture.lint 'rdm-spec.architecture.views)
    (let [dir (a/dir-of ctx)
          m (a/read-model dir)
          comp (first (for [c (:containers m) x (:contexts c) y (:components x)
                            :when (= (keyword (subs id 1)) (:id y))] y))]
      (s/check! comp (str "the model has no component " id))
      (s/check! (= spec (:spec comp)) (str id " has the spec path " (pr-str (:spec comp))))
      ;; the views are fresh, so nothing but the feature file decides the check
      (s/check! (= 0 (:exit (s/rdm-spec! dir "views"))) "rdm-spec views failed")
      (assoc ctx :dir dir :component id :spec-path spec))))

(defstep #"the component owns the feature file (\S+), and \"rdm-spec check\" passes only when that file exists"
  (fn [ctx file]
    (let [dir (:dir ctx)
          r (:run ctx)
          owner-lines (fn [run] (filter #(str/includes? % (:component ctx)) (a/rule-lines run "a-scenario-owner")))]
      (s/check! (= file (str "dhf/spec/" (:spec-path ctx) ".feature")) (str file " is not the spec path's file"))
      ;; with the file: passes
      (s/check! (fs/exists? (fs/file dir file)) (str file " does not exist"))
      (s/check! (= 0 (:exit r)) (str "check does not pass with " file "\n" (s/show r)))
      ;; without it: fails, naming the component and the file
      (let [saved (slurp (str (fs/file dir file)))]
        (fs/delete (fs/file dir file))
        (let [r2 (s/rdm-spec! dir "check")]
          (spit (str (fs/file dir file)) saved)
          (s/check! (= 1 (:exit r2)) (str "check does not fail without " file "\n" (s/show r2)))
          (s/check! (some #(str/includes? % file) (owner-lines r2))
                    (str "no a-scenario-owner finding names " (:component ctx) " and " file "\n" (s/show r2)))))
      ;; never guessed: a spec path elsewhere fails although the file exists
      (let [m (a/read-model dir)
            k (keyword (subs (:component ctx) 1))]
        (a/write-model! dir (a/update-context m (keyword (namespace k))
                                              #(update % :components a/update-where (fn [c] (= k (:id c)))
                                                       (fn [c] (assoc c :spec (str (:spec-path ctx) "-elsewhere"))))))
        (let [r3 (s/rdm-spec! dir "check")]
          (s/check! (= 1 (:exit r3)) (s/show r3))
          (s/check! (seq (owner-lines r3)) (str "no a-scenario-owner finding names " (:component ctx) "\n" (s/show r3)))
          (s/check! (some #(str/starts-with? % file) (a/rule-lines r3 "a-scenario-owner"))
                    (str file " is not named as belonging to no component\n" (s/show r3))))))))

;; --- Views go stale when the model's content changes -------------------------

(defstep #"views generated from the model"
  (fn [ctx]
    (a/needs-check!)
    (let [dir (a/dir-of ctx)
          r (s/rdm-spec! dir "views")]
      (s/check! (= 0 (:exit r)) (str "rdm-spec views failed\n" (s/show r)))
      (assoc ctx :dir dir))))

(defstep #"the model's comments and whitespace are changed and nothing else"
  (fn [ctx]
    (let [f (str (fs/file (:dir ctx) a/model-rel))
          before (slurp f)
          after (-> before
                    (str/replace "\n " "\n\n   ")
                    (str/replace #"(?m)^;;" ";;;; reworded comment:")
                    (->> (str ";; A comment added at the top\n\n")))]
      (s/check! (not= before after) "nothing changed")
      (s/check! (= (edn/read-string before) (edn/read-string after)) "the model's value changed")
      (spit f after))))

(defstep #"one element's description is changed"
  (fn [ctx]
    (let [f (str (fs/file (:dir ctx) a/model-rel))
          before (slurp f)
          after (str/replace-first before "Holds merges and pull requests" "Holds merges, pull requests and tags")]
      (s/check! (not= before after) "the description to change was not found")
      (spit f after))))

(defstep #"it reports no stale view"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (not= 2 (:exit r)) (s/show r))
      (s/check! (empty? (concat (a/rule-lines r "views-stale") (a/rule-lines r "views-missing")))
                (str "a view is reported stale\n" (s/show r))))))

(defstep #"it fails and names every view as stale"
  (fn [ctx]
    (let [r (:run ctx)
          stale (a/rule-lines r "views-stale")]
      (s/check! (= 1 (:exit r)) (s/show r))
      (doseq [k (map :key (:views (a/read-model (:dir ctx))))]
        (s/check! (some #(str/includes? % (str "view \"" k "\"")) stale)
                  (str "the view " k " is not named stale\n" (s/show r)))))))
