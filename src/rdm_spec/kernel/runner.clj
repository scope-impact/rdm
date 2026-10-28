(ns rdm-spec.kernel.runner
  "Kernel component Gherkin runner: runs scenarios against step definitions
  written with one small API, and writes one Allure result per scenario (one
  per row of an outline), in Allure's own model, labelled with what it verifies.

  The step API:
    (defstep #\"pattern\" (fn [ctx & captured] ctx'))
    (attach! name content) or (attach! name content type)
    (fail! message), or clojure's assert, for a Then that does not hold.
  The running step's doc string and data table are (doc-string) and (data-table)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]))

;; ------------------------------------------------------------------ the step API

(defonce steps (atom {}))

(defn register!
  "Registers a step definition; WHERE names it (file:line). Loading a file
  again replaces its definitions rather than doubling them."
  [pattern f where]
  (swap! steps assoc [(str pattern) where] {:pattern pattern :fn f :where where})
  nil)

(defmacro defstep [pattern f]
  `(register! ~pattern ~f ~(str (or *file* "?") ":" (:line (meta &form)))))

(def ^:dynamic *step* nil)
(def ^:dynamic *attachments* nil)
(def ^:dynamic *results-dir* nil)

(defn doc-string [] (:docstring *step*))
(defn data-table [] (:table *step*))

(defn fail!
  "Says that what a Then asserts does not hold."
  [message]
  (throw (ex-info (str message) {::assertion true})))

(defn attach!
  "Attaches CONTENT as evidence named NAME to the running step."
  ([name content] (attach! name content "text/plain"))
  ([name content type]
   (let [ext (case type "text/plain" "txt" "application/json" "json" "text/html" "html" "image/png" "png" "txt")
         source (str (random-uuid) "-attachment." ext)]
     (when *results-dir*
       (fs/create-dirs *results-dir*)
       (if (bytes? content)
         (with-open [o (java.io.FileOutputStream. (str (fs/path *results-dir* source)))] (.write o ^bytes content))
         (spit (str (fs/path *results-dir* source)) (str content))))
     (when *attachments* (swap! *attachments* conj {:name name :source source :type type}))
     nil)))

(defn registry
  "A registry from [[pattern fn where] ...], as the loaded step files make one."
  [defs]
  (into {} (for [[p f w] defs] [[(str p) w] {:pattern p :fn f :where w}])))

(defn load-steps!
  "Loads every step definition file under DIR, in the order of their names."
  [dir]
  (reset! steps {})
  (when (fs/directory? dir)
    (doseq [f (sort (map str (fs/glob dir "**.clj")))]
      (load-file f)))
  @steps)

;; ------------------------------------------------------------------ running one example

(defn- now [] (System/currentTimeMillis))

(defn- trace-of [^Throwable e]
  (let [sw (java.io.StringWriter.)] (.printStackTrace e (java.io.PrintWriter. sw)) (str sw)))

(defn- assertion? [^Throwable e]
  (or (instance? AssertionError e) (boolean (::assertion (ex-data e)))))

(defn- matches [reg text]
  (vec (keep (fn [[_ d]] (when-let [m (re-matches (:pattern d) text)]
                           (assoc d :captured (if (string? m) [] (vec (rest m))))))
             (sort-by key reg))))

(defn- step-name [st] (str (:keyword st) " " (:text st)))

(defn run-example
  "Runs one SCENARIO (one ROW of an outline, or nil) against the registry REG;
  returns the Allure result's body, without labels and links."
  [reg scenario row {:keys [results-dir]}]
  (let [start (now)
        steps (if row (parser/row-steps scenario row) (parser/scenario-steps scenario))]
    (binding [*results-dir* results-dir]
      (loop [sts steps ctx {} done [] status "passed" details nil primary nil]
        (if (empty? sts)
          {:status status :statusDetails details :stage "finished" :start start :stop (now) :steps done}
          (let [st (first sts)
                kw (if (#{"And" "But"} (:keyword st)) (or primary (:keyword st)) (:keyword st))]
            (if (not= status "passed")
              (recur (rest sts) ctx (conj done {:name (step-name st) :status "skipped" :stage "finished"
                                                :start (now) :stop (now) :steps [] :attachments [] :parameters []})
                     status details kw)
              (let [s0 (now)
                    atts (atom [])
                    ms (matches reg (:text st))
                    [st-status st-details ctx']
                    (cond
                      (empty? ms) ["broken" {:message (str "no step definition matches: " (step-name st))} ctx]
                      (> (count ms) 1) ["broken" {:message (str "the step " (step-name st) " is matched by more than one definition: "
                                                                (str/join " and " (map #(str (pr-str (str (:pattern %))) " at " (:where %)) ms)))}
                                        ctx]
                      :else
                      (let [{f :fn captured :captured} (first ms)]
                        (try
                          (let [r (binding [*step* st *attachments* atts] (apply f ctx captured))]
                            ["passed" nil (if (map? r) r ctx)])
                          (catch Throwable e
                            [(if (and (assertion? e) (= "Then" kw)) "failed" "broken")
                             {:message (str (step-name st) ": " (or (ex-message e) (str (class e)))) :trace (trace-of e)}
                             ctx]))))
                    step-result (cond-> {:name (step-name st) :status st-status :stage "finished" :start s0 :stop (now)
                                         :steps [] :attachments @atts :parameters []}
                                  st-details (assoc :statusDetails st-details))]
                (recur (rest sts) ctx' (conj done step-result) st-status st-details kw)))))))))

;; ------------------------------------------------------------------ results

(defn labels
  "The labels of a scenario's result: Allure's own, and rdm-spec's."
  [sc]
  (let [ctx (cond (= :kernel (:context sc)) "kernel" (:context sc) (name (:context sc)))
        comp-id (some-> (:component sc) (#(str (namespace %) "/" (name %))))]
    (vec (concat
          [{:name "feature" :value (:feature-name sc)}]
          (when (:rule sc) [{:name "story" :value (:rule sc)}])
          (when ctx [{:name "epic" :value ctx} {:name "suite" :value ctx}])
          (when comp-id [{:name "subSuite" :value comp-id}])
          (for [t (:tags sc)] {:name "tag" :value (subs t 1)})
          [{:name "framework" :value "gherkin"}]
          (when (:id sc) [{:name "spec" :value (:id sc)}])
          (when ctx [{:name "context" :value ctx}])
          (when (:component-name sc) [{:name "component" :value (:component-name sc)}])
          (when (:wip? sc) [{:name "planned" :value "true"}])))))

(defn links [sc needs-file]
  (vec (for [n (:needs sc)] {:type "issue" :name n :url (str needs-file "#" n)})))

(defn result-of
  "The whole Allure result of running SC (one ROW of an outline, or nil)."
  [reg sc row {:keys [needs-file] :as opts}]
  (let [uuid (str (random-uuid))
        body (if (:wip? sc)
               (let [t (now)]
                 {:status "skipped" :statusDetails {:message "planned: the scenario is tagged @wip and is not run"}
                  :stage "finished" :start t :stop t
                  :steps (vec (for [st (if row (parser/row-steps sc row) (parser/scenario-steps sc))]
                                {:name (step-name st) :status "skipped" :stage "finished" :start t :stop t
                                 :steps [] :attachments [] :parameters []}))})
               (run-example reg sc row opts))
        history (if row
                  (str/join "|" (cons (or (:id sc) (:title sc)) (:cells row)))
                  (or (:id sc) (str (:file sc) ":" (:title sc))))]
    (cond-> (merge {:uuid uuid
                    :historyId history
                    :testCaseId history
                    :name (:title sc)
                    :fullName (str (:file sc) ":" (:title sc))
                    :labels (labels sc)
                    :links (links sc needs-file)
                    :parameters (if row (vec (for [[h v] (map vector (:header row) (:cells row))] {:name h :value v})) [])
                    :attachments []}
                   body)
      (nil? (:statusDetails body)) (dissoc :statusDetails))))

(defn write-result! [results-dir r]
  (fs/create-dirs results-dir)
  (spit (str (fs/path results-dir (str (:uuid r) "-result.json"))) (json/generate-string r)))

;; ------------------------------------------------------------------ running a project

(defn- under? [file paths]
  (or (empty? paths)
      (some (fn [p] (let [p (str/replace p #"/+$" "")]
                      (or (= file p) (str/starts-with? file (str p "/")))))
            paths)))

(defn run-project
  "Runs every scenario of the project at ROOT, found by looking, against the
  step definitions under STEPS-DIR, writing one result per scenario (per row)
  into RESULTS-DIR. Returns the counts, :total 0 when no scenario was found."
  [{:keys [root steps-dir results-dir paths after-scenario on-result]}]
  (let [cfg (project/config root)
        read (binding [project/*root* root] (parser/read-specs root cfg))
        reg (load-steps! steps-dir)
        scenarios (for [r read sc (:scenarios r) :when (under? (:file sc) paths)]
                    [sc (get-in r [:spec :needs])])]
    (reduce (fn [acc [sc needs-file]]
              (reduce (fn [acc row]
                        (let [r (try (result-of reg sc row {:results-dir results-dir :needs-file needs-file})
                                     (finally (when after-scenario (after-scenario))))]
                          (write-result! results-dir r)
                          (when on-result (on-result r))
                          (-> acc (update :total inc) (update (keyword (:status r)) (fnil inc 0)))))
                      acc
                      (if (:outline? sc) (:rows sc) [nil])))
            {:total 0 :passed 0 :failed 0 :broken 0 :skipped 0}
            scenarios)))
