(ns rdm-spec.spec.parser
  "Parser (dhf/spec/spec/parser.feature): reads the spec, every feature file
  under the configured :spec-dir in the declared Gherkin subset (the kernel's
  one reading of a feature file, rdm-spec.kernel.gherkin), refusing anything
  outside it, never skipping it. It reads the working tree, or the spec as
  it was at a revision (a cut), and publishes what it read as SpecParsed.

  Each scenario, as read (`parse-text`, `read-spec`):

    {:id        the scenario's one id (\"spec-0001\"), nil unless exactly one
     :ids       every id it carries      :levels  its levels, :e2e | :contract
     :level     its level, the first     :tags    its own tags
     :title     its name, trimmed        :name    its name as written
     :keyword   \"Scenario\" | \"Scenario Outline\"
     :file :line                         :needs   [\"UN-001\"] from its feature, rule and itself
     :wip?      tagged @wip              :outline? an outline
     :rows      an outline's example rows [{header cell}], [] for a scenario
     :examples  an outline's tables [{:line :tags :header [cell] :rows [{:line :cells}]}]
     :background the feature's and its rule's background steps [{:keyword :text}]
     :steps     [{:keyword :text}]
     :rule      its rule's name, or nil  :rule-line :rule-tags
     :context :component                  the model's ids of its owner
     :context-name :component-name        the model's names}

  SpecParsed (dhf/spec/events.edn) carries {:revision :scenarios :needs
  :refusals}, each scenario with exactly the declared keys: :id :title :name
  :keyword :file :line :level :needs :wip? :outline? :rows :examples
  :background :rule :steps :context :component :context-name :component-name.

  A feature file is owned by the model's component whose :spec names it; a
  kernel component's context is :kernel, named kernel; a file no component
  owns is owned by its path, <context>/<component>.

  `read-spec` also returns what the spec check reads: :features [{:file
  :feature :examples-tags}], :files [{:file :text}] (every file of the spec),
  :needs [{:id :text :sources}], :needs-lines {id line} and :needs-findings
  (needs-unreadable: the needs file missing or not an EDN map).

  The stage `parse` (quiet, before the stages that read SpecParsed) reads at
  the request's :revision when it names one, and publishes nothing when that
  revision is not a commit of the repository."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; Tags

(defn id-re
  "The id tag of the prefix `prefix`: @<prefix>-NNNN, four digits or more."
  [prefix]
  (re-pattern (str "@" (java.util.regex.Pattern/quote (str (or prefix "spec"))) "-\\d{4,}")))

(def need-re #"@UN-\d+")

(def levels ["@e2e" "@contract"])

(def published-keys
  [:id :title :name :keyword :file :line :level :needs :wip? :outline? :rows :examples :background :rule
   :steps :context :component :context-name :component-name])

;; ---------------------------------------------------------------------------
;; One feature file

(defn- step [st] {:keyword (:keyword st) :text (:text st)})

(defn- scenario [file prefix sc]
  (let [own (vec (:tags sc))
        all (vec (:all-tags sc))
        ids (vec (distinct (keep #(when (re-matches (id-re prefix) %) (subs % 1)) own)))
        lvls (vec (distinct (keep #(when (some #{%} levels) (keyword (subs % 1))) own)))
        outline? (= "Scenario Outline" (:keyword sc))]
    {:id (when (= 1 (count ids)) (first ids))
     :ids ids
     :levels lvls
     :level (first lvls)
     :tags own
     :title (str/trim (str (:name sc)))
     :name (:name sc)
     :keyword (:keyword sc)
     :file file
     :line (:line sc)
     :needs (vec (distinct (keep #(when (re-matches need-re %) (subs % 1)) all)))
     :wip? (boolean (some #{"@wip"} all))
     :outline? outline?
     :rows (if outline? (mapv :values (gherkin/outline-rows sc)) [])
     :examples (vec (:examples sc))
     :background (mapv step (:background sc))
     :steps (mapv step (:steps sc))
     :rule (get-in sc [:rule :name])
     :rule-line (get-in sc [:rule :line])
     :rule-tags (vec (get-in sc [:rule :tags]))}))

(defn parse-text
  "The feature file `file` holding `text`: {:feature :scenarios :refusals
  :examples-tags [{:line :tags}]}. `opts`: :prefix, the id prefix."
  [file text {:keys [prefix]}]
  (let [parsed (gherkin/parse-feature file text)]
    {:feature (:feature parsed)
     :scenarios (mapv #(scenario file prefix %) (:scenarios parsed))
     :refusals (vec (:refusals parsed))
     :examples-tags (vec (for [sc (:scenarios parsed) ex (:examples sc) :when (seq (:tags ex))]
                           {:line (:line ex) :tags (:tags ex)}))}))

;; ---------------------------------------------------------------------------
;; Owners

(defn owners
  "{spec-path {:context :component :context-name :component-name}} of the
  model `m`."
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
                          :context-name "kernel" :component-name (or (:name comp) (some-> (:id comp) name))}]))))

(defn owner-of
  "The owner of the feature file `rel` under `spec-dir`."
  [owner-map spec-dir rel]
  (let [prefix (str (str/replace (str spec-dir) #"/+$" "") "/")
        sp (when (str/starts-with? rel prefix) (str/replace (subs rel (count prefix)) #"\.feature$" ""))]
    (or (get owner-map sp)
        (let [parts (when sp (str/split sp #"/"))]
          (if (= 2 (count parts))
            {:context (keyword (first parts)) :component (keyword (first parts) (second parts))
             :context-name (first parts) :component-name (second parts)}
            {:context nil :component nil :context-name nil :component-name nil})))))

;; ---------------------------------------------------------------------------
;; Reading the spec, from the working tree or at a revision

(defn- git [root & args]
  (try (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} "git" args)
       (catch Exception _ {:exit -1 :out "" :err "git cannot be run"})))

(defn- commit-of [root rev]
  (let [r (git root "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn- working-tree-reader [root]
  {:list (fn [dir]
           (let [d (command/path root dir)]
             (if (fs/directory? d)
               (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (command/rel root f))))
               [])))
   :read (fn [rel] (let [f (command/path root rel)] (when (fs/regular-file? f) (slurp (str f)))))})

(defn- revision-reader [root commit]
  {:list (fn [dir]
           (let [r (git root "ls-tree" "-r" "--name-only" "-z" commit "--" (str/replace (str dir) #"/+$" ""))]
             (if (zero? (:exit r)) (vec (sort (remove str/blank? (str/split (:out r) #"\x00")))) [])))
   :read (fn [rel]
           (let [r (git root "show" (str commit ":" rel))]
             (when (zero? (:exit r)) (:out r))))})

(defn- line-of [text part]
  (some (fn [[i l]] (when (str/includes? l part) (inc i))) (map-indexed vector (str/split-lines (str text)))))

(defn- read-needs [rel text]
  (let [v (when text (try {:ok (edn/read-string text)} (catch Exception e {:error (ex-message e)})))]
    (cond
      (nil? text) {:findings [(command/finding rel "needs-unreadable" (str "there is no needs file " rel))]}
      (:error v) {:findings [(command/finding rel "needs-unreadable" (str "the needs file is not readable EDN: " (:error v)))]}
      (not (map? (:ok v))) {:findings [(command/finding rel "needs-unreadable" "the needs file is not an EDN map of needs")]}
      :else {:needs (vec (for [[id n] (sort-by (comp str key) (:ok v))]
                           {:id (str id) :text (when (map? n) (:text n))
                            :sources (vec (when (map? n) (filter string? (:sources n))))}))
             :lines (into {} (for [[id _] (:ok v)] [(str id) (line-of text (pr-str (str id)))]))})))

(defn- read-with [{:keys [list read]} config revision]
  (let [spec-dir (str/replace (str (command/config-value config :spec-dir)) #"/+$" "")
        prefix (command/config-value config :id-prefix)
        model (try (edn/read-string (or (read (command/config-value config :model)) "nil")) (catch Exception _ nil))
        owner-map (owners model)
        files (list spec-dir)
        texts (vec (keep (fn [f] (when-let [t (read f)] {:file f :text t})) files))
        parsed (for [{:keys [file text]} texts :when (str/ends-with? file ".feature")]
                 (assoc (parse-text file text {:prefix prefix}) :file file))
        needs-rel (command/config-value config :needs)
        needs (read-needs needs-rel (read needs-rel))]
    {:revision revision
     :scenarios (vec (for [pf parsed sc (:scenarios pf)]
                       (merge sc (owner-of owner-map spec-dir (:file pf)))))
     :refusals (vec (mapcat :refusals parsed))
     :features (mapv #(select-keys % [:file :feature :examples-tags]) parsed)
     :files texts
     :needs (or (:needs needs) [])
     :needs-lines (or (:lines needs) {})
     :needs-findings (vec (:findings needs))}))

(defn read-spec
  "The spec of the project at `root` with the configuration `config`: in the
  working tree, or at the revision `revision` (nil when the repository holds
  no such commit)."
  ([root config] (read-with (working-tree-reader root) config :working-tree))
  ([root config revision]
   (if (nil? revision)
     (read-spec root config)
     (when-let [c (commit-of root revision)]
       (read-with (revision-reader root c) config c)))))

;; ---------------------------------------------------------------------------
;; The event and the stage

(defn published
  "The scenario `sc` as SpecParsed carries it: exactly the declared keys."
  [sc]
  (into {} (map (fn [k] [k (get sc k)])) published-keys))

(defn publish!
  "Publishes SpecParsed for the read `spec`."
  [req spec]
  (command/publish! req :spec/parser :SpecParsed
                    {:revision (:revision spec)
                     :scenarios (mapv published (:scenarios spec))
                     :needs (:needs spec)
                     :refusals (mapv #(select-keys % [:file :line :rule :message]) (:refusals spec))}))

(defn parse
  "The parser's stage: reads the spec (at the request's :revision when it
  names one) and publishes SpecParsed; its refusals are its findings."
  [req]
  (if-let [spec (read-spec (:root req) (:config req) (:revision req))]
    (do (publish! req spec)
        {:result {:spec {:revision (:revision spec) :scenarios (count (:scenarios spec))}}
         :findings (vec (for [{:keys [file line rule message]} (:refusals spec)]
                          (command/finding file line rule message)))})
    {:result {} :findings []}))
