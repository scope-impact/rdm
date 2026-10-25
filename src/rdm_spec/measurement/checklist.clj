(ns rdm-spec.measurement.checklist
  "Component Checklist of the bounded context measurement
  (dhf/spec/measurement/checklist.feature): `rdm-spec checklist CYCLE` derives
  one item per stamped scenario at the cycle's cut into
  dhf/cycles/CYCLE/checklist.json, never written by hand, for a grader to grade.

  The checklist reads the spec at the cut (the git tag cycle.edn names), never
  the working tree: the configuration, the feature files and the model are all
  read from the cut. An unstamped scenario at the cut stops it (exit 1, nothing
  written) rather than shrinking the denominator.

  Also holds what the measurement context's other components share about a
  cycle: where its directory is and how its cycle.edn reads (the scorer and the
  scoreboard build on the checklist, as the model draws them)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :as runner]))

;; ---------------------------------------------------------------------------
;; A cycle: dhf/cycles/NN/

(defn cycle-dir
  "The directory of cycle `cycle` (a name such as \"03\") under the configured
  :cycles-dir. A name that is not a plain directory name cannot run."
  [req cycle]
  (when-not (and (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (not (str/includes? cycle "..")))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle name (a directory name such as 03)")))
  (fs/file (command/path req (command/config-value req :cycles-dir)) cycle))

(defn cycle-file
  "The file `name` of cycle `cycle`, e.g. (cycle-file req \"03\" \"score.edn\")."
  [req cycle name]
  (fs/file (cycle-dir req cycle) name))

(defn read-edn-file
  "The EDN in file `f`, or cannot-run naming it when it is absent or not EDN."
  [req f]
  (when-not (fs/exists? f)
    (command/cannot-run! (str "no " (command/rel req f))))
  (try (edn/read-string (slurp (str f)))
       (catch Exception e
         (command/cannot-run! (str (command/rel req f) " is not EDN: " (ex-message e))))))

(defn read-cycle
  "Cycle `cycle`'s cycle.edn, written by hand: {:cut :scope :builder :grader
  :spec-owner [:bootstrap]}. Absent or unreadable: cannot run, naming it."
  [req cycle]
  (let [f (cycle-file req cycle "cycle.edn")
        c (read-edn-file req f)]
    (when-not (map? c)
      (command/cannot-run! (str (command/rel req f) " is not a map")))
    c))

(defn scope-finding
  "A finding when `scope` is none of :project, {:context k} or {:component
  :context/component}, or names a context or component `model` does not hold;
  else nil. A cycle.edn with no :scope is the whole project."
  [file scope model]
  (let [contexts (for [c (:containers model) ctx (:contexts c)] ctx)
        context-ids (set (map :id contexts))
        component-ids (set (for [ctx contexts comp (:components ctx)] (:id comp)))
        bad (fn [msg] (command/finding file nil "cycle-scope" msg))]
    (cond
      (or (nil? scope) (= :project scope)) nil
      (and (map? scope) (= [:context] (keys scope)) (keyword? (:context scope)))
      (when (and model (not (context-ids (:context scope))))
        (bad (str "the scope names the bounded context " (:context scope) ", which the model does not hold")))
      (and (map? scope) (= [:component] (keys scope)) (qualified-keyword? (:component scope)))
      (when (and model (not (component-ids (:component scope))))
        (bad (str "the scope names the component " (:component scope) ", which the model does not hold")))
      :else
      (bad (str "the scope " (pr-str scope)
                " is none of :project, {:context <context>} or {:component <context>/<component>}")))))

;; ---------------------------------------------------------------------------
;; The spec at a cut, read from git

(defn- git [req & args]
  (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true} "git" args))

(defn cut-exists? [req cut]
  (zero? (:exit (git req "rev-parse" "--verify" "--quiet" (str "refs/tags/" cut)))))

(defn show-at
  "The text of `path` at `cut`, or nil when the cut holds no such file."
  [req cut path]
  (let [r (git req "show" (str cut ":" path))]
    (when (zero? (:exit r)) (:out r))))

(defn files-at
  "Every file under `dir` at `cut`, as paths relative to the root."
  [req cut dir]
  (let [r (git req "ls-tree" "-r" "--name-only" cut "--" dir)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "cannot list " dir " at " cut ": " (str/trim (:err r)))))
    (vec (sort (remove str/blank? (str/split-lines (:out r)))))))

(defn config-at
  "The harness configuration at the cut, or the working one when the cut holds
  none."
  [req cut]
  (if-let [t (show-at req cut command/config-file)]
    (try (edn/read-string t)
         (catch Exception e
           (command/cannot-run! (str command/config-file " at " cut " is not EDN: " (ex-message e)))))
    (:config req)))

(defn model-at
  "The C4 model at the cut (its configured :model), or nil."
  [req cut]
  (let [cfg (config-at req cut)]
    (when-let [t (some->> (:model cfg) (show-at req cut))]
      (try (edn/read-string t) (catch Exception _ nil)))))

(defn require-cut!
  "Cannot run unless the cut (a tag) exists in the repository."
  [req cut]
  (when-not (and (string? cut) (not (str/blank? cut)))
    (command/cannot-run! "cycle.edn names no :cut"))
  (when-not (cut-exists? req cut)
    (command/cannot-run! (str "the cut " cut " does not exist: no tag " cut " in " (:root req)))))

(defn scenarios-at
  "Every scenario of the spec at the cut:
  {:scenarios [{:id :ids :title :context :component :level :wip? :file :line}]
   :refusals [...] :model m}."
  [req cut]
  (let [cfg (config-at req cut)
        spec-dir (or (:spec-dir cfg) "dhf/spec")
        prefix (or (:id-prefix cfg) "spec")
        id-re (re-pattern (str "@" (java.util.regex.Pattern/quote prefix) "-\\d+"))
        model (model-at req cut)
        files (filter #(str/ends-with? % ".feature") (files-at req cut spec-dir))
        parsed (for [f files] (gherkin/parse-feature f (show-at req cut f)))]
    {:model model
     :refusals (vec (mapcat :refusals parsed))
     :scenarios
     (vec (for [{:keys [file scenarios]} parsed
                :let [spec-path (-> file (subs (inc (count (str/replace spec-dir #"/+$" ""))))
                                    (str/replace #"\.feature$" ""))
                      owner (runner/owner-of model spec-path)]
                sc scenarios
                :let [tags (:all-tags sc)
                      ids (vec (distinct (map #(subs % 1) (filter #(re-matches id-re %) (:tags sc)))))
                      level (some #(when (#{"@e2e" "@contract"} %) (subs % 1)) tags)]]
            {:id (first ids) :ids ids :title (:name sc)
             :context (:context owner) :component (:component owner)
             :level level :wip? (boolean (some #{"@wip"} tags))
             :file file :line (:line sc)}))}))

;; ---------------------------------------------------------------------------
;; checklist.json

(defn checklist-json
  "checklist.json's text: keys in the order cycle, cut, items, planned; items
  and planned sorted by id."
  [cycle cut scenarios]
  (let [by-id (partial sort-by :id)
        items (for [s (by-id (remove :wip? scenarios))]
                (array-map "id" (:id s) "title" (:title s) "context" (:context s)
                           "component" (:component s) "level" (:level s)))
        planned (for [s (by-id (filter :wip? scenarios))]
                  (array-map "id" (:id s) "title" (:title s)))]
    (str (json/generate-string (array-map "cycle" cycle "cut" cut "items" items "planned" planned)
                               {:pretty true})
         "\n")))

(defn read-checklist
  "Cycle `cycle`'s checklist.json, keywordized, or cannot-run naming it."
  [req cycle]
  (let [f (cycle-file req cycle "checklist.json")]
    (when-not (fs/exists? f)
      (command/cannot-run! (str "no " (command/rel req f) "; derive it first: rdm-spec checklist " cycle)))
    (try (json/parse-string (slurp (str f)) true)
         (catch Exception e
           (command/cannot-run! (str (command/rel req f) " is not JSON: " (ex-message e)))))))

(defn checklist
  "The checklist task: `rdm-spec checklist CYCLE`."
  [req]
  (let [[cycle] (:args req)
        c (read-cycle req cycle)
        cut (:cut c)
        cycle-rel (command/rel req (cycle-file req cycle "cycle.edn"))
        _ (require-cut! req cut)
        {:keys [scenarios refusals model]} (scenarios-at req cut)
        scope-bad (scope-finding cycle-rel (:scope c) model)
        unstamped (for [s scenarios :when (empty? (:ids s))]
                    (command/finding (:file s) (:line s) "checklist-unstamped"
                                     (str "the scenario \"" (:title s) "\" at " cut
                                          " carries no id; stamp it, an unstamped scenario stops the checklist")))
        doubled (for [s scenarios :when (> (count (:ids s)) 1)]
                  (command/finding (:file s) (:line s) "checklist-ids"
                                   (str "the scenario \"" (:title s) "\" carries " (count (:ids s))
                                        " ids, " (str/join ", " (:ids s)) "; a scenario carries exactly one")))
        dupes (for [[id ss] (group-by :id (filter :id scenarios)) :when (> (count ss) 1)
                    s (rest ss)]
                (command/finding (:file s) (:line s) "checklist-ids"
                                 (str id " is carried by more than one scenario")))
        unreadable (for [r refusals] (command/finding (:file r) (:line r) (:rule r) (:message r)))
        findings (vec (concat (when scope-bad [scope-bad]) unstamped doubled dupes unreadable))]
    (if (seq findings)
      {:result {:cycle cycle :cut cut :written nil} :findings findings}
      (let [f (cycle-file req cycle "checklist.json")
            items (remove :wip? scenarios)]
        (spit (str f) (checklist-json cycle cut scenarios))
        {:result {:cycle cycle :cut cut :scope (or (:scope c) :project)
                  :items (count items) :planned (count (filter :wip? scenarios))
                  :written (command/rel req f)}
         :findings []}))))
