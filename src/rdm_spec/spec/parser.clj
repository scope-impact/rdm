(ns rdm-spec.spec.parser
  "Reads the declared Gherkin subset and refuses anything outside it; reads a
  project's spec (one, or several :specs), each scenario with its id, tags,
  steps and owning component (dhf/spec/02-write-the-spec/parser.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

;; ------------------------------------------------------------------ Gherkin

(def step-re #"^(Given|When|Then|And|But)(?:\s+(.*))?$")
(def header-re #"^(Feature|Background|Rule|Scenario Outline|Scenario|Examples):\s*(.*)$")
(def keyword-like-re #"^([A-Z][a-z]+(?: [A-Z][a-z]+)*):(?:\s|$)")

(defn- cells [line]
  (let [s (str/trim line)
        s (subs s 1 (if (and (> (count s) 1) (str/ends-with? s "|")) (dec (count s)) (count s)))]
    (mapv #(str/replace (str/trim %) "\\|" "|") (str/split s #"(?<!\\)\|" -1))))

(defn- refusal [file line rule message] {:file file :line line :rule rule :message message})

(defn- id-tag? [prefix t] (boolean (re-matches (re-pattern (str (java.util.regex.Pattern/quote prefix) "-\\d{4,}")) t)))

(defn parse-feature
  "Parses one feature file's text. Returns {:feature :scenarios :refusals}.
  Tags are kept without their @."
  [text file prefix]
  (let [lines (vec (str/split-lines (str text)))
        st (volatile! {:feature nil :rule nil :background [] :scenarios [] :refusals []
                       :pending-tags [] :block nil :doc nil :in-steps? false})
        refuse! (fn [line rule msg] (vswap! st update :refusals conj (refusal file line rule msg)))
        cur-steps-path (fn [s] (case (:block s)
                                 :background [:background]
                                 :scenario [:scenarios (dec (count (:scenarios s))) :steps]
                                 nil))]
    (doseq [[i raw] (map-indexed vector lines)]
      (let [n (inc i) t (str/trim raw) s @st]
        (cond
          ;; inside a doc string
          (:doc s)
          (if (or (str/starts-with? t "\"\"\"") (str/starts-with? t "```"))
            (let [{:keys [path lines-acc]} (:doc s)]
              (vswap! st #(-> % (assoc :doc nil)
                              (update-in (conj path (dec (count (get-in % path)))) assoc :arg {:doc-string (str/join "\n" lines-acc)}))))
            (let [indent (get-in s [:doc :indent])
                  l (if (and (>= (count raw) indent) (str/blank? (subs raw 0 indent))) (subs raw indent) (str/triml raw))]
              (vswap! st update-in [:doc :lines-acc] conj l)))

          (str/blank? t) nil
          (str/starts-with? t "#") nil

          (str/starts-with? t "@")
          (let [tags (str/split (str/trim (first (str/split t #"\s#"))) #"\s+")]
            (if (every? #(re-matches #"@\S+" %) tags)
              (vswap! st update :pending-tags into (map #(subs % 1) tags))
              (refuse! n "tag-malformed" (str "a tag line holds something that is not a tag: " t))))

          (re-matches header-re t)
          (let [[_ kw title] (re-matches header-re t)
                tags (:pending-tags s)]
            (vswap! st assoc :pending-tags [])
            (case kw
              "Feature" (if (:feature s)
                          (refuse! n "feature-twice" (str "a feature file holds exactly one feature; the first is on line " (get-in s [:feature :line])))
                          (vswap! st assoc :feature {:name title :line n :tags tags} :block :feature :in-steps? false))
              "Background" (if-not (:feature s)
                             (refuse! n "feature-missing" "Background before any Feature")
                             (vswap! st assoc :block :background :in-steps? false))
              "Rule" (if-not (:feature s)
                       (refuse! n "feature-missing" "Rule before any Feature")
                       (vswap! st assoc :rule {:name title :line n :tags tags} :block :rule :in-steps? false))
              ("Scenario" "Scenario Outline")
              (if-not (:feature s)
                (refuse! n "feature-missing" (str kw " before any Feature"))
                (vswap! st #(-> % (update :scenarios conj {:name title :title title :keyword kw :line n :own-tags tags
                                                           :rule (:rule %) :steps [] :examples []
                                                           :outline? (= kw "Scenario Outline")})
                                (assoc :block :scenario :in-steps? false))))
              "Examples"
              (let [sc (last (:scenarios s))]
                (if (and sc (:outline? sc) (= :scenario (:block s)) )
                  (vswap! st #(update-in % [:scenarios (dec (count (:scenarios %))) :examples] conj {:line n :header nil :rows []}))
                  (if (and sc (:outline? sc) (= :examples (:block s)))
                    (vswap! st #(update-in % [:scenarios (dec (count (:scenarios %))) :examples] conj {:line n :header nil :rows []}))
                    (refuse! n "examples-misplaced" "Examples belong under a Scenario Outline")))
                (vswap! st assoc :block :examples))))

          (re-matches step-re t)
          (let [[_ kw text] (re-matches step-re t)]
            (if-let [path (cur-steps-path s)]
              (vswap! st #(-> % (update-in path conj {:keyword kw :text (str text) :line n}) (assoc :in-steps? true)))
              (refuse! n "step-misplaced" (str "a step outside any scenario or background: " t))))

          (str/starts-with? t "|")
          (cond
            (= :examples (:block s))
            (let [si (dec (count (:scenarios s)))
                  ei (dec (count (get-in s [:scenarios si :examples])))
                  ex (get-in s [:scenarios si :examples ei])]
              (if (nil? (:header ex))
                (vswap! st assoc-in [:scenarios si :examples ei :header] (cells t))
                (vswap! st update-in [:scenarios si :examples ei :rows] conj {:line n :cells (cells t)})))
            (and (cur-steps-path s) (seq (get-in s (cur-steps-path s))))
            (let [path (cur-steps-path s) k (dec (count (get-in s path)))]
              (vswap! st update-in (conj path k :arg :table) (fnil conj []) (cells t)))
            :else (refuse! n "table-misplaced" "a table row belongs to a step or to Examples"))

          (or (str/starts-with? t "\"\"\"") (str/starts-with? t "```"))
          (if-let [path (and (cur-steps-path s) (seq (get-in s (cur-steps-path s))) (cur-steps-path s))]
            (vswap! st assoc :doc {:path path :indent (- (count raw) (count (str/triml raw))) :lines-acc [] :line n})
            (refuse! n "doc-string-misplaced" "a doc string belongs to a step"))

          (re-find keyword-like-re t)
          (refuse! n "keyword-unknown" (str "\"" (second (re-find keyword-like-re t)) ":\" is not a keyword of the declared Gherkin subset"))

          (or (nil? (:feature s)))
          (refuse! n "line-unknown" (str "text before the Feature line: " t))

          (:in-steps? s)
          (refuse! n "line-unknown" (str "a line that is no step, table, tag or comment: " t))

          :else nil)))
    (let [s @st]
      (when-let [d (:doc s)] (refuse! (:line d) "doc-string-unclosed" "a doc string is never closed"))
      (when (and (nil? (:feature s)) (some #(not (or (str/blank? %) (str/starts-with? (str/trim %) "#"))) lines)
                 (empty? (:refusals s)))
        (refuse! 1 "feature-missing" "no Feature line"))
      (let [s @st
            feature (:feature s)
            bg (:background s)
            scenarios
            (vec (for [sc (:scenarios s)]
                   (let [rule (:rule sc)
                         own (:own-tags sc)
                         all (vec (distinct (concat (:tags feature) (:tags rule) own)))
                         ids (filterv #(id-tag? prefix %) own)
                         levels (filterv #{"e2e" "contract"} own)
                         needs (vec (distinct (filter #(re-matches #"UN-\d+" %) all)))
                         rows (vec (for [ex (:examples sc) r (:rows ex)]
                                     {:line (:line r) :order (:header ex)
                                      :values (zipmap (:header ex) (:cells r))}))]
                     (merge (dissoc sc :own-tags)
                            {:file file :feature (:name feature) :feature-line (:line feature)
                             :feature-tags (:tags feature)
                             :rule (:name rule) :rule-line (:line rule) :rule-tags (:tags rule)
                             :own-tags own :tags all
                             :id (when (= 1 (count ids)) (first ids)) :ids ids
                             :levels levels :level (when (= 1 (count levels)) (keyword (first levels)))
                             :needs needs :wip? (boolean (some #{"wip"} own))
                             :background bg :rows rows
                             :steps (mapv #(dissoc % :line) (:steps sc))
                             :step-lines (mapv :line (:steps sc))}))))]
        {:feature feature :scenarios scenarios :refusals (:refusals s)}))))

;; ------------------------------------------------------------------ the model and ownership

(defn read-model-lenient [path]
  (try (when (fs/exists? path) (let [m (edn/read-string (slurp (str path)))] (when (map? m) m)))
       (catch Exception _ nil)))

(defn components
  "Every component of the model, kernel included: [{:id :name :description :spec :context :context-name}], in the model's order."
  [model]
  (vec (concat
        (for [c (:containers model) ctx (:contexts c) comp (:components ctx)]
          (assoc comp :context (:id ctx) :context-name (or (:name ctx) (name (:id ctx)))))
        (for [comp (:kernel model)]
          (assoc comp :context :kernel :context-name "kernel")))))

(defn component-name
  "A component's name in the slices layout: its id's name part."
  [id] (name id))

(defn slice-folder? [s] (boolean (re-matches #"\d+-[A-Za-z0-9._-]+" (str s))))

(defn owner
  "The component owning a feature (or prose) file, the file relative to the spec folder."
  [layout model rel]
  (let [comps (components model)
        base (str/replace rel #"\.(feature|md)$" "")]
    (if (= layout :slices)
      (let [parts (str/split base #"/")]
        (when (and (= 2 (count parts)) (slice-folder? (first parts)))
          (let [hits (filter #(= (second parts) (component-name (:id %))) comps)]
            (when (= 1 (count hits)) (:id (first hits))))))
      (some #(when (= base (:spec %)) (:id %)) comps))))

;; ------------------------------------------------------------------ several specs

(defn spec-decls
  "The specs a configuration declares: its :specs, else one spec from :spec-dir."
  [cfg]
  (if-let [specs (seq (:specs cfg))]
    (vec (for [{:keys [name dir id-prefix]} specs]
           {:name name :dir dir :prefix (or id-prefix (:id-prefix cfg))
            :ledger (str dir "/ids.edn") :model (str dir "/model.edn") :events (str dir "/events.edn")
            :needs-file (str dir "/needs.edn") :glossary (str dir "/glossary.md") :domain (str dir "/domain.edn")
            :layout (:spec-layout cfg :contexts) :several? true}))
    [{:name nil :dir (:spec-dir cfg) :prefix (:id-prefix cfg) :ledger (:ledger cfg) :model (:model cfg)
      :events (:events cfg) :needs-file (:needs cfg) :glossary (str (:spec-dir cfg) "/glossary.md")
      :layout (:spec-layout cfg :contexts) :several? false}]))

(defn specs-problem
  "Why several specs cannot be read apart, or nil."
  [specs]
  (let [dup (fn [k] (some (fn [[v n]] (when (> n 1) v)) (frequencies (map k specs))))]
    (cond (dup :name) "a spec's name is declared once"
          (dup :id-prefix) "an id prefix is declared once, so no two scenarios share an id"
          (dup :dir) "a folder holds one spec")))

(defn validate-specs!
  "Refuses (exit 2) a :specs rdm-spec cannot read apart."
  [root cfg]
  (when-let [specs (seq (:specs cfg))]
    (when-not (and (vector? (:specs cfg)) (every? #(and (map? %) (string? (:name %)) (string? (:dir %))) specs))
      (project/cannot-run! ":specs is a vector of {:name :dir :id-prefix}"))
    (when-let [p (specs-problem specs)]
      (project/cannot-run! (str ":specs cannot be read apart: " p)))
    (doseq [{:keys [name dir]} specs]
      (when-not (fs/directory? (fs/path root dir))
        (project/cannot-run! (str "the spec " name "'s folder " dir " does not exist"))))))

;; ------------------------------------------------------------------ reading a spec

(defn- read-needs [root rel]
  (let [path (fs/path root rel)]
  (if-not (fs/exists? path)
    {:needs [] :needs-error {:line nil :message (str rel " does not exist")}}
    (let [text (slurp (str path))]
      (try (let [m (edn/read-string text)]
             (if (map? m)
               {:needs (vec (for [[k v] m] {:id (str k) :text (:text v) :sources (:sources v)
                                            :line (or (project/line-of text (pr-str (str k))) 1)}))
                :needs-text text}
               {:needs [] :needs-error {:line 1 :message "the needs are not a map"}}))
           (catch Exception e
             {:needs [] :needs-error {:line (or (some-> (re-find #"line (\d+)" (str (ex-message e))) second parse-long) 1)
                                      :message (project/one-line (ex-message e))}}))))))

(defn read-spec
  "Reads one spec declaration in root."
  [root decl]
  (let [dir (:dir decl)
        model-path (fs/path root (:model decl))
        model (read-model-lenient model-path)
        comps (components model)
        by-id (into {} (map (juxt :id identity) comps))
        files (filter #(str/ends-with? % ".feature") (project/files-under root dir))
        features
        (vec (for [f files]
               (let [relf (subs f (inc (count dir)))
                     parsed (parse-feature (slurp (str (fs/path root f))) f (:prefix decl))
                     own (when model (owner (:layout decl) model relf))
                     comp (by-id own)
                     slice (when (= :slices (:layout decl))
                             (let [parts (str/split relf #"/")] (when (and (= 2 (count parts)) (slice-folder? (first parts))) (first parts))))]
                 {:file f :rel relf :component own :slice slice :feature (:feature parsed) :refusals (:refusals parsed)
                  :scenarios (mapv #(cond-> (assoc % :context (:context comp) :component own
                                                   :context-name (:context-name comp)
                                                   :component-name (:name comp))
                                      slice (assoc :slice slice)
                                      (:name decl) (assoc :spec (:name decl)))
                                   (:scenarios parsed))})))
        needs (read-needs root (:needs-file decl))]
    (merge decl needs
           {:model model :model-present? (fs/exists? model-path) :features features
            :scenarios (vec (mapcat :scenarios features))
            :refusals (vec (mapcat :refusals features))})))

(defn read-specs
  "Reads every spec of the project in root: {:specs [...]}."
  [root cfg]
  {:specs (mapv #(read-spec root %) (spec-decls cfg))})

(defn spec-parsed-event
  "The SpecParsed event of a reading (events.edn)."
  [revision reading]
  (let [specs (:specs reading)]
    {:revision revision
     :scenarios (vec (for [s (mapcat :scenarios specs)]
                       (-> s
                           (select-keys [:id :ids :title :name :keyword :file :line :level :needs :wip? :outline? :rows
                                         :examples :background :rule :steps :context :component :context-name :component-name
                                         :slice :spec])
                           (update :steps #(mapv (fn [st] (select-keys st [:keyword :text])) %))
                           (update :background #(mapv (fn [st] (select-keys st [:keyword :text])) %)))))
     :needs (vec (for [n (mapcat :needs specs)] (select-keys n [:id :text :sources])))
     :refusals (vec (mapcat :refusals specs))}))

;; ------------------------------------------------------------------ the stage

(defn parse
  "check's stage: reads the working tree's spec and publishes SpecParsed. A
  construct outside the subset is refused, never skipped: each refusal is a finding."
  [run]
  (let [reading (read-specs (:root run) (:cfg run))
        event (spec-parsed-event :working-tree reading)]
    ((requiring-resolve 'rdm-spec.kernel.bus/publish!) :SpecParsed :spec/parser event)
    {:reading reading
     :findings (vec (for [r (:refusals event)] (project/finding (:file r) (:line r) (:rule r) (:message r))))}))

(defn slice-reach
  "The components a slice reaches: one per feature file named for it in the
  slice's folder, in the model's order. files are paths relative to the spec folder."
  [model rel-files slice]
  (let [names (set (for [f rel-files
                         :let [parts (str/split f #"/")]
                         :when (and (= 2 (count parts)) (= slice (first parts)) (str/ends-with? (second parts) ".feature"))]
                     (str/replace (second parts) #"\.feature$" "")))]
    (vec (for [c (components model) :when (names (component-name (:id c)))] (:id c)))))

(defn slices-of
  "The slice folders among rel-files, sorted."
  [rel-files]
  (vec (sort (distinct (for [f rel-files :let [parts (str/split f #"/")]
                             :when (and (> (count parts) 1) (slice-folder? (first parts)))]
                         (first parts))))))
