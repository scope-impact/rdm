(ns steps.architecture-model
  "Step definitions for dhf/spec/architecture/model.feature: `rdm-spec
  check` on a copy of the room whose model.edn a step writes or edits
  (steps.architecture-support).

  Context keys set here: :dir the project; :line the line a finding must
  name; :named what it must name; :spec-feature the component's feature
  file (spec-0076)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def ^:private model-lines
  "A model, one line per entry: line 12 opens the person :bather."
  [";; A fixture model."                                                ; 1
   "{:system {:id :sauna"                                               ; 2
   "          :name \"Sauna\""                                          ; 3
   "          :description \"Books sauna slots\"}"                      ; 4
   " :external []"                                                      ; 5
   " :containers []"                                                    ; 6
   " :relationships []"                                                 ; 7
   " :views []"                                                         ; 8
   " ;; the people"                                                     ; 9
   " :people"                                                           ; 10
   " ["                                                                 ; 11
   "  {:id :bather :name \"Bather\" :description \"Books a slot\" :actor #{:human}}" ; 12
   "  {:id :host :name \"Host\" :description \"Heats the sauna\" :actor #{:human}}"   ; 13
   " ]}"])                                                              ; 14

(defn- write-model-text! [ctx lines]
  (let [dir (a/room-copy ctx)]
    (s/write! dir (a/model-rel dir) (str (str/join "\n" lines) "\n"))
    (assoc ctx :dir dir)))

(defstep #"a model file with an unclosed map on line (\d+)"
  (fn [ctx n]
    (let [n (parse-long n)
          ls (update model-lines (dec n) #(str/replace % #"\}$" ""))]
      (s/check! (not= ls model-lines) (str "line " n " closes no map"))
      (assoc (write-model-text! ctx ls) :line n :named []))))

(defstep #"a model file with a \"\}\" on line (\d+) that closes nothing"
  (fn [ctx n]
    (let [n (parse-long n)
          ;; the model, closed on one line, then a stray "}" on line n
          whole (str/join " " (remove #(str/starts-with? % ";") (map str/trim (rest model-lines))))
          ls (vec (concat [";; A fixture model." whole] (repeat (- n 3) "") ["}"]))]
      (s/check! (= "}" (nth ls (dec n))) "the stray } is not on its line")
      (assoc (write-model-text! ctx ls) :line n :named ["\"}\""]))))

(defstep #"it exits 1 and names the file and line (\d+)"
  (fn [ctx n]
    (let [r (kc/exits! ctx 1)
          prefix (str (a/model-rel (:dir ctx)) ":" n ": ")]
      (s/check! (some #(str/starts-with? % prefix) (kc/finding-lines r))
                (str "no finding names " prefix "\n" (s/show r)))
      nil)))

(defstep #"it exits 1 and names the file, line (\d+) and the \"\}\""
  (fn [ctx n]
    (let [r (kc/exits! ctx 1)
          prefix (str (a/model-rel (:dir ctx)) ":" n ": ")]
      (s/check! (some #(and (str/starts-with? % prefix) (str/includes? % "\"}\"")) (kc/finding-lines r))
                (str "no finding names " prefix " and the \"}\"\n" (s/show r)))
      nil)))

(defn- update-where [xs pred f] (mapv #(if (pred %) (f %) %) xs))

(defn- edited [ctx f named]
  (let [dir (a/room-copy ctx)]
    (a/update-model! dir f)
    (assoc ctx :dir dir :named named)))

(defstep #"a container carrying the key (\S+), which the model's shape does not declare"
  (fn [ctx k]
    (edited ctx (fn [m] (update m :containers update-where #(= :repository (:id %)) #(assoc % (keyword (subs k 1)) "Sami")))
            [":repository" k])))

(defstep #"a container and a component both with the id (\S+)"
  (fn [ctx k]
    (let [id (keyword (subs k 1))]
      (edited ctx (fn [m]
                    (-> m
                        (update :containers conj {:id id :name "Alpha store" :kind :stores :technology "files"
                                                  :description "Holds alpha"})
                        (update :containers update-where #(= :rdm-spec (:id %))
                                (fn [c] (update c :contexts update-where #(= :knowledge (:id %))
                                                (fn [x] (update x :components conj
                                                                {:id id :name "Alpha" :spec "knowledge/sources"
                                                                 :description "Alpha"})))))))
              [k "Alpha store" "\"Alpha\""]))))

(defstep #"a relationship from (\S+) to (\S+), and no element (\S+)"
  (fn [ctx from to _]
    (edited ctx (fn [m] (update m :relationships conj {:from (keyword (subs from 1)) :to (keyword (subs to 1))
                                                       :description "Archives to"}))
            [from to])))

(defstep #"it exits 1 and names (?:the container and the key|the id and both elements|the relationship and (\S+))"
  (fn [ctx _]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some (fn [l] (every? #(str/includes? l %) (:named ctx))) (kc/finding-lines r))
                (str "no finding names " (str/join ", " (:named ctx)) "\n" (s/show r)))
      nil)))

(defstep #"the component (\S+) with the spec path \"([^\"]+)\""
  (fn [ctx id spec]
    (let [dir (a/room-copy ctx)
          m (a/read-model dir)
          c (first (for [x (:containers m) y (:contexts x) c (:components y) :when (= (keyword (subs id 1)) (:id c))] c))]
      (s/check! (= spec (:spec c)) (str id "'s spec path is " (pr-str (:spec c))))
      (assoc ctx :dir dir :component id :spec-feature (str "dhf/spec/" spec ".feature")))))

(defstep #"the component owns the feature file (\S+), and \"rdm-spec check\" passes only when that file exists"
  (fn [ctx file]
    (s/check! (= file (:spec-feature ctx)) (str "the component's feature file is " (:spec-feature ctx)))
    (kc/exits! ctx 0)
    (fs/delete (fs/file (:dir ctx) file))
    (let [r (kc/run! ctx (:dir ctx) ["check"])]
      (s/check! (= 1 (:exit r)) (str "the check passes without " file "\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": a-scenario-owner: ") (str/includes? % (:component ctx))
                            (str/includes? % file))
                      (kc/finding-lines r))
                (str "no finding says " (:component ctx) " owns no " file "\n" (s/show r)))
      (assoc ctx :run r))))

;; ---------------------------------------------------------------------------
;; Views go stale when the model's content changes, never its formatting

(defstep #"views generated from the model"
  (fn [ctx]
    (let [dir (a/room-copy ctx)]
      (a/views! ctx dir)
      (assoc ctx :dir dir))))

(defstep #"the model's comments and whitespace are changed and nothing else"
  (fn [ctx]
    (let [f (fs/file (:dir ctx) (a/model-rel (:dir ctx)))
          text (slurp (str f))
          changed (str ";; A comment added above everything.\n\n"
                       (-> text
                           (str/replace #"(?m)^(\s*);;.*$" "$1;; a comment, reworded")
                           (str/replace #"(?m)^ " "    ")
                           (str/replace " :name " "   :name  ")))]
      (s/check! (= (a/read-model (:dir ctx)) (do (spit (str f) changed) (a/read-model (:dir ctx))))
                "the change altered the model's content")
      ctx)))

(defstep #"it reports no stale view"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (not (some #(str/includes? % "view-stale") (s/err-lines r)))
                (str "a view is reported stale\n" (s/show r)))
      nil)))

(defstep #"one element's description is changed"
  (fn [ctx]
    (a/update-model! (:dir ctx) (fn [m] (update m :people (fn [ps] (update-in ps [0 :description] str ", now changed")))))
    ctx))

(defstep #"it fails and names every view as stale"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          stale (filter #(str/includes? % ": view-stale: ") (kc/finding-lines r))]
      (doseq [k (map :key (:views (a/read-model (:dir ctx))))]
        (s/check! (some #(str/includes? % (str "\"" k "\"")) stale)
                  (str "the view " k " is not named stale\n" (s/show r))))
      nil)))
