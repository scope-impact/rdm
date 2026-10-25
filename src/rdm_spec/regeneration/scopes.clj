(ns rdm-spec.regeneration.scopes
  "Scopes (dhf/spec/regeneration/scopes.feature): `rdm-spec scopes CUT CUT`
  lists what a spec change touched between two cuts, so a cycle's scopes come
  from the spec and are never listed by hand.

  In order, each refusal a cannot-run (exit 2):
    1. the project must be a git repository, and each CUT must name a commit
       carrying a spec-N tag (a tag name, or any name of such a commit);
       regeneration reads only cuts.
    2. the two cuts are put in order by their numbers, whichever is given
       first: :from the earlier, :to the later.
    3. the configuration and the model are the later cut's
       (dhf/harness/config.edn as that cut holds it, then its :model), never
       the working tree's; a cut holding either unreadably or not at all
       cannot be read.
    4. the files that differ between the two commits (added, changed or
       deleted; renames counted as both paths) are split:
       - a component's scope, {:component <model id>}, for each component of
         the later cut's model (kernel included) whose <spec-dir>/<spec>.feature
         or design prose <spec-dir>/<spec>.md changed;
       - a shared file, for every other changed file in the clean room
         (:clean-room :allow): no component owns it, so which components read
         it is for a person to judge. Each is named on the error stream as a
         warning, `FILE: warning: shared-file-changed: ...`;
       - nothing, for a file outside the clean room, which no regeneration reads.

  Standard output: {:from \"spec-3\" :to \"spec-4\" :scopes [{:component ..} ..]
  :shared [\"dhf/spec/events.edn\" ..]}, scopes sorted by id, shared by path.
  Listing is not checking: a shared file warns and the task exits 0."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; The model's owners

(defn- trim-dir [d] (str/replace (str d) #"/+$" ""))

(defn components
  "Every component of the model, the kernel's included."
  [model]
  (concat (for [c (:containers model) ctx (:contexts c) comp (:components ctx)] comp)
          (:kernel model)))

(defn owners
  "{path component-id}: the feature file and the design prose of every
  component of `model`, under the spec directory `spec-dir`."
  [model spec-dir]
  (let [d (trim-dir spec-dir)]
    (into {}
          (for [{:keys [id spec]} (components model)
                :when (and id (string? spec) (not (str/blank? spec)))
                ext [".feature" ".md"]]
            [(str d "/" spec ext) id]))))

(defn- under? [rel p]
  (let [p (trim-dir p)]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn classify
  "{:scopes [{:component id}] :shared [path]} of the `changed` paths: a
  path `owners` names gives its component's scope; any other path in the
  clean room (`allow`) is shared; the rest is neither."
  [owners allow changed]
  (let [changed (sort (distinct changed))
        ids (distinct (keep owners changed))]
    {:scopes (vec (for [id (sort-by str ids)] {:component id}))
     :shared (vec (for [f changed
                        :when (not (contains? owners f))
                        :when (some #(under? f %) allow)]
                    f))}))

;; ---------------------------------------------------------------------------
;; The cuts

(defn resolve-cut
  "{:commit :tag :number} of the cut `arg` names in the repository `dir`;
  cannot run unless it names a commit carrying a spec-N tag."
  [dir arg]
  (let [commit (or (cut/rev dir arg)
                   (command/cannot-run! (str "scopes reads only cuts, commits tagged spec-N: " arg
                                             " names no commit in this repository")))
        tags (cut/cuts-at dir commit)]
    (when (empty? tags)
      (command/cannot-run! (str "scopes reads only cuts, commits tagged spec-N: " arg
                                " (" (cut/short-hash commit) ") carries no spec-N tag")))
    (let [tag (if (some #{arg} tags) arg (last tags))]
      {:commit commit :tag tag :number (cut/cut-number tag)})))

(defn- edn-at
  "`path` at the cut, read as EDN; cannot run when it is missing or unreadable."
  [dir {:keys [commit tag]} path what]
  (let [r (cut/git dir "cat-file" "-p" (str commit ":" path))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the cut " tag " holds no " path ": " what " is read from the later cut")))
    (try (edn/read-string (:out r))
         (catch Exception e
           (command/cannot-run! (str "the cut " tag "'s " path " is not readable EDN: " (ex-message e)))))))

(defn changed-files
  "Every path that differs between the commits `a` and `b`, renames counted
  as their old and new paths."
  [dir a b]
  (let [r (cut/git dir "diff" "--name-only" "--no-renames" "-z" a b "--")]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git diff " a " " b " failed: " (str/trim (:err r)))))
    (vec (remove str/blank? (str/split (:out r) #"\u0000")))))

;; ---------------------------------------------------------------------------
;; The task

(defn scopes
  "The scopes task: `rdm-spec scopes CUT CUT`."
  [req]
  (let [dir (:root req)
        _ (cut/repository! dir)
        [a b] (:args req)
        [from to] (sort-by :number [(resolve-cut dir a) (resolve-cut dir b)])
        cfg (edn-at dir to command/config-file "the configuration")
        _ (when-not (map? cfg) (command/cannot-run! (str "the cut " (:tag to) "'s " command/config-file " is not a map")))
        spec-dir (or (:spec-dir cfg) (command/cannot-run! (str "the cut " (:tag to) "'s configuration sets no :spec-dir")))
        model (edn-at dir to (or (:model cfg) (command/cannot-run! (str "the cut " (:tag to) "'s configuration sets no :model")))
                      "the model")
        _ (when-not (map? model) (command/cannot-run! (str "the cut " (:tag to) "'s model is not a map")))
        allow (or (not-empty (vec (get-in cfg [:clean-room :allow]))) [spec-dir])
        {:keys [scopes shared]} (classify (owners model spec-dir) allow
                                          (changed-files dir (:commit from) (:commit to)))]
    {:result (array-map :from (:tag from) :to (:tag to) :scopes scopes :shared shared)
     :findings (vec (for [f shared]
                      (command/warning f nil "shared-file-changed"
                                       (str "changed between " (:tag from) " and " (:tag to)
                                            " and owned by no component: a person must judge which"
                                            " components read it, and so which scopes it adds"))))}))
