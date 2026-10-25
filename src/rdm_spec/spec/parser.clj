(ns rdm-spec.spec.parser
  "The Parser component of the spec context (dhf/spec/spec/parser.feature):
  reads the spec, in the working tree or as it was at a revision, into data,
  and refuses every construct outside the declared Gherkin subset. Gherkin is
  read by the kernel's reader (rdm-spec.kernel.gherkin), so the runner and the
  spec context never read it two ways; this component adds what the spec
  context knows: ids, levels, needs, planned work and each scenario's owner in
  the C4 model. It publishes SpecParsed.

  `read-spec` returns

    {:revision   \"<commit>\" | :working-tree
     :spec-dir   \"dhf/spec\"
     :files      [path]                  ; every file of the spec, sorted
     :texts      {path text}             ; their texts (text-files reads them)
     :features   [{:file :line :name :tags :description}]
     :scenarios  [scenario]              ; path order, then file order
     :needs      [{:id :text :sources :line}]   ; in the needs file's order
     :needs-file path  :needs-error message-or-nil
     :refusals   [{:file :line :rule :message}]}

  where a scenario is

    {:id \"spec-0007\" or nil (nil unless it carries exactly one id)
     :ids [\"spec-0007\"] :title :name :file :line
     :keyword \"Scenario\" | \"Scenario Outline\" :outline? bool
     :tags [own tags] :all-tags [feature, rule, own]
     :levels [\"@e2e\"] :level :e2e | :contract | nil (nil unless exactly one)
     :needs [\"UN-001\"]                    ; from it, its rule and its feature
     :wip? bool                            ; @wip on it, its rule or its feature
     :steps [{:keyword :text :line [:doc-string] [:data-table]}] :background [step]
     :examples [{:line :tags :header :rows [{:line :cells}]}] or nil
     :rows [{:line :values {header value}}] or nil   ; an outline's example rows
     :feature name :rule name-or-nil :rule-line :rule-tags
     :context :spec :component :spec/parser      ; the model's ids, or nil
     :context-name \"spec\" :component-name \"Parser\"}

  Tags keep their @; ids and needs do not. The context of a kernel component
  is :kernel, named \"kernel\".

  The stage `parse` (first stage of checklist, status, trace and release)
  reads the spec at the cut the task names (checklist: the cycle's cut, from
  its cycle.edn) or else in the working tree, leaves it in the run's state
  under ::spec and publishes SpecParsed. It reports no finding itself: what a
  refusal means is the reading task's to say."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; Tags

(defn id-pattern
  "Matches a whole id tag, @<prefix>-NNNN (four digits or more)."
  [prefix]
  (re-pattern (str "@" (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}")))

(def need-pattern
  "Matches a whole need tag, @UN-nnn."
  #"@UN-\d+")

(def level-tags #{"@e2e" "@contract"})

(def wip-tag "@wip")

(defn- level-of [tag] (keyword (subs tag 1)))

;; ---------------------------------------------------------------------------
;; Reading files: the working tree or a revision

(defn- git [root & args]
  (let [r (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn commit-of
  "The commit `rev` names in the repository at `root`, or nil."
  [root rev]
  (when (and rev (not (str/blank? (str rev))))
    (let [r (git root "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
      (when (zero? (:exit r)) (not-empty (str/trim (:out r)))))))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn- reader
  "{:list (fn [dir] [rel path]) :read (fn [rel] text-or-nil)}: the working
  tree under `root`, or the commit `commit` of its repository."
  [root commit]
  (if commit
    {:list (fn [dir]
             (let [r (git root "ls-tree" "-r" "--name-only" "-z" commit "--" (str (strip-slash dir) "/"))]
               (if (zero? (:exit r))
                 (vec (sort (remove str/blank? (str/split (:out r) #"\u0000"))))
                 [])))
     :read (fn [rel]
             (let [r (git root "show" (str commit ":./" rel))]
               (when (zero? (:exit r)) (:out r))))}
    {:list (fn [dir]
             (let [d (command/path root dir)]
               (if (fs/directory? d)
                 (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                              (command/rel root f))))
                 [])))
     :read (fn [rel]
             (let [f (command/path root rel)]
               (when (fs/regular-file? f) (slurp (str f)))))}))

;; ---------------------------------------------------------------------------
;; The model: who owns a feature file

(defn- read-edn-map [text]
  (when text
    (try (let [v (edn/read-string text)] (when (map? v) v))
         (catch Exception _ nil))))

(defn owners
  "{spec-path {:context :component :context-name :component-name}} of every
  component the model `model` names (spec-path as in :spec, e.g.
  \"spec/parser\"); the kernel's components have the context :kernel."
  [model]
  (into {}
        (concat
         (for [c (:containers model) ctx (:contexts c) comp (:components ctx)
               :when (string? (:spec comp))]
           [(:spec comp) {:context (:id ctx)
                          :component (:id comp)
                          :context-name (or (:name ctx) (some-> (:id ctx) name))
                          :component-name (or (:name comp) (some-> (:id comp) name))}])
         (for [comp (:kernel model) :when (string? (:spec comp))]
           [(:spec comp) {:context :kernel
                          :component (:id comp)
                          :context-name "kernel"
                          :component-name (or (:name comp) (some-> (:id comp) name))}]))))

(defn spec-path
  "The feature file `file` as the model names it: relative to `spec-dir`,
  without .feature (\"spec/parser\"); nil when it lies outside."
  [spec-dir file]
  (let [prefix (str (strip-slash spec-dir) "/")]
    (when (str/starts-with? file prefix)
      (str/replace (subs file (count prefix)) #"\.feature$" ""))))

(defn owner-of
  "The owner of `file`: from the model, or, with no model, from the path
  (dhf/spec/<context>/<component>.feature). Nil keys when no one owns it."
  [model spec-dir file]
  (let [sp (spec-path spec-dir file)
        none {:context nil :component nil :context-name nil :component-name nil}]
    (cond
      (nil? sp) none
      model (get (owners model) sp none)
      :else (let [parts (str/split sp #"/")]
              (if (= 2 (count parts))
                (let [[c n] parts]
                  {:context (keyword c) :component (keyword c n) :context-name c :component-name n})
                none)))))

;; ---------------------------------------------------------------------------
;; Needs

(defn- need-line [text id]
  (let [q (java.util.regex.Pattern/quote (pr-str id))
        lines (map-indexed vector (str/split-lines text))]
    (or (some (fn [[i l]] (when (re-find (re-pattern (str "^[\\s{]*" q "(\\s|$)")) l) (inc i))) lines)
        (some (fn [[i l]] (when (and (str/includes? l (pr-str id))
                                     (not (str/starts-with? (str/triml l) ";")))
                            (inc i)))
              lines))))

(defn read-needs
  "{:needs [{:id :text :sources :line}] :needs-error message-or-nil} from the
  needs file's text (nil: the file `rel` does not exist)."
  [rel text]
  (if (nil? text)
    {:needs [] :needs-error (str "the needs file " rel " does not exist")}
    (let [v (try (edn/read-string text)
                 (catch Exception e {::error (str "the needs file " rel " is not readable EDN: " (ex-message e))}))]
      (cond
        (and (map? v) (::error v)) {:needs [] :needs-error (::error v)}
        (not (map? v)) {:needs [] :needs-error (str "the needs file " rel " is not a map of needs, {\"UN-001\" {:text :sources}}")}
        :else
        {:needs (->> (for [[k n] v
                           :let [id (if (keyword? k) (name k) (str k))
                                 n (if (map? n) n {})
                                 sources (:sources n)]]
                       {:id id
                        :text (:text n)
                        :sources (if (sequential? sources) (vec sources) [])
                        :line (need-line text id)})
                     (sort-by (juxt #(or (:line %) Integer/MAX_VALUE) :id))
                     vec)
         :needs-error nil}))))

;; ---------------------------------------------------------------------------
;; Scenarios

(defn- strip-at [t] (subs t 1))

(defn scenario
  "The scenario of a kernel-parsed scenario `sc` of the feature `feature`,
  in `file`, owned by `owner`."
  [prefix file feature owner sc]
  (let [own (vec (:tags sc))
        all (vec (:all-tags sc))
        id-re (id-pattern prefix)
        ids (vec (distinct (map strip-at (filter #(re-matches id-re %) own))))
        levels (vec (distinct (filter level-tags own)))
        outline? (= "Scenario Outline" (:keyword sc))]
    (merge
     {:id (when (= 1 (count ids)) (first ids))
      :ids ids
      :title (:name sc)
      :name (:name sc)
      :file file
      :line (:line sc)
      :keyword (:keyword sc)
      :outline? outline?
      :tags own
      :all-tags all
      :levels levels
      :level (when (= 1 (count levels)) (level-of (first levels)))
      :needs (vec (distinct (map strip-at (filter #(re-matches need-pattern %) all))))
      :wip? (boolean (some #{wip-tag} all))
      :steps (vec (:steps sc))
      :background (vec (:background sc))
      :examples (:examples sc)
      :rows (when outline?
              (vec (for [ex (:examples sc) row (:rows ex)]
                     {:line (:line row) :values (zipmap (:header ex) (:cells row))})))
      :feature (:name feature)
      :rule (get-in sc [:rule :name])
      :rule-line (get-in sc [:rule :line])
      :rule-tags (vec (get-in sc [:rule :tags]))}
     owner)))

(defn parse-file
  "{:feature :scenarios :refusals} of the feature file `file` (a path
  relative to the project root) holding `text`."
  [{:keys [id-prefix spec-dir model]} file text]
  (let [parsed (gherkin/parse-feature file text)
        feature (:feature parsed)
        owner (owner-of model spec-dir file)]
    {:feature (when feature
                {:file file :line (:line feature) :name (:name feature)
                 :tags (vec (:tags feature)) :description (:description feature)})
     :scenarios (mapv #(scenario id-prefix file feature owner %) (:scenarios parsed))
     :refusals (vec (:refusals parsed))}))

;; ---------------------------------------------------------------------------
;; The spec

(defn read-spec
  "The spec under `root` (see the namespace doc). `opts`: :spec-dir (default
  dhf/spec), :id-prefix (default spec), :model and :needs (the files, by
  default model.edn and needs.edn of the spec directory) and :revision (a
  commit, tag or branch to read the spec at; nil for the working tree)."
  [root {:keys [spec-dir id-prefix model needs revision]}]
  (let [spec-dir (strip-slash (or spec-dir "dhf/spec"))
        commit (when revision
                 (or (commit-of root revision)
                     (command/cannot-run! (str "the revision " revision " is not a commit of this repository"))))
        {list-files :list read-text :read} (reader root commit)
        files (list-files spec-dir)
        texts (into (sorted-map) (keep (fn [f] (when-let [t (read-text f)] [f t]))) files)
        model-rel (or model (str spec-dir "/model.edn"))
        m (read-edn-map (read-text model-rel))
        needs-rel (or needs (str spec-dir "/needs.edn"))
        {:keys [needs needs-error]} (read-needs needs-rel (read-text needs-rel))
        ctx {:id-prefix (or id-prefix "spec") :spec-dir spec-dir :model m}
        parsed (for [[f t] texts :when (str/ends-with? f ".feature")] (parse-file ctx f t))]
    {:revision (or commit :working-tree)
     :spec-dir spec-dir
     :files (vec (keys texts))
     :texts texts
     :features (vec (keep :feature parsed))
     :scenarios (vec (mapcat :scenarios parsed))
     :needs needs
     :needs-file needs-rel
     :needs-error needs-error
     :refusals (vec (mapcat :refusals parsed))}))

(defn text-files
  "Every file of the spec as read: [{:file :text}], sorted by path."
  [_root spec]
  (vec (for [[f t] (:texts spec)] {:file f :text t})))

(defn refusal-findings
  "The parser's refusals as findings, each named by file, line and rule."
  [spec]
  (vec (for [{:keys [file line rule message]} (:refusals spec)]
         (command/finding file line rule message))))

(defn payload
  "SpecParsed's payload: what events.edn says it carries."
  [spec]
  {:revision (:revision spec)
   :scenarios (:scenarios spec)
   :needs (mapv #(select-keys % [:id :text :sources]) (:needs spec))
   :refusals (:refusals spec)})

(defn publish!
  "Publishes SpecParsed for `spec` on the run's bus."
  [req spec]
  (command/publish! req :spec/parser :SpecParsed (payload spec)))

;; ---------------------------------------------------------------------------
;; The stage

(defn- cycle-cut
  "The cut a cycle's cycle.edn names, or nil when it names none readable."
  [req cycle]
  (when-let [cycles (get-in req [:config :cycles-dir])]
    (when (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
      (let [f (command/path req (str (strip-slash cycles) "/" cycle "/cycle.edn"))]
        (when (fs/regular-file? f)
          (let [c (read-edn-map (slurp (str f)))]
            (when (and (string? (:cut c)) (not (str/blank? (:cut c))))
              (:cut c))))))))

(defn cut-of
  "The cut the task reading the spec names: --cut, else for a task over a
  cycle (checklist) the cycle's cut. Nil: the working tree."
  [req]
  (or (get-in req [:opts :cut])
      (when (= "checklist" (:task req)) (cycle-cut req (first (:args req))))))

(defn parse
  "The parser's stage: reads the spec (at the cut the task names, else in the
  working tree), leaves it in the run's state as ::spec, publishes
  SpecParsed. A cut the repository does not hold is left to the task to
  refuse: nothing is read or published then."
  [req]
  (let [config (:config req)
        spec-dir (command/config-value req :spec-dir)
        cut (cut-of req)
        opts {:spec-dir spec-dir :id-prefix (:id-prefix config) :model (:model config) :needs (:needs config)}]
    (cond
      (and cut (nil? (commit-of (:root req) cut)))
      {:findings []}

      cut
      (let [spec (read-spec (:root req) (assoc opts :revision cut))]
        (swap! (:state req) assoc ::spec spec)
        (publish! req spec)
        {:findings []})

      :else
      (do (when-not (fs/directory? (command/path req spec-dir))
            (command/cannot-run! (str "no spec directory " spec-dir)))
          (let [spec (read-spec (:root req) opts)]
            (swap! (:state req) assoc ::spec spec)
            (publish! req spec)
            {:findings []})))))
