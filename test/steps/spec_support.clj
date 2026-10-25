(ns steps.spec-support
  "Fixtures for the spec context's step definitions (spec_check, spec_parser,
  spec_stamper): a copy of the room's spec with one component's feature file
  replaced by a fixture, and minimal projects for the stamper."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [steps.support :as s]))

(def check-stages
  "Every namespace `rdm-spec check` runs: a scenario that expects the whole
  check to pass cannot be verified while one is not built."
  ['rdm-spec.knowledge.sources 'rdm-spec.spec.check 'rdm-spec.spec.parser
   'rdm-spec.architecture.lint 'rdm-spec.architecture.views])

(defn spec-file [dir spec-path] (str (fs/file dir "dhf/spec" (str spec-path ".feature"))))

(defn- spec-files [dir]
  (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))))

(defn ids-in [text] (set (re-seq #"@spec-\d+" text)))

(defn features!
  "Replaces the feature files of the spec paths (e.g. \"spec/parser\") in
  the project `dir` with the texts of `fixtures`, {spec-path text}. Any id the
  fixtures use that another feature file of the spec also carries is moved
  off that file, to an id far above the ledger, so the fixtures' ids are the
  only ones of their kind. Returns the files' paths relative to `dir`."
  [dir fixtures]
  (let [fs* (set (for [[sp text] fixtures]
                   (let [f (spec-file dir sp)] (spit f text) (str (fs/absolutize f)))))
        used (set (mapcat ids-in (vals fixtures)))
        counter (atom 9000)]
    (doseq [g (spec-files dir)
            :when (not (contains? fs* (str (fs/absolutize g))))
            :let [t (slurp g)
                  hit (filter #(str/includes? t %) used)]
            :when (seq hit)]
      (spit g (reduce (fn [t id] (str/replace t (re-pattern (str id "(?!\\d)"))
                                              (str "@spec-" (swap! counter inc))))
                      t hit)))
    (mapv #(str "dhf/spec/" % ".feature") (keys fixtures))))

(defn feature!
  "features! for one fixture; returns its file's path relative to `dir`."
  [dir spec-path text]
  (first (features! dir {spec-path text})))

(defn fixture-feature
  "A feature file holding one scenario under a rule. Options:
  :feature-tags :name :tags :description (extra description lines),
  :leading (comment lines before the feature, to place the scenario)."
  [{:keys [feature-tags name tags description leading feature-name]
    :or {feature-tags "@UN-002" name "A fixture scenario" feature-name "Parser"}}]
  (str (str/join (map #(str % "\n") leading))
       (when (seq feature-tags) (str feature-tags "\n"))
       "Feature: " feature-name "\n"
       "  A fixture of the spec context's tests.\n"
       (str/join (map #(str "  " % "\n") description))
       "\n"
       "  Rule: The fixture's rule\n\n"
       (when (seq tags) (str "    " tags "\n"))
       "    Scenario: " name "\n"
       "      Given a fixture\n"
       "      When the fixture runs\n"
       "      Then the fixture holds\n"))

(defn scenario-line
  "The line of the Scenario named `name` in `text`."
  [text name]
  (some (fn [[i l]] (when (= (str/trim l) (str "Scenario: " name)) (inc i)))
        (map-indexed vector (str/split-lines text))))

(defn room!
  "A copy of the room's spec, with every view generated when the views are
  built (so a check of the copy can pass)."
  []
  (s/copy-of-room!))

(defn generate-views!
  "Generates the views in `dir`, when the views are built, so the views'
  freshness check can pass."
  [dir]
  (when (s/built? 'rdm-spec.architecture.views)
    (s/rdm-spec! dir "views"))
  dir)

(defn ensure-room!
  "`ctx`'s project, a copy of the room's spec when it has none yet (or when
  the one it has holds no spec: the allow-listed paths are copied in, its
  configuration kept)."
  [ctx]
  (let [dir (or (:dir ctx) (room!))]
    (when-not (fs/exists? (fs/file dir "dhf/spec/model.edn"))
      (let [cfg (when (fs/exists? (fs/file dir "dhf/harness/config.edn"))
                  (slurp (str (fs/file dir "dhf/harness/config.edn"))))
            room (room!)]
        (doseq [a (get-in (s/room-config) [:clean-room :allow])
                :let [src (fs/file room a)]
                :when (fs/exists? src)]
          (if (fs/directory? src)
            (fs/copy-tree src (fs/file dir a) {:replace-existing true})
            (do (fs/create-dirs (fs/parent (fs/file dir a)))
                (fs/copy src (fs/file dir a) {:replace-existing true}))))
        (when cfg (spit (str (fs/file dir "dhf/harness/config.edn")) cfg))))
    (assoc ctx :dir dir)))

(defn update-edn!
  "Rewrites the EDN file `rel` under `dir` as (f value)."
  [dir rel f]
  (let [file (fs/file dir rel)
        v (edn/read-string (slurp (str file)))]
    (spit (str file) (pr-str (f v)))))

(defn config! [dir f] (update-edn! dir "dhf/harness/config.edn" f))

(defn needs! [dir f] (update-edn! dir "dhf/spec/needs.edn" f))

(defn finding-lines
  "The lines of the run's error stream that are findings (no event lines)."
  [run]
  (vec (remove #(str/starts-with? % "event: ") (s/err-lines run))))

(defn line-with
  "The first error line containing every one of `parts`, or nil."
  [run & parts]
  (first (filter (fn [l] (every? #(str/includes? l %) parts)) (finding-lines run))))

(defn fails-naming!
  "Checks the run exited 1 and one finding line names every one of `parts`."
  [ctx & parts]
  (let [r (:run ctx)]
    (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
    (s/check! (apply line-with r parts)
              (str "no finding names all of " (pr-str parts) "\n" (s/show r)))))

(defn result
  "The run's standard output read as EDN, or nil."
  [run]
  (try (edn/read-string (:out run)) (catch Exception _ nil)))

;; --- minimal projects, for the stamper ---------------------------------------

(defn project!
  "A temporary project holding only the room's configuration (merged with
  `overrides`) and an empty spec directory."
  ([] (project! {}))
  ([overrides]
   (let [dir (s/project! overrides)]
     (fs/create-dirs (fs/file dir "dhf/spec"))
     dir)))

(defn ledger-file [dir] (str (fs/file dir "dhf/spec/ids.edn")))

(defn write-ledger! [dir n]
  (spit (ledger-file dir) (str ";; The id ledger.\n{:issued " n "}\n")))

(defn ledger [dir]
  (when (fs/exists? (ledger-file dir))
    (:issued (edn/read-string (slurp (ledger-file dir))))))

(defn scenarios-feature
  "A feature file holding scenarios [[name tags] ...]."
  [scs]
  (str "@UN-001\nFeature: Demo\n  A feature for the stamper's tests.\n\n"
       (str/join "\n"
                 (for [[name tags] scs]
                   (str (when (seq tags) (str "  " tags "\n"))
                        "  Scenario: " name "\n"
                        "    Given a step\n"
                        "    Then another\n")))))

(defn tags-of
  "The own tags of the scenario named `name` in any feature file under dir's
  spec, or nil when there is none."
  [dir name]
  (first (for [f (spec-files dir)
               sc (:scenarios (gherkin/parse-feature f (slurp f)))
               :when (= name (:name sc))]
           (:tags sc))))

(defn spec-snapshot
  "{relative path content} of every file under dhf/spec of `dir` (the shape
  the status steps snapshot in, :spec-before)."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

(defn mtimes
  "{path mtime} of every file under the spec directory."
  [dir]
  (into {} (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
             [(str f) (str (fs/last-modified-time f))])))

;; --- steps the spec shares with other contexts' features -----------------------

(defn ensure-shared-steps!
  "Defines nothing: \"it passes\" and \"no file in the spec changes\", which
  the spec context's features share with other contexts', are the kernel's
  (steps.kernel-command), defined once when its step file loads. Kept so the
  step files that still call it load."
  []
  nil)
