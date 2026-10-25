(ns rdm-spec.regeneration.scopes
  "Scopes (dhf/spec/regeneration/scopes.feature): `rdm-spec scopes CUT CUT`
  lists what a spec change touched between two cuts, so a cycle's scopes come
  from the spec, never from memory.

  In order, each refusal a cannot-run (exit 2):
    1. the project must be a git repository, and each CUT must name a commit
       carrying a spec-N tag: scopes compare cuts, nothing else.
    2. the configuration read is the later cut's (dhf/harness/config.edn as
       that cut holds it), else the working tree's; one that sets no
       :spec-dir or :model cannot run. The later cut is the one with the
       higher cut number; the order of the arguments does not matter.
    3. the model is read at both cuts. A model neither cut holds readably is
       a finding (exit 1): the model is part of the spec.

  Every file that differs between the two cuts (added, changed or deleted;
  a rename counts as both paths) is then sorted:
    scope    <spec-dir>/<context>/<component>.feature or .md, where a
             component's :spec is <context>/<component> in either cut's
             model (the later cut's first): {:component <its model id>},
             the kernel's as {:component :kernel/<name>}. A component's
             feature file and its design prose are its own.
    shared   a file on the clean room's allow-list (:clean-room :allow) no
             component owns: named as a warning, for a person to judge whose
             reads it, and listed under :shared. Never a finding: exit 0.
    ignored  anything else (the implementation, cycles): not a spec change.

  The result: {:from :to :scopes [{:component ..} ..] :shared [path ..]},
  scopes sorted by component, paths sorted; the scopes are written as a
  cycle.edn's :scope lists them."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; Owners

(defn owners
  "{\"<context>/<component>\" component-id} of the model, kernel included."
  [model]
  (into {}
        (for [c (concat (for [k (:containers model) ctx (:contexts k) c (:components ctx)] c)
                        (:kernel model))
              :when (and (string? (:spec c)) (keyword? (:id c)))]
          [(:spec c) (:id c)])))

(defn owner-of
  "The id of the component that owns the project path `rel`: its feature
  file or its design prose under `spec-dir`; nil for any other path."
  [spec-dir owners rel]
  (let [prefix (str (str/replace (str spec-dir) #"/+$" "") "/")]
    (when (str/starts-with? rel prefix)
      (when-let [[_ spec] (re-matches #"([^/]+/[^/]+)\.(?:feature|md)" (subs rel (count prefix)))]
        (get owners spec)))))

(defn- under? [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn classify
  "Sorts the changed project paths `changed` by `config` and `models` (one
  model, or several, the first winning): {:scopes :shared :ignored}."
  [config models changed]
  (let [models (if (map? models) [models] models)
        own (apply merge (map owners (reverse models)))
        spec-dir (:spec-dir config)
        allow (get-in config [:clean-room :allow])
        sorted (group-by (fn [rel]
                           (cond (owner-of spec-dir own rel) :scopes
                                 (some #(under? rel %) allow) :shared
                                 :else :ignored))
                         (distinct changed))]
    {:scopes (->> (:scopes sorted)
                  (map #(owner-of spec-dir own %))
                  distinct
                  (sort-by #(str (namespace %) "/" (name %)))
                  (mapv #(hash-map :component %)))
     :shared (vec (sort (:shared sorted)))
     :ignored (vec (sort (:ignored sorted)))}))

(defn shared-warning
  "The warning naming the shared file `rel` changed between the cuts."
  [from to rel]
  (command/warning rel nil "shared-file"
                   (str "changed between " from " and " to ", and no component owns it:"
                        " a person must judge which components read it")))

;; ---------------------------------------------------------------------------
;; The cuts

(defn- resolve-cut!
  "{:arg :commit :number} of the cut `arg` names in `dir`."
  [dir arg]
  (let [commit (or (cut/rev dir arg)
                   (command/cannot-run! (str "scopes compare cuts, commits tagged spec-N: " arg
                                             " names no commit in this repository")))
        tags (cut/cuts-at dir commit)]
    (when (empty? tags)
      (command/cannot-run! (str "scopes compare cuts, commits tagged spec-N: " arg
                                " (" (cut/short-hash commit) ") carries no spec-N tag")))
    {:arg arg :commit commit
     :number (or (cut/cut-number arg) (cut/cut-number (last tags)))}))

(defn- file-at [dir commit path]
  (let [r (cut/git dir "cat-file" "-p" (str commit ":" path))]
    (when (zero? (:exit r)) (:out r))))

(defn- edn-at
  "`path` at `commit` read as EDN: {:value v} or {:problem why}."
  [dir commit path]
  (if-let [text (file-at dir commit path)]
    (try {:value (edn/read-string text)}
         (catch Exception e {:problem (str "is not readable EDN: " (ex-message e))}))
    {:problem "is missing"}))

(defn- changed-paths
  "Every path that differs between the two commits, renames as both paths."
  [dir a b]
  (let [r (cut/git dir "diff" "--name-only" "--no-renames" "-z" a b)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git diff " a " " b " failed: " (str/trim (:err r)))))
    (vec (remove str/blank? (str/split (:out r) #"\u0000")))))

(defn- config-of
  "The later cut's configuration, else the working tree's."
  [dir later req]
  (let [{:keys [value problem]} (edn-at dir (:commit later) command/config-file)]
    (cond
      (map? value) value
      (and problem (not= problem "is missing"))
      (command/cannot-run! (str "the configuration " command/config-file " at " (:arg later) " " problem))
      (:config req) (:config req)
      :else (command/cannot-run! (str "no configuration " command/config-file " at " (:arg later)
                                      " or in the project")))))

;; ---------------------------------------------------------------------------
;; The task

(defn scopes
  "The scopes task: `rdm-spec scopes CUT CUT`."
  [req]
  (let [dir (:root req)
        _ (cut/repository! dir)
        [a b] (map #(resolve-cut! dir %) (:args req))
        [from to] (if (> (:number a) (:number b)) [b a] [a b])
        config (config-of dir to req)
        model-file (or (:model config) (command/cannot-run! (str "the configuration sets no :model")))
        _ (or (:spec-dir config) (command/cannot-run! (str "the configuration sets no :spec-dir")))
        read (for [c [to from]] (assoc (edn-at dir (:commit c) model-file) :cut c))
        models (vec (keep #(when (map? (:value %)) (:value %)) read))
        model-findings (when (empty? models)
                         (vec (for [{:keys [problem value cut]} read]
                                (command/finding model-file nil "model-unreadable"
                                                 (str "the model at " (:arg cut) " "
                                                      (or problem (str "is not a map: " (pr-str (type value))))
                                                      ": no component can be named a scope")))))
        {:keys [scopes shared]} (classify config models (changed-paths dir (:commit from) (:commit to)))]
    {:result (array-map :from (:arg from) :to (:arg to) :scopes scopes :shared shared)
     :findings (into (vec model-findings)
                     (map #(shared-warning (:arg from) (:arg to) %) shared))}))
