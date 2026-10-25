(ns rdm-spec.measurement.checklist
  "Component Checklist of the bounded context measurement
  (dhf/spec/measurement/checklist.feature): `rdm-spec checklist CYCLE`.

  Derives the cycle's checklist from the spec at the cycle's cut, never by
  hand: one item to grade per stamped scenario not tagged @wip, and the @wip
  scenarios listed apart as planned. It writes dhf/cycles/CYCLE/checklist.json:

    {\"cycle\": \"03\", \"cut\": \"spec-3\",
     \"items\":   [{\"id\" \"title\" \"context\" \"component\" \"level\"} ...],
     \"planned\": [{\"id\" \"title\"} ...]}

  keys in that order, items and planned sorted by id; context and component
  are the names the model gives the scenario's owner (\"kernel\" for the
  kernel). The checklist covers the whole spec at the cut, whatever the
  cycle's scope: a partial regeneration can break what it did not rebuild,
  and the scoreboard reports the scope's items apart from the rest.

  The spec arrives as the parser's SpecParsed reading at the cut (the task's
  first stage, which leaves it in the run's state). A cut holding an
  unstamped scenario, a scenario with several ids, or Gherkin the reader
  refuses has no checklist: exit 1, nothing written, each named by file and
  line. Cannot run (exit 2): no cycle.edn, one naming no cut, a cut the
  repository does not hold, or a scope that is malformed or names what the
  model at the cut does not.

  Also the cycle helpers the scorer and the scoreboard share: reading a
  cycle's files, its scope, and which checklist items a scope targets."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; A cycle's files

(defn cycle-dir
  "The directory of cycle `cycle` under the configured :cycles-dir; a name
  that is not a plain directory name cannot run."
  [req cycle]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (not (str/includes? cycle "..")))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle name (a directory name such as 03)")))
  (command/path req (str (command/config-value req :cycles-dir) "/" cycle)))

(defn cycle-file [req cycle name] (fs/file (cycle-dir req cycle) name))

(defn read-edn-file
  "The EDN value in `f`, or nil when absent; cannot run when unreadable."
  [req f]
  (when (fs/exists? f)
    (try (edn/read-string (slurp (str f)))
         (catch Exception e
           (command/cannot-run! (str (command/rel req f) " is not readable EDN: " (ex-message e)))))))

(defn read-cycle
  "Cycle `cycle`'s cycle.edn (a map naming its :cut); cannot run when it is
  absent, not a map or names no cut."
  [req cycle]
  (let [f (cycle-file req cycle "cycle.edn")
        n (command/rel req f)]
    (when-not (fs/exists? f)
      (command/cannot-run! (str "no " n ": the cycle " cycle " is not declared (write its cycle.edn, naming its cut)")))
    (let [c (read-edn-file req f)]
      (when-not (map? c) (command/cannot-run! (str n " is not a map")))
      (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str n " names no :cut")))
      c)))

(defn read-checklist-json
  "Cycle `cycle`'s checklist.json, keywordized, or nil when absent; cannot
  run when it is not a JSON object."
  [req cycle]
  (let [f (cycle-file req cycle "checklist.json")]
    (when (fs/exists? f)
      (let [c (try (json/parse-string (slurp (str f)) true)
                   (catch Exception e
                     (command/cannot-run! (str (command/rel req f) " is not JSON: " (ex-message e)))))]
        (when-not (map? c) (command/cannot-run! (str (command/rel req f) " is not a JSON object")))
        c))))

;; ---------------------------------------------------------------------------
;; git

(defn- git [root & args]
  (let [r (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn commit-of
  "The commit `rev` names in the repository at `root`, or nil."
  [root rev]
  (let [r (git root "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn- show [root rev rel]
  (let [r (git root "show" (str rev ":./" rel))]
    (when (zero? (:exit r)) (:out r))))

(defn model-at
  "The C4 model at the cut `cut` (nil: the working tree's): the configured
  :model read from git, else from the working tree; nil when neither holds a
  readable map."
  [req cut]
  (let [rel (or (get-in req [:config :model]) "dhf/spec/model.edn")
        read-text (fn [t] (when t (try (let [m (edn/read-string t)] (when (map? m) m)) (catch Exception _ nil))))
        f (command/path req rel)]
    (or (when cut (read-text (show (:root req) cut rel)))
        (when (fs/regular-file? f) (read-text (slurp (str f)))))))

;; ---------------------------------------------------------------------------
;; Scopes

(defn scope-units
  "The scope as a list of units, [[:context kw] or [:component kw] ...]; []
  for the project. nil when the scope is malformed. A scope is :project,
  {:context kw}, {:component context/component} or a non-empty vector of the
  latter two."
  [scope]
  (letfn [(unit [s]
            (when (and (map? s) (= 1 (count s)))
              (let [[k v] (first s)]
                (cond (and (= :context k) (or (keyword? v) (and (string? v) (not (str/blank? v)))))
                      [:context (keyword (name v))]
                      (and (= :component k) (keyword? v) (namespace v))
                      [:component v]
                      (and (= :component k) (string? v) (re-matches #"[^/\s]+/[^/\s]+" v))
                      [:component (keyword v)]))))]
    (cond (= :project scope) []
          (map? scope) (when-let [u (unit scope)] [u])
          (and (sequential? scope) (seq scope)) (let [us (map unit scope)] (when (every? some? us) (vec us)))
          :else nil)))

(defn model-owners
  "What the model names: {:contexts {kw name} :components {kw [context-name
  component-name]}}, the kernel a context named kernel."
  [model]
  {:contexts (into (if (seq (:kernel model)) {:kernel "kernel"} {})
                   (for [c (:containers model) ctx (:contexts c)]
                     [(:id ctx) (or (:name ctx) (name (:id ctx)))]))
   :components (into {}
                     (concat
                      (for [c (:containers model) ctx (:contexts c) comp (:components ctx)]
                        [(:id comp) [(or (:name ctx) (name (:id ctx))) (:name comp)]])
                      (for [comp (:kernel model)]
                        [(:id comp) ["kernel" (:name comp)]])))})

(defn scope-refusal
  "Why `scope` cannot be used with `model`, or nil when it can."
  [model scope]
  (let [units (scope-units scope)
        {:keys [contexts components]} (model-owners model)]
    (cond
      (nil? units)
      (str "the scope " (pr-str scope) " is not a scope: :project, {:context :c}, {:component :c/n},"
           " or a vector of contexts and components")

      (empty? units) nil

      (nil? model)
      (str "the scope " (pr-str scope) " names contexts or components, and the cut holds no readable model")

      :else
      (let [unknown (remove (fn [[k v]] (contains? (if (= :context k) contexts components) v)) units)]
        (when (seq unknown)
          (str "the model names no " (str/join ", " (map (fn [[k v]] (str (name k) " " (subs (str v) 1))) unknown))
               "; its bounded contexts are " (str/join ", " (map #(subs (str %) 1) (keys contexts)))))))))

(defn target?
  "A predicate on checklist items ({:context name :component name}): true
  for the items `scope` rebuilt. Every item for the project."
  [model scope]
  (let [units (or (scope-units scope) [])
        {:keys [contexts components]} (model-owners model)]
    (if (empty? units)
      (constantly true)
      (let [ctx-names (set (keep (fn [[k v]] (when (= :context k) (get contexts v (name v)))) units))
            comp-names (set (keep (fn [[k v]] (when (= :component k) (get components v))) units))]
        (fn [{:keys [context component]}]
          (boolean (or (contains? ctx-names context)
                       (contains? comp-names [context component]))))))))

;; ---------------------------------------------------------------------------
;; The checklist

(defn item
  "The checklist item of a parsed scenario."
  [sc]
  (array-map :id (:id sc)
             :title (:title sc)
             :context (or (:context-name sc) (some-> (:context sc) name))
             :component (or (:component-name sc) (some-> (:component sc) name))
             :level (some-> (:level sc) name)))

(defn derive-checklist
  "{:items [..] :planned [..]} from the scenarios of a spec, sorted by id."
  [scenarios]
  (let [stamped (sort-by :id (filter :id scenarios))]
    {:items (mapv item (remove :wip? stamped))
     :planned (mapv #(array-map :id (:id %) :title (:title %)) (filter :wip? stamped))}))

(defn spec-findings
  "What stops the checklist: Gherkin the reader refused, and every scenario
  without exactly one id, each named by file and line."
  [spec]
  (vec (concat
        (for [{:keys [file line rule message]} (:refusals spec)]
          (command/finding file line (or rule "gherkin") (str "the spec at the cut cannot be read: " message)))
        (for [{:keys [file line title ids]} (:scenarios spec)
              :when (not= 1 (count ids))]
          (if (empty? ids)
            (command/finding file line "unstamped-scenario"
                             (str "the scenario \"" title "\" has no id: a cut holding an unstamped scenario"
                                  " has no checklist (stamp it and cut again)"))
            (command/finding file line "scenario-ids"
                             (str "the scenario \"" title "\" has " (count ids) " ids: " (str/join ", " ids))))))))

(defn checklist-json
  "The text of checklist.json."
  [cycle cut {:keys [items planned]}]
  (str (json/generate-string (array-map :cycle cycle :cut cut :items items :planned planned) {:pretty true})
       "\n"))

(defn checklist
  "The checklist task: `rdm-spec checklist CYCLE`, run after the parser's
  stage has read the spec at the cycle's cut."
  [req]
  (let [cycle (first (:args req))
        c (read-cycle req cycle)
        cut (:cut c)
        cycle-edn (command/rel req (cycle-file req cycle "cycle.edn"))
        _ (when-not (commit-of (:root req) cut)
            (command/cannot-run! (str "the cut " cut " named by " cycle-edn " is not a tag or commit of this repository")))
        scope (get c :scope :project)
        _ (when-let [why (scope-refusal (model-at req cut) scope)]
            (command/cannot-run! (str cycle-edn ": " why)))
        spec (some-> (:state req) deref :rdm-spec.spec.parser/spec)
        _ (when-not spec
            (command/cannot-run! (str "the spec at the cut " cut " was not read")))
        findings (spec-findings spec)
        f (cycle-file req cycle "checklist.json")]
    (if (seq findings)
      {:result {:cycle cycle :cut cut :written false} :findings findings}
      (let [cl (derive-checklist (:scenarios spec))]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (checklist-json cycle cut cl))
        {:result {:cycle cycle :cut cut :scope scope
                  :items (count (:items cl)) :planned (count (:planned cl))
                  :checklist (command/rel req f)}
         :findings []}))))
