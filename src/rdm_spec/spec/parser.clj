(ns rdm-spec.spec.parser
  "Parser (dhf/spec/spec/parser.feature): reads the spec, at a revision,
  through the kernel's Gherkin reader (rdm-spec.kernel.gherkin), which reads
  the declared subset and refuses anything outside it, never skipping it.

  The spec is read from the working tree, or from a commit: a task that names
  a cut reads the spec as it was at that cut (`rdm-spec checklist CYCLE`, the
  cut its cycle.edn names), its model and needs included.

  `read-spec` returns SpecParsed's payload (dhf/spec/events.edn) with more
  than the event carries, for the Spec check:

    {:revision  \"<commit>\" | :working-tree
     :scenarios [{:id :ids :title :name :keyword :file :line :level :levels
                  :needs :wip? :outline? :rows :steps :background :examples
                  :rule :rule-line :tags :all-tags :feature-tags :rule-tags
                  :feature-line :context :component :context-name
                  :component-name}]
     :needs     [{:id :text :sources}]
     :refusals  [{:file :line :rule :message}]
     :files     [{:file :text}]          ; every file under the spec directory
     :features  [{:file :feature :examples-tags [{:line :tags}]}]
     :needs-findings [finding]           ; the needs file missing or unreadable
     :needs-lines {\"UN-001\" line}}

  :id is the scenario's one id tag (@<prefix>-NNNN, four digits or more),
  nil when it carries none or several (:ids holds them all); :level is :e2e
  or :contract when it carries exactly one; :needs the need tags (@UN-nnn) of
  the scenario, its rule and its feature; :wip? whether @wip is on it.
  :context and :component are the model's ids of the component owning the
  file (kernel components: context :kernel), the -name keys its :name; a file
  no component owns is named by its path, <context>/<component>.feature.

  The parse stage publishes SpecParsed and reports nothing itself: what reads
  the spec relays what stops it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; Tags

(defn id-re
  "The pattern of an id tag with `prefix`: @<prefix>-NNNN, four digits or more."
  [prefix]
  (re-pattern (str "@" (java.util.regex.Pattern/quote (str prefix)) "-\\d{4,}")))

(def any-id-re
  "An id tag of any prefix."
  #"@[A-Za-z](?:[A-Za-z0-9]|-(?=[A-Za-z0-9]))*-\d{4,}")

(def need-re #"@UN-\d+")

(def levels ["@e2e" "@contract"])

(defn id-tags [prefix tags] (vec (distinct (filter #(re-matches (id-re prefix) %) tags))))

(defn need-tags [tags] (vec (distinct (filter #(re-matches need-re %) tags))))

(defn level-tags [tags] (vec (distinct (filter (set levels) tags))))

;; ---------------------------------------------------------------------------
;; Reading files at a revision

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn commit-of
  "The commit `rev` names in the repository at `root`, or nil."
  [root rev]
  (let [r (git root "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn reader
  "{:revision :read (rel -> text or nil) :list (dir -> [rel], every file
  under it)}: the working tree of `root`, or the commit `commit`."
  [root commit]
  (if commit
    {:revision commit
     :read (fn [rel]
             (let [r (git root "show" (str commit ":" rel))]
               (when (zero? (:exit r)) (:out r))))
     :list (fn [dir]
             (let [r (git root "ls-tree" "-r" "--name-only" commit "--" (str dir "/"))]
               (if (zero? (:exit r)) (vec (sort (remove str/blank? (str/split-lines (:out r))))) [])))}
    {:revision :working-tree
     :read (fn [rel]
             (let [f (command/path root rel)]
               (when (fs/regular-file? f) (slurp (str f)))))
     :list (fn [dir]
             (let [d (command/path root dir)]
               (if (fs/directory? d)
                 (vec (sort (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
                              (command/rel root f))))
                 [])))}))

;; ---------------------------------------------------------------------------
;; The model's owners

(defn- read-edn [text]
  (when text (try (edn/read-string text) (catch Exception _ nil))))

(defn owners
  "{spec-path {:context :component :context-name :component-name}} of the
  model `m` (kernel components: context :kernel)."
  [m]
  (let [m (if (map? m) m {})
        maps (fn [xs] (if (sequential? xs) (filter map? xs) []))]
    (into {}
          (concat
           (for [c (maps (:containers m)) x (maps (:contexts c)) comp (maps (:components x))
                 :when (string? (:spec comp))]
             [(:spec comp) {:context (:id x) :component (:id comp)
                            :context-name (or (:name x) (some-> (:id x) name))
                            :component-name (or (:name comp) (some-> (:id comp) name))}])
           (for [comp (maps (:kernel m)) :when (string? (:spec comp))]
             [(:spec comp) {:context :kernel :component (:id comp)
                            :context-name "kernel"
                            :component-name (or (:name comp) (some-> (:id comp) name))}])))))

(defn owner-of
  "The owner of the feature file `rel` under `spec-dir`."
  [owner-map spec-dir rel]
  (let [prefix (str spec-dir "/")
        sp (when (str/starts-with? rel prefix) (str/replace (subs rel (count prefix)) #"\.feature$" ""))]
    (or (get owner-map sp)
        (let [parts (when sp (str/split sp #"/"))]
          (if (= 2 (count parts))
            {:context (keyword (first parts)) :component (keyword (first parts) (second parts))
             :context-name (first parts) :component-name (second parts)}
            {:context nil :component nil :context-name nil :component-name nil})))))

;; ---------------------------------------------------------------------------
;; Scenarios

(defn scenario-entry
  "Pure: one scenario of the feature file `rel`, as SpecParsed carries it."
  [rel parsed sc prefix owner]
  (let [ids (mapv #(subs % 1) (id-tags prefix (:tags sc)))
        lvls (level-tags (:tags sc))
        outline? (= "Scenario Outline" (:keyword sc))]
    (merge
     {:id (when (= 1 (count ids)) (first ids))
      :ids ids
      :title (:name sc)
      :name (:name sc)
      :keyword (:keyword sc)
      :file rel
      :line (:line sc)
      :level (when (= 1 (count lvls)) (keyword (subs (first lvls) 1)))
      :levels (mapv #(keyword (subs % 1)) lvls)
      :needs (mapv #(subs % 1) (need-tags (:all-tags sc)))
      :wip? (boolean (some #{"@wip"} (:tags sc)))
      :outline? outline?
      :rows (if outline? (gherkin/outline-rows sc) [])
      :steps (mapv #(select-keys % [:keyword :text :line :doc-string :data-table]) (:steps sc))
      :background (mapv #(select-keys % [:keyword :text :line :doc-string :data-table]) (:background sc))
      :examples (vec (for [ex (:examples sc)] (select-keys ex [:line :tags :header :rows])))
      :rule (get-in sc [:rule :name])
      :rule-line (get-in sc [:rule :line])
      :tags (vec (:tags sc))
      :all-tags (vec (:all-tags sc))
      :feature-tags (vec (get-in parsed [:feature :tags]))
      :rule-tags (vec (get-in sc [:rule :tags]))
      :feature-line (get-in parsed [:feature :line])}
     owner)))

(defn parse-text
  "Pure: the feature file `rel` holding `text`: {:scenarios :refusals :feature}."
  [rel text {:keys [prefix owner]}]
  (let [parsed (gherkin/parse-feature rel text)]
    {:feature (:feature parsed)
     :examples-tags (vec (for [sc (:scenarios parsed) ex (:examples sc) :when (seq (:tags ex))]
                           {:line (:line ex) :tags (:tags ex)}))
     :scenarios (mapv #(scenario-entry rel parsed % prefix owner) (:scenarios parsed))
     :refusals (mapv #(select-keys % [:file :line :rule :message]) (:refusals parsed))}))

;; ---------------------------------------------------------------------------
;; Needs

(defn- key-lines
  "{\"UN-001\" line} of every need key written in `text`."
  [text]
  (into {}
        (for [[i l] (map-indexed vector (str/split-lines (or text "")))
              [_ k] (re-seq #"\"(UN-\d+)\"" l)]
          [k (inc i)])))

(defn read-needs
  "The needs file `rel` holding `text` (nil: missing): {:needs [{:id :text
  :sources}] :findings [..] :lines {id line}}. A missing or unreadable needs
  file is a finding: it is part of the spec."
  [rel text]
  (let [v (when text (try {:ok (edn/read-string text)} (catch Exception e {:error (ex-message e)})))
        lines (key-lines text)]
    (cond
      (nil? text)
      {:needs [] :lines {} :findings [(command/finding rel "needs-unreadable" (str "there is no needs file " rel))]}

      (:error v)
      {:needs [] :lines {} :findings [(command/finding rel "needs-unreadable"
                                                       (str "the needs file is not readable EDN: " (:error v)))]}

      (not (map? (:ok v)))
      {:needs [] :lines {} :findings [(command/finding rel "needs-unreadable"
                                                       "the needs file is not an EDN map of need id to {:text :sources}")]}

      :else
      {:needs (vec (for [[k n] (sort-by (comp str key) (:ok v))]
                     {:id (str k)
                      :text (when (map? n) (:text n))
                      :sources (if (and (map? n) (sequential? (:sources n))) (vec (:sources n)) [])}))
       :lines lines
       :findings []})))

;; ---------------------------------------------------------------------------
;; The spec

(defn read-spec
  "The spec of the project at `root` with configuration `config`, read from
  the working tree or, with `commit`, at that commit; see the namespace doc."
  ([root config] (read-spec root config nil))
  ([root config commit]
   (let [req {:root root :config config}
         spec-dir (str/replace (command/config-value req :spec-dir) #"/+$" "")
         prefix (command/config-value req :id-prefix)
         {:keys [revision read list]} (reader root commit)
         files (list spec-dir)
         owner-map (owners (read-edn (read (command/config-value req :model))))
         features (vec (for [rel files :when (str/ends-with? rel ".feature")]
                         (assoc (parse-text rel (or (read rel) "") {:prefix prefix
                                                                    :owner (owner-of owner-map spec-dir rel)})
                                :file rel)))
         needs-rel (command/config-value req :needs)
         needs (read-needs needs-rel (read needs-rel))]
     {:revision revision
      :scenarios (vec (mapcat :scenarios features))
      :needs (:needs needs)
      :refusals (vec (mapcat :refusals features))
      :files (vec (for [rel files] {:file rel :text (read rel)}))
      :features (mapv #(select-keys % [:file :feature :examples-tags]) features)
      :needs-findings (:findings needs)
      :needs-lines (:lines needs)})))

(def carried
  "What SpecParsed carries of a read spec."
  [:revision :scenarios :needs :refusals])

(defn publish!
  "Publishes the read spec as SpecParsed, from the Parser."
  [req spec]
  (command/publish! req :spec/parser :SpecParsed (select-keys spec carried)))

;; ---------------------------------------------------------------------------
;; The stage

(defn- cycle-cut
  "The commit of the cut the cycle named by the task's argument declares, or
  nil when there is none to read (the task's own stage says why)."
  [req]
  (let [cycle (first (:args req))
        cycles (str/replace (command/config-value req :cycles-dir) #"/+$" "")]
    (when (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
      (let [f (command/path req (str cycles "/" cycle "/cycle.edn"))
            c (when (fs/regular-file? f) (read-edn (slurp (str f))))
            cut (when (map? c) (:cut c))]
        (when (and (string? cut) (not (str/blank? cut)))
          (commit-of (:root req) cut))))))

(defn revision-of
  "The commit a task reads the spec at, or nil for the working tree: the
  checklist reads its cycle's cut."
  [req]
  (when (= "checklist" (:task req))
    (cycle-cut req)))

(defn parse
  "The parse stage: reads the spec at the task's revision and publishes
  SpecParsed. A checklist whose cut cannot be read publishes nothing; the
  checklist says why."
  [req]
  (let [commit (revision-of req)]
    (when (or commit (not= "checklist" (:task req)))
      (publish! req (read-spec (:root req) (:config req) commit)))
    {:result {} :findings []}))
