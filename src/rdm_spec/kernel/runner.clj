(ns rdm-spec.kernel.runner
  "The kernel component Gherkin runner (dhf/spec/kernel/runner.feature):
  runs the scenarios' steps against their step definitions and writes one
  Allure result per scenario, and per example row of an outline, in
  Allure's own model (glossary, \"Allure result\").

  The step API, a public contract for any project that runs `rdm-spec run`:

    (defstep #\"the spec holds (\\d+) features\" (fn [ctx n] ctx'))
        a step: its pattern matches the step's whole text; the function
        gets the context and each captured group, then the step's doc
        string or data table ([[cell]]) when it has one. What it returns
        is the next step's context; anything that is not a map leaves the
        context as it was. Each scenario starts with {}.
    (fail! msg)      fails the step: an assertion that does not hold
    (attach! name text [type])   evidence, an Allure attachment of the step

  A step's outcome: an exception from a Then (or an And or But after one)
  that is an assertion (`fail!`, an AssertionError, or an ex-info whose
  data holds a true key named failed or assertion) makes the result
  failed; anything else that goes wrong makes it broken, as do a step no
  definition matches and one two definitions match (naming both). The
  steps after it are skipped. A scenario tagged @wip is not run: its
  result is skipped and labelled planned.

  Discovered, never listed: `plan` finds every feature file under the spec
  directory by looking. A run that finds none is refused.

  The task `run [--results DIR]`: results go to --results, else
  RESULTS_DIR, else the configured :results-dir; step definitions are the
  .clj files under the configured :steps-dir. Exit 1 names every scenario
  not tagged @wip whose result did not pass.

  API for test.clj: plan, step-files, load-step-files!, run-pickles!,
  shadow-warnings; default-mapping (the Allure mapping rdm-spec holds a
  result to when a project declares no :allure, spec-0333)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as g]))

(def default-mapping
  "Which element of the spec each Allure element carries (spec-0333)."
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}}
   :evidence {}})

;; ---------------------------------------------------------------------------
;; The step API

(defonce ^:private registry (atom {:defs [] :shadows []}))

(defn defstep
  "Defines a step by its pattern and its function (see the namespace doc).
  The same pattern defined again by the same file replaces it; defined by
  another file, both stay (a step both match is broken) and the second is
  a warning."
  [pattern f]
  (let [file (some-> *file* str)
        src (str pattern)]
    (swap! registry
           (fn [{:keys [defs] :as r}]
             (let [same (first (filter #(= src (:source %)) defs))]
               (cond
                 (nil? same) (update r :defs conj {:pattern pattern :source src :f f :file file})
                 (= file (:file same)) (assoc r :defs (mapv #(if (= src (:source %)) (assoc % :pattern pattern :f f) %) defs))
                 :else (-> r
                           (update :defs conj {:pattern pattern :source src :f f :file file})
                           (update :shadows conj {:source src :file file :first (:file same)}))))))
    nil))

(defn fail!
  "Fails the step: what it asserts does not hold."
  [msg]
  (throw (ex-info (str msg) {::assertion true})))

(def ^:dynamic *attachments* nil)

(defn attach!
  "Attaches `content` to the running step as evidence named `nm`."
  ([nm content] (attach! nm content "text/plain"))
  ([nm content type]
   (when *attachments* (swap! *attachments* conj {:name (str nm) :content (str content) :type (str type)}))
   nil))

(defn shadow-warnings
  "A warning for each pattern defined by two files."
  []
  (vec (for [{:keys [source file first]} (:shadows @registry)]
         (command/warning file "step-defined-twice"
                          (str "the step pattern " (pr-str source) " is defined here and in " first
                               ": a step both match is broken")))))

(defn step-files
  "Every .clj file under the step directory `dir`, sorted."
  [dir]
  (if (fs/directory? dir)
    (vec (sort (map str (fs/glob dir "**.clj"))))
    []))

(defn load-step-files!
  "Loads the step definition files `files`, in order."
  [files]
  (doseq [f files] (load-file (str f))))

;; ---------------------------------------------------------------------------
;; Pickles: the runnable scenarios

(defn- spec-path [spec-dir file]
  (let [d (str (str/replace (str spec-dir) #"/+$" "") "/")]
    (when (str/starts-with? file d)
      (str/replace (subs file (count d)) #"\.feature$" ""))))

(defn owners-of
  "{spec-path {:context :component :component-id}} of the model `m`: the
  context's id, the component's name, the component's id without its colon;
  the kernel's context is kernel."
  [m]
  (into {}
        (concat
         (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x)
               comp (:components x) :when (and (map? comp) (:spec comp))]
           [(:spec comp) {:context (some-> (:id x) name) :component (:name comp)
                          :component-id (some-> (:id comp) str (subs 1))}])
         (for [comp (:kernel m) :when (and (map? comp) (:spec comp))]
           [(:spec comp) {:context "kernel" :component (:name comp) :component-id (some-> (:id comp) str (subs 1))}]))))

(defn step-name
  "A step as a result names it: its keyword and text."
  [{:keys [keyword text]}]
  (str keyword " " text))

(defn effective-keywords
  "The keyword each step of `steps` counts as: And and But take the one before."
  [steps]
  (vec (rest (reductions (fn [prev {:keys [keyword]}] (if (#{"And" "But"} keyword) (or prev keyword) keyword))
                         nil steps))))

(defn- row-steps [steps values]
  (vec (for [st steps]
         (cond-> (update st :text g/substitute values)
           (:doc-string st) (update :doc-string g/substitute values)
           (:data-table st) (update :data-table (fn [t] (mapv (fn [r] (mapv #(g/substitute % values) r)) t)))))))

(defn pickles-of
  "The pickles of the feature file `file` (relative to the project), parsed
  as `parsed`: one per scenario, one per example row of an outline."
  [file parsed {:keys [spec-dir id-prefix owners]}]
  (let [id-re (re-pattern (str "^@(" (java.util.regex.Pattern/quote (str id-prefix)) "-\\d{4,})$"))
        owner (get owners (spec-path spec-dir file))]
    (vec
     (for [sc (:scenarios parsed)
           :let [tags (vec (:all-tags sc))
                 ids (vec (keep #(second (re-matches id-re %)) (:tags sc)))
                 base {:file file :line (:line sc) :name (:name sc) :tags tags :ids ids
                       :id (when (= 1 (count ids)) (first ids))
                       :wip? (boolean (some #{"@wip"} tags))
                       :rule (get-in sc [:rule :name])
                       :feature (get-in parsed [:feature :name])
                       :needs (vec (distinct (keep #(second (re-matches #"^@(UN-\d+)$" %)) tags)))
                       :context (:context owner) :component (:component owner) :component-id (:component-id owner)}
                 steps (vec (concat (:background sc) (:steps sc)))]
           row (if (= "Scenario Outline" (:keyword sc))
                 (for [ex (:examples sc) r (:rows ex)] {:line (:line r) :header (:header ex) :cells (:cells r)})
                 [nil])]
       (if row
         (let [values (zipmap (:header row) (:cells row))]
           (assoc base :steps (row-steps steps values)
                  :row {:line (:line row) :values values}
                  :parameters (mapv vector (:header row) (:cells row))
                  :display-name (str (:name sc) " " (pr-str (vec (:cells row))))))
         (assoc base :steps steps :display-name (:name sc)))))))

(defn history-id
  "The pickle's history: its id, and for an outline row the row's values,
  joined by |."
  [p]
  (let [base (or (:id p) (str (:file p) ":" (:name p)))]
    (str/join "|" (cons base (map second (:parameters p))))))

(defn- under? [rel only]
  (let [o (str/replace (str only) #"/+$" "")]
    (or (= rel o) (str/starts-with? rel (str o "/")))))

(defn plan
  "Every pickle of the project `root`'s spec: {:pickles :refusals :features},
  limited to the feature files under the paths `only` when any are given."
  [root {:keys [spec-dir id-prefix model only]}]
  (let [spec-dir (str/replace (str (or spec-dir "dhf/spec")) #"/+$" "")
        d (fs/file root spec-dir)
        files (if (fs/directory? d)
                (vec (sort (for [f (fs/glob d "**.feature") :when (fs/regular-file? f)]
                             (str/replace (str (fs/relativize root f)) "\\" "/"))))
                [])
        only (seq (map #(let [p (str %)] (if (fs/absolute? p) (str (fs/relativize root p)) p)) only))
        files (if only (filterv (fn [f] (some #(under? f %) only)) files) files)
        m (let [f (fs/file root (or model "dhf/spec/model.edn"))]
            (or (when (fs/regular-file? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil))) {}))
        owners (owners-of (if (map? m) m {}))
        parsed (for [f files] [f (g/parse-feature f (slurp (str (fs/file root f))))])]
    {:pickles (vec (mapcat (fn [[f p]] (pickles-of f p {:spec-dir spec-dir :id-prefix (or id-prefix "spec") :owners owners}))
                           parsed))
     :refusals (vec (mapcat (comp :refusals second) parsed))
     :features (count files)}))

;; ---------------------------------------------------------------------------
;; Running

(defn matches
  "The definitions of `defs` whose pattern matches the whole `text`, each
  with what it captured: [{:def :captured}]."
  [defs text]
  (vec (for [d defs
             :let [m (re-matches (:pattern d) text)]
             :when m]
         {:def d :captured (if (vector? m) (vec (rest m)) [])})))

(defn assertion?
  "Whether `e` says an assertion did not hold."
  [e]
  (or (instance? AssertionError e)
      (boolean (some (fn [[k v]] (and (keyword? k) (#{"failed" "assertion"} (name k)) v)) (ex-data e)))))

(defn outcome
  "The status of a step of the keyword `kw` that threw `e`."
  [kw e]
  (if (and (= "Then" kw) (assertion? e)) "failed" "broken"))

(defn- trace-of [^Throwable e]
  (let [sw (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. sw))
    (str sw)))

(defn- def-name [d] (str "#\"" (:source d) "\" (" (or (:file d) "?") ")"))

(defn- run-steps
  "Runs the pickle's steps: {:status :status-details :steps [step-result]}."
  [p]
  (let [defs (:defs @registry)
        kws (effective-keywords (:steps p))]
    (loop [[[st kw] & more] (map vector (:steps p) kws) ctx {} out [] status "passed" details nil]
      (if (nil? st)
        {:status status :status-details details :steps out}
        (let [nm (step-name st)
              start (System/currentTimeMillis)]
          (if (not= "passed" status)
            (recur more ctx (conj out {:name nm :status "skipped" :stage "finished" :start start :stop start :attachments []})
                   status details)
            (let [ms (matches defs (:text st))
                  atts (atom [])
                  [st-status msg e ctx']
                  (cond
                    (empty? ms) ["broken" (str "the step \"" nm "\" matches no step definition") nil ctx]
                    (< 1 (count ms)) ["broken" (str "the step \"" nm "\" matches more than one step definition: "
                                                     (str/join " and " (map (comp def-name :def) ms)))
                                      nil ctx]
                    :else
                    (let [{:keys [def captured]} (first ms)
                          extra (cond (:doc-string st) [(:doc-string st)] (:data-table st) [(:data-table st)] :else [])]
                      (try
                        (let [r (binding [*attachments* atts] (apply (:f def) ctx (concat captured extra)))]
                          ["passed" nil nil (if (map? r) r ctx)])
                        (catch Throwable t
                          [(outcome kw t) (str "the step \"" nm "\" " (if (= "failed" (outcome kw t)) "failed" "broke")
                                               ": " (or (ex-message t) (.getName (class t))))
                           t ctx]))))
                  stop (System/currentTimeMillis)
                  sd (when msg (cond-> {:message msg} e (assoc :trace (trace-of e))))]
              (recur more ctx'
                     (conj out (cond-> {:name nm :status st-status :stage "finished" :start start :stop stop
                                        ::attachments @atts}
                                 sd (assoc :statusDetails sd)))
                     st-status sd))))))))

(defn result-of
  "The Allure result of the pickle `p` run as `run` ({:status :start :stop
  :steps :status-details :needs-rel})."
  [p {:keys [status start stop steps status-details needs-rel uuid]}]
  (let [label (fn [n v] {:name n :value (str v)})
        needs-rel (or needs-rel "dhf/spec/needs.edn")]
    (cond-> {:uuid (or uuid (str (random-uuid)))
             :historyId (history-id p)
             :name (:name p)
             :fullName (str (:file p) ":" (:name p))
             :status status
             :stage "finished"
             :start start :stop stop
             :steps (vec steps)
             :parameters (mapv (fn [[n v]] {:name n :value v}) (:parameters p))
             :labels (vec (concat
                           (when (:feature p) [(label "feature" (:feature p))])
                           (when (:rule p) [(label "story" (:rule p))])
                           (when (:context p) [(label "epic" (:context p)) (label "suite" (:context p))])
                           (when (:component-id p) [(label "subSuite" (:component-id p))])
                           (for [t (:tags p)] (label "tag" (subs t 1)))
                           [(label "framework" "gherkin")]
                           (when (:id p) [(label "spec" (:id p))])
                           (when (:context p) [(label "context" (:context p))])
                           (when (:component p) [(label "component" (:component p))])
                           (when (:wip? p) [(label "planned" "true")])))
             :links (vec (for [n (:needs p)] {:type "issue" :name n :url (str needs-rel "#" n)}))
             :attachments []}
      status-details (assoc :statusDetails status-details))))

(defn- write-attachments!
  "Writes each step's evidence beside the result; the steps as Allure has them."
  [dir steps]
  (vec (for [st steps]
         (-> st
             (dissoc ::attachments)
             (assoc :attachments
                    (vec (for [{:keys [name content type]} (::attachments st)
                               :let [src (str (random-uuid) "-attachment" (if (= "text/plain" type) ".txt" ""))]]
                           (do (spit (str (fs/file dir src)) content)
                               {:name name :source src :type type}))))))))

(defn run-pickle!
  "Runs the pickle `p`, writes its result into `dir`: the result."
  [p dir {:keys [needs-rel]}]
  (let [start (System/currentTimeMillis)
        {:keys [status status-details steps]}
        (if (:wip? p)
          {:status "skipped" :status-details {:message "planned (tagged @wip): not run"}
           :steps (for [st (:steps p)] {:name (step-name st) :status "skipped" :stage "finished" :start start :stop start})}
          (run-steps p))
        steps (write-attachments! dir steps)
        r (result-of p {:status status :start start :stop (System/currentTimeMillis) :steps steps
                        :status-details status-details :needs-rel needs-rel})]
    (spit (str (fs/file dir (str (:uuid r) "-result.json"))) (json/generate-string r {:pretty true}))
    r))

(defn run-pickles!
  "Runs every pickle, writing each result into the directory `out`;
  `on-result` is called with each pickle and its result. [[pickle result]]."
  ([pickles out on-result] (run-pickles! pickles out on-result {}))
  ([pickles out on-result opts]
   (fs/create-dirs out)
   (vec (for [p pickles]
          (let [r (run-pickle! p (str out) opts)]
            (when on-result (on-result p r))
            [p r])))))

;; ---------------------------------------------------------------------------
;; The task

(defn run
  "The run task's stage: `rdm-spec run [--results DIR]`."
  [req]
  (let [root (:root req)
        spec-dir (command/config-value req :spec-dir)
        {:keys [pickles refusals features]} (plan root {:spec-dir spec-dir :id-prefix (command/config-value req :id-prefix)
                                                        :model (command/config-value req :model)})]
    (when (zero? features)
      (command/cannot-run! (str "no scenario was found: " spec-dir " holds no feature file, and a run that finds"
                                " nothing is refused")))
    (let [given (or (not-empty (get-in req [:opts :results])) (not-empty (get (:env req) "RESULTS_DIR"))
                    (command/config-value req :results-dir))
          out (command/path req given)
          steps-dir (command/path req (command/config-value req :steps-dir))]
      (when (fs/directory? steps-dir)
        (when-let [add (requiring-resolve 'babashka.classpath/add-classpath)]
          (add (str (fs/parent steps-dir)))))
      (swap! registry assoc :shadows [])
      (load-step-files! (step-files steps-dir))
      (let [runs (run-pickles! pickles out nil {:needs-rel (command/config-value req :needs)})
            counts (frequencies (map (comp :status second) runs))]
        {:result {:run {:results given :scenarios (count runs) :counts counts}}
         :findings (vec (concat
                         (for [f refusals] (command/finding (:file f) (:line f) (:rule f) (:message f)))
                         (for [[p r] runs :when (and (not (:wip? p)) (not= "passed" (:status r)))]
                           (command/finding (:file p) (or (get-in p [:row :line]) (:line p)) (str "scenario-" (:status r))
                                            (str "\"" (:display-name p) "\" " (:status r) ": "
                                                 (get-in r [:statusDetails :message]))))
                         (shadow-warnings)))}))))
