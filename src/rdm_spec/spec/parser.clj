(ns rdm-spec.spec.parser
  "The Parser (dhf/spec/spec/parser.feature): reads the spec, in the declared
  Gherkin subset (read by the kernel's reader, rdm-spec.kernel.gherkin, so a
  feature file is never read two ways), at the working tree or at a commit,
  and publishes SpecParsed (dhf/spec/events.edn).

  A scenario as read:

    {:id :ids :title :name :keyword :file :line :level :levels :needs :wip?
     :outline? :rows :examples :background :rule :rule-line :rule-tags :tags
     :all-tags :steps :context :component :context-name :component-name}

  :ids every scenario id it carries (@<prefix>-NNNN, without the @), :id
  the one when it carries exactly one; :levels its levels (:e2e, :contract),
  :level the one when it carries exactly one; :needs the need tags of its
  feature, its rule and its own (UN-nnn); :wip? tagged @wip; :rows an
  outline's example rows [{:line :values}]; :rule its rule's name or nil;
  :context and :component the model's ids of the component owning its file
  (the kernel's under the context :kernel), the -name keys their :name.

  `read-spec` gives {:revision :scenarios :refusals :features :files :needs
  :needs-lines :needs-findings}: :features [{:file :feature :examples-tags}],
  :files every file of the spec with its text, :needs the declared needs
  [{:id :text :sources}], :needs-lines {id line} and :needs-findings
  (needs-unreadable: the needs file missing or not an EDN map)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

(defn id-re
  "The tag of a scenario id with `prefix`: @spec-0042."
  [prefix]
  (re-pattern (str "@" (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}")))

(def levels
  "The level tags."
  #{"@e2e" "@contract"})

(def need-re
  "A need tag."
  #"@UN-\d+")

;; ---------------------------------------------------------------------------
;; One feature file

(defn- step-of [st]
  (cond-> {:keyword (:keyword st) :text (:text st) :line (:line st)}
    (contains? st :doc-string) (assoc :doc-string (:doc-string st))
    (contains? st :data-table) (assoc :data-table (:data-table st))))

(defn- scenario-of [rel prefix owner feature sc]
  (let [own (vec (:tags sc))
        rule (:rule sc)
        all (vec (distinct (concat (:tags feature) (:tags rule) own)))
        ids (vec (distinct (for [t own :when (re-matches (id-re prefix) t)] (subs t 1))))
        lvls (vec (distinct (for [t own :when (levels t)] (keyword (subs t 1)))))
        needs (vec (distinct (for [t all :when (re-matches need-re t)] (subs t 1))))]
    (merge
     {:id (when (= 1 (count ids)) (first ids))
      :ids ids
      :title (:name sc)
      :name (:name sc)
      :keyword (:keyword sc)
      :file rel
      :line (:line sc)
      :tags own
      :all-tags all
      :levels lvls
      :level (when (= 1 (count lvls)) (first lvls))
      :needs needs
      :wip? (boolean (some #{"@wip"} all))
      :outline? (= "Scenario Outline" (:keyword sc))
      :rows (gherkin/outline-rows sc)
      :examples (vec (:examples sc))
      :background (mapv step-of (:background sc))
      :rule (:name rule)
      :rule-line (:line rule)
      :rule-tags (vec (:tags rule))
      :steps (mapv step-of (:steps sc))
      :context nil :component nil :context-name nil :component-name nil}
     owner)))

(defn parse-text
  "The feature file `rel` holding `text`: {:file :feature :scenarios
  :refusals :examples-tags}. `opts`: :prefix (the id prefix) and :owner
  ({:context :component :context-name :component-name})."
  [rel text {:keys [prefix owner]}]
  (let [parsed (gherkin/parse-feature rel text)
        feature (:feature parsed)]
    {:file rel
     :feature feature
     :scenarios (mapv #(scenario-of rel prefix owner feature %) (:scenarios parsed))
     :refusals (vec (:refusals parsed))
     :examples-tags (vec (for [sc (:scenarios parsed) ex (:examples sc) :when (seq (:tags ex))]
                           {:line (:line ex) :tags (:tags ex)}))}))

;; ---------------------------------------------------------------------------
;; The spec at a revision

(defn- git [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn- reader
  "{:list (fn [dir] [rel]) :read (fn [rel] text-or-nil)} of the working
  tree under `root`, or of `revision`."
  [root revision]
  (if (string? revision)
    {:list (fn [dir]
             (let [r (git root "ls-tree" "-r" "-z" "--name-only" revision "--" (str (strip-slash dir) "/"))]
               (when-not (zero? (:exit r))
                 (command/cannot-run! (str "the spec at " revision " cannot be read: " (str/trim (:err r)))))
               (vec (sort (remove str/blank? (str/split (:out r) #"\u0000"))))))
     :read (fn [rel]
             (let [r (git root "show" (str revision ":" rel))]
               (when (zero? (:exit r)) (:out r))))}
    {:list (fn [dir]
             (let [d (command/path root dir)]
               (if (fs/directory? d)
                 (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (command/rel root f))))
                 [])))
     :read (fn [rel]
             (let [f (command/path root rel)]
               (when (fs/regular-file? f) (slurp (str f)))))}))

(defn owners
  "{spec-path {:context :component :context-name :component-name}} of the
  model `m`; a kernel component's context is :kernel."
  [m]
  (into {}
        (concat
         (for [c (when (map? m) (:containers m)) :when (map? c)
               x (:contexts c) :when (map? x)
               comp (:components x) :when (and (map? comp) (string? (:spec comp)))]
           [(:spec comp) {:context (:id x) :component (:id comp)
                          :context-name (or (:name x) (some-> (:id x) name))
                          :component-name (or (:name comp) (some-> (:id comp) name))}])
         (for [comp (when (map? m) (:kernel m)) :when (and (map? comp) (string? (:spec comp)))]
           [(:spec comp) {:context :kernel :component (:id comp)
                          :context-name "kernel"
                          :component-name (or (:name comp) (some-> (:id comp) name))}]))))

(defn- read-model [text]
  (try (let [m (edn/read-string text)] (when (map? m) m)) (catch Exception _ nil)))

(defn- read-needs [rel text]
  (let [v (when text (try {:ok (edn/read-string text)} (catch Exception e {:error (ex-message e)})))]
    (cond
      (nil? text) {:needs [] :findings [(command/finding rel "needs-unreadable" (str "there is no needs file " rel))]}
      (:error v) {:needs [] :findings [(command/finding rel "needs-unreadable"
                                                        (str "the needs file is not readable EDN: " (:error v)))]}
      (not (map? (:ok v))) {:needs [] :findings [(command/finding rel "needs-unreadable"
                                                                  "the needs file is not a map of needs, {\"UN-001\" {:text :sources}}")]}
      :else
      {:needs (vec (for [[id n] (sort-by (comp str key) (:ok v))]
                     {:id (str id) :text (when (map? n) (:text n))
                      :sources (vec (when (map? n) (:sources n)))}))
       :lines (into {} (for [[id _] (:ok v)
                             :let [line (some (fn [[i l]] (when (str/includes? l (pr-str (str id))) (inc i)))
                                              (map-indexed vector (str/split-lines text)))]]
                         [(str id) line]))
       :findings []})))

(defn read-spec
  "The spec of the project `root`, as its configuration `config` places it,
  at the working tree or at `:revision` (a commit): see the namespace doc."
  ([root config] (read-spec root config {}))
  ([root config {:keys [revision]}]
   (let [cfg {:config config}
         spec-dir (strip-slash (command/config-value cfg :spec-dir))
         prefix (command/config-value cfg :id-prefix)
         {list-files :list read-text :read} (reader root revision)
         files (list-files spec-dir)
         texts (vec (keep (fn [f] (when-let [t (read-text f)] {:file f :text t})) files))
         text-of (into {} (map (juxt :file :text)) texts)
         model-rel (command/config-value cfg :model)
         owner-map (owners (read-model (or (get text-of model-rel) (read-text model-rel))))
         owner-of (fn [rel]
                    (let [sp (str/replace (subs rel (inc (count spec-dir))) #"\.feature$" "")]
                      (get owner-map sp)))
         parsed (vec (for [{:keys [file text]} texts :when (str/ends-with? file ".feature")]
                       (parse-text file text {:prefix prefix :owner (owner-of file)})))
         needs-rel (command/config-value cfg :needs)
         needs (read-needs needs-rel (or (get text-of needs-rel) (read-text needs-rel)))]
     {:revision (if (string? revision) revision :working-tree)
      :scenarios (vec (mapcat :scenarios parsed))
      :refusals (vec (mapcat :refusals parsed))
      :features (mapv #(select-keys % [:file :feature :examples-tags]) parsed)
      :files texts
      :needs (:needs needs)
      :needs-lines (or (:lines needs) {})
      :needs-findings (:findings needs)})))

;; ---------------------------------------------------------------------------
;; SpecParsed

(defn publish!
  "Publishes SpecParsed of the read spec `spec`."
  [req spec]
  (command/publish! req :spec/parser :SpecParsed
                    {:revision (:revision spec)
                     :scenarios (:scenarios spec)
                     :needs (:needs spec)
                     :refusals (mapv #(select-keys % [:file :line :rule :message]) (:refusals spec))}))

(defn parse
  "The parser's stage: reads the spec at the request's :revision (the
  working tree when it names none) and publishes SpecParsed. It reports
  nothing: the stages that read the spec relay its refusals."
  [req]
  (let [spec (read-spec (:root req) (:config req) {:revision (:revision req)})]
    (publish! req spec)
    {:result {} :findings []}))
