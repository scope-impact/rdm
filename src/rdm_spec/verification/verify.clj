(ns rdm-spec.verification.verify
  "Component Verify (verification context): a cycle's gate. The room's
  implementation, put into a fresh export of the cycle's cut in place of the
  cut's own, passes every check the cut's configuration declares, in order,
  the first that fails stopping it. Checks the builder does not control
  decide; each reports its budget and margin; a check that cannot run never
  passes. Records ratified.edn and publishes CycleVerified."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.plugins :as plugins]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]))

(def kinds #{:tests :serves :runs :plugin})

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "N#{id}"}}
   :evidence {}})

(def allure-elements #{:epic :feature :story :name :step :parameter :labels :links :evidence :suite :subSuite :parentSuite})
(def spec-elements #{:context :feature :rule :scenario :gherkin-step :example-row :scenario-id :component :gherkin-tags
                     :level :context-name :spec :slice :need})

(defn- now [] (System/currentTimeMillis))
(defn- secs [t0] (/ (Math/round (/ (- (now) t0) 10.0)) 100.0))

;; ------------------------------------------------------------------ what the cut declares

(defn check-name [c] (or (:name c) (name (:kind c))))

(defn validate-mapping! [m]
  (when-not (map? m) (project/cannot-run! ":allure is a map from an element of an Allure result to an element of the spec"))
  (doseq [[k v] m]
    (when-not (allure-elements k)
      (project/cannot-run! (str k " is not an element of an Allure result: the mapping maps " (str/join ", " (sort allure-elements)))))
    (when (keyword? v)
      (when-not (spec-elements v)
        (project/cannot-run! (str v " is not an element of the spec: the mapping maps to " (str/join ", " (sort spec-elements)))))))
  (doseq [[_ v] (:labels m) :when (keyword? v) :when (not (spec-elements v))]
    (project/cannot-run! (str v " is not an element of the spec")))
  (when-not (= :scenario-id (get-in m [:labels "spec"]))
    (project/cannot-run! "the mapping maps no label spec: the label spec, from the scenario's id, is required"))
  (when (and (contains? m :evidence) (not (map? (:evidence m))))
    (project/cannot-run! ":evidence is {level [attachment-name ...]}"))
  m)

(defn validate!
  "Refuses (exit 2), before anything runs, checks verify cannot trust."
  [cfg]
  (let [vs (:verify cfg)
        impl? (fn [w] (some (fn [i] (let [i (str/replace (str i) #"/+$" "") w (str/replace (str w) #"^\./" "")]
                                      (or (= w i) (str/starts-with? w (str i "/")))))
                            (:implementation cfg)))]
    (when (nil? vs) (project/cannot-run! "the configuration declares no :verify, so there is nothing to verify"))
    (when-not (and (sequential? vs) (every? map? vs)) (project/cannot-run! ":verify is a vector of checks"))
    (doseq [c vs :when (not (kinds (:kind c)))]
      (project/cannot-run! (str (pr-str (:kind c)) " is not a kind of check: the kinds are :tests, :serves, :runs and :plugin")))
    (when-not (= :tests (:kind (first vs)))
      (project/cannot-run! "the :verify declares no :tests check first: a project is verified by its tests first"))
    (doseq [[n c] (frequencies (map check-name vs)) :when (> c 1)]
      (project/cannot-run! (str "the check " n " is declared " c " times")))
    (doseq [c vs]
      (case (:kind c)
        :tests (when-not (and (sequential? (:command c)) (seq (:command c)))
                 (project/cannot-run! "the tests check names no command"))
        :runs (do (when-not (and (sequential? (:command c)) (seq (:command c)))
                    (project/cannot-run! (str "the :runs check " (check-name c) " names no command")))
                  (when-not (number? (get-in c [:budget :seconds]))
                    (project/cannot-run! (str "the :runs check " (check-name c) " names no :budget {:seconds S}"))))
        :serves (do (when-not (and (sequential? (:start c)) (integer? (:port c)) (sequential? (:probe c))
                                   (number? (get-in c [:budget :seconds])) (pos-int? (get-in c [:budget :polls])))
                      (project/cannot-run! (str "the :serves check " (check-name c) " is {:start [...] :port P :budget {:seconds S :polls K} :probe [...]}")))
                    (when (some impl? (:probe c))
                      (project/cannot-run! (str "the :serves check " (check-name c) " is refused: its probe " (pr-str (:probe c))
                                                " names a path of the implementation, and the probe is in the implementation the builder writes; it lives in a kept path"))))
        :plugin (when-not (some #(and (= (:name c) (:name %)) (= :Check (:answers %))) (plugins/declared cfg))
                  (project/cannot-run! (str "the :plugin check " (pr-str (check-name c)) " is refused: the check names no plugin answering :Check")))
        nil))
    (validate-mapping! (or (:allure cfg) default-mapping))))

;; ------------------------------------------------------------------ the checkout

(defn impl-paths [cfg] (vec (map #(str/replace (str %) #"/+$" "") (:implementation cfg))))

(defn checkout!
  "Exports the cut into BUILD, replaces its implementation with the room's,
  and returns the room's implementation tree."
  [cut build room cfg]
  (when (fs/exists? build) (fs/delete-tree build))
  (project/export! cut build)
  (doseq [i (impl-paths cfg)] (fs/delete-tree (project/abs build i)))
  (project/with-temp-dir [gd "rdm-room-"]
    (let [g (fn [& args] (apply project/git! room (str "--git-dir=" gd) (str "--work-tree=" room) args))
          present (filter #(let [f (project/abs room %)] (or (fs/exists? f) (fs/sym-link? f))) (impl-paths cfg))]
      (project/git! room "init" "-q" "--bare" gd)
      (when (seq present) (apply g "add" "-A" "--" present))
      (let [tree (str/trim (g "write-tree"))]
        (when (seq present)
          (g "checkout-index" "-a" "-f" (str "--prefix=" build "/")))
        tree))))

;; ------------------------------------------------------------------ holding results to the mapping

(defn ctx-name [sc] (cond (= :kernel (:context sc)) "kernel" (:context sc) (name (:context sc))))

(defn element-value [sc el]
  (case el
    :context (ctx-name sc)
    :context-name (:context-name sc)
    :feature (:feature-name sc)
    :rule (:rule sc)
    :scenario (:title sc)
    :scenario-id (:id sc)
    :component (:component-name sc)
    :level (:level sc)
    :spec (:spec sc)
    :slice (:slice sc)
    nil))

(defn- labels-of [raw n] (vec (keep #(when (= n (get % "name")) (get % "value")) (get raw "labels"))))

(defn- attachment-names [raw]
  (letfn [(walk [x] (concat (map #(get % "name") (get x "attachments")) (mapcat walk (get x "steps"))))]
    (set (walk raw))))

(defn mapping-findings
  "How the result R of the scenario SC breaks the mapping M."
  [m sc r needs-file]
  (when-not (:wip? sc)
    (let [raw (:raw r)
          row (when (:outline? sc) (first (filter #(= (:parameters r) (zipmap (:header %) (:cells %))) (:rows sc))))
          f (fn [rule msg] (project/finding (:file r) 1 rule (str (:id sc) ": " msg)))
          label-check (fn [lab el]
                        (let [want (element-value sc el) have (labels-of raw lab)]
                          (when (and want (not= [want] have))
                            [(f (str "mapping-differs: " lab) (str "its result's " lab " is " (pr-str (first have)) ", not " (pr-str want)))])))]
      (concat
       (for [k [:epic :feature :story :suite :subSuite :parentSuite]
             :let [el (get m k)] :when (keyword? el)
             x (label-check (name k) el)] x)
       (when (= :scenario (:name m))
         (let [title (:title sc) subst (when row (parser/substitute title (:values row)))]
           (when-not (some #{(get raw "name")} [title subst])
             [(f "mapping-differs: name" (str "its result's name is " (pr-str (get raw "name")) ", not the scenario's title " (pr-str title)))])))
       (when (= :gherkin-step (:step m))
         (let [want (mapv #(str (:keyword %) " " (:text %)) (if row (parser/row-steps sc row) (parser/scenario-steps sc)))
               have (mapv #(get % "name") (get raw "steps"))]
           (when-not (= want have)
             [(f "steps-differ-from-scenario" (str "its result's steps " (pr-str have) " are not the scenario's lines " (pr-str want)))])))
       (for [[lab el] (:labels m) :when (not= "spec" lab)
             x (if (= :gherkin-tags el)
                 (let [want (set (map #(subs % 1) (:tags sc))) have (set (labels-of raw lab))]
                   (when-not (= want have)
                     [(f (str "mapping-differs: " lab) (str "its result's " lab " labels are " (pr-str (sort have)) ", not the scenario's tags " (pr-str (sort want))))]))
                 (label-check lab el))]
         x)
       (when-let [{:keys [type url]} (get-in m [:links :need])]
         (let [links (get raw "links")
               missing (for [n (:needs sc)
                             :let [u (-> (str url) (str/replace "N" "\u0000") (str/replace "{id}" n) (str/replace "\u0000" needs-file))]
                             :when (not (some #(and (= type (get % "type")) (= u (get % "url"))) links))]
                         (str n " (" u ")"))]
           (when (seq missing)
             [(f "mapping-differs: links" (str "its result has no " type " link to " (str/join ", " missing)))])))
       (let [names (attachment-names raw)]
         (for [a (get-in m [:evidence (keyword (:level sc))]) :when (not (names a))]
           (f (str "evidence-missing: " a) (str "its result carries no attachment named " a ", which :evidence requires of @" (:level sc)))))))))

(defn results-findings
  "Every required scenario named by results that passed, and every result
  held to the mapping. {:findings :named}."
  [required all-scenarios rs mapping needs-of]
  (let [by-id (group-by :spec (filter :spec rs))
        by-sc (into {} (for [sc all-scenarios :when (:id sc)] [(:id sc) sc]))
        need-rows (fn [sc rs] (if (:outline? sc)
                                (for [row (:rows sc)
                                      :when (not (some #(and (= :passed (:status %)) (= (:parameters %) (zipmap (:header row) (:cells row)))) rs))]
                                  row)
                                (when-not (some #(= :passed (:status %)) rs) [nil])))]
    {:named (count (filter #(seq (get by-id (:id %))) required))
     :findings
     (vec (concat
           (for [sc required
                 :let [rs (get by-id (:id sc))
                       bad (remove #(= :passed (:status %)) rs)]
                 f (cond
                     (empty? rs) [(project/finding (:file sc) (:line sc) "scenario-unrun" (str (:id sc) " is named by no result"))]
                     (seq bad) (for [r bad] (project/finding (:file r) 1 "scenario-not-passed" (str (:id sc) "'s result is " (name (:status r)))))
                     :else (for [row (need-rows sc rs) :when row]
                             (project/finding (:file sc) (:line row) "scenario-unrun"
                                              (str (:id sc) "'s row " (pr-str (:cells row)) " is named by no passing result"))))]
             f)
           (for [r rs :let [sc (by-sc (:spec r))] :when sc
                 f (mapping-findings mapping sc r (needs-of sc))]
             f)))}))

;; ------------------------------------------------------------------ running commands

(defn start
  "Starts ARGV in DIR, its output to OUT (a file) or kept as a string."
  [argv dir & [{:keys [out]}]]
  (try
    (p/process (vec (map str argv)) (cond-> {:dir (str dir) :env (project/env) :in ""}
                                      out (assoc :out (io/file out) :err :out)
                                      (not out) (assoc :out :string :err :string)))
    (catch java.io.IOException e
      (project/cannot-run! (str "cannot start " (pr-str (vec argv)) ": " (project/one-line (ex-message e)))))))

(defn wait
  "Waits for PROC at most SECONDS (nil: no limit); stops it and its children past it."
  [proc seconds]
  (let [r (if seconds (deref proc (long (* 1000 seconds)) ::timeout) @proc)]
    (if (= r ::timeout)
      (do (p/destroy-tree proc) (assoc (deref proc 5000 {}) :timed-out? true))
      r)))

(defn- sub [argv k v] (mapv #(str/replace (str %) k (str v)) argv))

;; ------------------------------------------------------------------ the checks

(defn tests-check
  [{:keys [c build cut-cfg cut-read scope-specs results-dir results-rel log-rel]}]
  (let [t0 (now)
        rabs (project/abs results-dir)
        _ (do (fs/delete-tree rabs) (fs/create-dirs rabs))
        log (project/abs log-rel)
        proc (try (p/process (vec (:command c)) {:dir build :env (assoc (project/env) "RESULTS_DIR" rabs) :in ""
                                                 :out (io/file log) :err :out})
                  (catch java.io.IOException e
                    (project/cannot-run! (str "the check tests cannot run its command " (pr-str (:command c)) ": " (project/one-line (ex-message e))))))
        exit (:exit @proc)
        base {:name (check-name c) :kind :tests}]
    (cond
      (not= 0 exit)
      (let [why (str "the test command failed with exit status " exit "; its output is in " log-rel)]
        {:check (assoc base :outcome :failed :seconds (secs t0) :reason why)
         :findings [(project/finding log-rel "tests-failed" why)]})
      (empty? (fs/glob rabs "*-result.json"))
      (let [why (str "the test command exited 0 and wrote no Allure result into " results-rel ": a green exit with no results is not a run")]
        {:check (assoc base :outcome :failed :seconds (secs t0) :reason why)
         :findings [(project/finding results-rel "tests-no-results" why)]})
      :else
      (let [{rs :results unreadable :unreadable} (results/read-dir {:dir results-rel :abs rabs})
            all (vec (for [r cut-read sc (:scenarios r)] sc))
            required (vec (for [r cut-read :when (or (nil? scope-specs) (scope-specs (get-in r [:spec :name])))
                                sc (:scenarios r) :when (and (:id sc) (not (:wip? sc)))] sc))
            needs-of (let [m (into {} (for [r cut-read] [(get-in r [:spec :name]) (get-in r [:spec :needs])]))] #(get m (:spec %)))
            {:keys [findings named]} (results-findings required all rs (or (:allure cut-cfg) default-mapping) needs-of)
            findings (vec (concat (results/unreadable-findings unreadable) findings))]
        {:check (cond-> (assoc base :outcome (if (seq findings) :failed :passed) :seconds (secs t0) :scenarios named)
                  (seq findings) (assoc :reason (str (count findings) " finding" (when (> (count findings) 1) "s") " in the results: " (project/finding-line (first findings)))))
         :findings findings}))))

(defn runs-check [{:keys [c build]}]
  (let [t0 (now)
        budget (get-in c [:budget :seconds])
        proc (try (start (:command c) build)
                  (catch clojure.lang.ExceptionInfo _
                    (project/cannot-run! (str "the check " (check-name c) " cannot run: its command " (first (:command c)) " is not found"))))
        r (wait proc budget)
        s (secs t0)
        base {:name (check-name c) :kind :runs :seconds s :budget budget}
        expect (:expect c)
        first-line (first (str/split-lines (str (:out r))))
        fail (fn [why] {:check (assoc base :outcome :failed :reason why)
                        :findings [(project/finding (str "check " (check-name c)) "runs-failed" why)]})]
    (cond
      (:timed-out? r) (fail (str "the check " (check-name c) " gave no answer within the budget of " budget " seconds and was stopped"))
      (and (contains? expect :exit) (not= (:exit expect) (:exit r)))
      (fail (str "the check " (check-name c) " answered with the exit " (:exit r) " where " (:exit expect) " was expected"))
      (and (:stdout-starts expect) (not (str/starts-with? (str (:out r)) (:stdout-starts expect))))
      (fail (str "the check " (check-name c) "'s first line printed was " (pr-str first-line) ", not starting " (pr-str (:stdout-starts expect))))
      :else {:check (assoc base :outcome :passed :margin (/ (Math/round (* 100.0 (max 0 (- budget s)))) 100.0)) :findings []})))

(defn port-held? [port]
  (try
    (with-open [s (java.net.ServerSocket.)]
      (.setReuseAddress s true)
      (.bind s (java.net.InetSocketAddress. "127.0.0.1" (int port)))
      false)
    (catch java.io.IOException _ true)))

(defn answers?
  "Whether GET / on PORT returns any HTTP response within TIMEOUT-MS."
  [port timeout-ms]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int timeout-ms))
      (.setSoTimeout s (int timeout-ms))
      (let [o (.getOutputStream s)]
        (.write o (.getBytes "GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n"))
        (.flush o))
      (let [in (.getInputStream s)
            buf (byte-array 5)
            n (loop [n 0] (if (< n 5) (let [k (.read in buf n (- 5 n))] (if (neg? k) n (recur (+ n k)))) n))]
        (= "HTTP/" (String. buf 0 n))))
    (catch Exception _ false)))

(defn serves-check [{:keys [c build]}]
  (let [t0 (now)
        port (:port c)
        {:keys [seconds polls]} (:budget c)
        probe-secs (:probe-seconds c 30)]
    (when (port-held? port)
      (project/cannot-run! (str "the check " (check-name c) " cannot run: the port " port " is held by another process, and the gate measures only its own app")))
    (let [app (try (start (sub (:start c) "{port}" port) build)
                   (catch clojure.lang.ExceptionInfo _
                     (project/cannot-run! (str "the check " (check-name c) " cannot run: its start command " (first (:start c)) " is not found"))))
          step (/ (* 1000.0 seconds) polls)
          poll-timeout (long (max 100 (min 1000 (* 0.8 step))))]
      (try
        (let [answered (loop [i 1]
                         (when (<= i polls)
                           (let [due (+ t0 (long (* i step)))
                                 wait-ms (- due (now))]
                             (when (pos? wait-ms) (Thread/sleep wait-ms))
                             (if (answers? port poll-timeout) i (recur (inc i))))))
              base {:name (check-name c) :kind :serves :polls polls :budget seconds :probe-seconds probe-secs}
              fail (fn [why m] {:check (merge base {:outcome :failed :seconds (secs t0) :reason why} m)
                                :findings [(project/finding (str "check " (check-name c)) "serves-failed" why)]})]
          (if-not answered
            (fail (str "the app of " (check-name c) " gave no answer: no answer came within " seconds " seconds (" polls " polls)") {})
            (let [url (str "http://127.0.0.1:" port "/")
                  probe (try (start (sub (:probe c) "{url}" url) build)
                             (catch clojure.lang.ExceptionInfo _
                               (project/cannot-run! (str "the check " (check-name c) " cannot run: its probe " (first (:probe c)) " is not found"))))
                  r (wait probe probe-secs)
                  said (project/one-line (str (:out r) " " (:err r)))
                  m {:answered-on-poll answered :margin (- polls answered)}]
              (cond
                (:timed-out? r) (fail (str "the probe of " (check-name c) " timed out after " probe-secs " seconds and was stopped"
                                           (when-not (str/blank? said) (str "; it printed: " said))) m)
                (not= 0 (:exit r)) (fail (str "the probe of " (check-name c) " exited " (:exit r) ": the app is not this application"
                                              (when-not (str/blank? said) (str "; it printed: " said))) m)
                :else {:check (merge base {:outcome :passed :seconds (secs t0)} m) :findings []}))))
        (finally
          (p/destroy-tree app)
          (deref app 5000 nil))))))

(defn plugin-check [{:keys [c cut-cfg build cycle cut cycles-dir cut-read]}]
  (let [t0 (now)
        n (check-name c)
        plugin (first (filter #(and (= (:name c) (:name %)) (= :Check (:answers %))) (plugins/declared cut-cfg)))
        results-rel (str cycles-dir "/" cycle "/results/" n)
        rabs (project/abs results-rel)
        _ (do (fs/delete-tree rabs) (fs/create-dirs rabs))
        request {:request :Check :cycle cycle :cut cut :checkout (str cycles-dir "/" cycle "/build") :results results-rel}
        res (plugins/run-plugin plugin (pr-str request) build)
        base {:name n :kind :plugin}]
    (try
      (let [rep (plugins/answer-report plugin res)
            written? (seq (fs/glob rabs "*-result.json"))
            extra (when (and written? (empty? (:findings rep)))
                    (let [{rs :results unreadable :unreadable} (results/read-dir {:dir results-rel :abs rabs})
                          all (vec (for [r cut-read sc (:scenarios r)] sc))
                          required (filterv #(and (:id %) (not (:wip? %))) all)
                          needs-of (let [m (into {} (for [r cut-read] [(get-in r [:spec :name]) (get-in r [:spec :needs])]))] #(get m (:spec %)))]
                      (concat (results/unreadable-findings unreadable)
                              (:findings (results-findings required all rs (or (:allure cut-cfg) default-mapping) needs-of)))))
            fs (vec (concat (:findings rep) extra))]
        {:check (cond-> (assoc base :outcome (if (seq fs) :failed :passed) :seconds (secs t0))
                  (seq fs) (assoc :reason (str "the plugin " n " found " (count fs) ": " (project/finding-line (first fs)))))
         :findings fs
         :warnings (:warnings rep)})
      (catch clojure.lang.ExceptionInfo e
        (if (project/cannot-run? e)
          {:check (assoc base :outcome :refused :seconds (secs t0) :reason (ex-message e)) :cannot-run (ex-message e) :findings []}
          (throw e))))))

(defn run-check [ctx c]
  (case (:kind c)
    :tests (tests-check (assoc ctx :c c))
    :runs (runs-check (assoc ctx :c c))
    :serves (serves-check (assoc ctx :c c))
    :plugin (plugin-check (assoc ctx :c c))))

;; ------------------------------------------------------------------ the task

(defn scope-specs [scope]
  (let [entries (if (sequential? scope) scope [scope])
        names (keep #(when (map? %) (:spec %)) entries)]
    (when (seq names) (set names))))

(defn write-ratified! [file m]
  (fs/create-dirs (fs/parent (project/abs file)))
  (spit (project/abs file) (str (pr-str m) "\n")))

(defn verify-task [{:keys [cfg args]}]
  (let [[room-arg cycle] args
        cycles-dir (:cycles-dir cfg)
        cdir (str cycles-dir "/" cycle)
        cycle-file (str cdir "/cycle.edn")
        r (project/read-edn-file (project/root) cycle-file)]
    (when (:missing r) (project/cannot-run! (str "no " cycle-file ": a cycle is declared before it is verified")))
    (when (:error r) (project/cannot-run! (str cycle-file ":" (get-in r [:error :line]) ": the cycle is not readable EDN: " (get-in r [:error :message]))))
    (let [cyc (:value r)
          cut (:cut cyc)
          _ (when-not (and (string? cut) (project/resolve-commit cut))
              (project/cannot-run! (str cycle-file " names the cut " (pr-str cut) ", which this repository does not hold")))
          room (project/abs room-arg)
          _ (when-not (fs/directory? room) (project/cannot-run! (str "the room " room-arg " is not a directory")))
          _ (plugins/validate! cfg)
          gate (plugins/gate! cfg {:request :Gate :task :verify :cut cut :cycle cycle})]
      (if-not (:passed? gate)
        (project/report {} (:findings gate) (:warnings gate))
        (let [build-rel (str cdir "/build")
              build (project/abs build-rel)
              _ (when (fs/exists? build) (fs/delete-tree build))
              _ (project/export! cut build)
              cut-cfg (project/config build)
              _ (validate! cut-cfg)
              _ (plugins/validate! cut-cfg)
              tree (checkout! cut build room cut-cfg)
              cut-read (parser/read-specs build cut-cfg)
              unstamped (for [rd cut-read sc (:scenarios rd) :when (empty? (:ids sc))]
                          (project/finding (:file sc) (:line sc) "scenario-unstamped"
                                           (str "the cut " cut " holds a scenario with no id, \"" (:title sc) "\": it is not verified")))
              ratified-file (str cdir "/ratified.edn")
              record {:cut cut :room-tree tree :checkout build-rel}]
          (if (seq unstamped)
            (do (write-ratified! ratified-file (assoc record :outcome :refused :checks []
                                                      :reason (str "the cut holds an unstamped scenario: " (project/finding-line (first unstamped)))))
                (project/report {:verify {:cycle cycle :cut cut :outcome :refused :checks []}} unstamped))
            (let [ctx {:build build :cut-cfg cut-cfg :cut-read cut-read :scope-specs (scope-specs (:scope cyc))
                       :results-dir (str cdir "/results") :results-rel (str cdir "/results") :log-rel (str cdir "/test.log")
                       :cycle cycle :cut cut :cycles-dir cycles-dir}
                  outcome (loop [cs (:verify cut-cfg) done [] findings [] warnings []]
                            (if (empty? cs)
                              {:checks done :findings findings :warnings warnings}
                              (let [res (run-check ctx (first cs))
                                    done (conj done (:check res))]
                                (cond
                                  (:cannot-run res) (assoc {:checks (into done (map (fn [c] {:name (check-name c) :kind (:kind c) :outcome :not-run}) (rest cs)))
                                                            :findings findings :warnings warnings}
                                                           :cannot-run (:cannot-run res))
                                  (seq (:findings res)) {:checks (into done (map (fn [c] {:name (check-name c) :kind (:kind c) :outcome :not-run}) (rest cs)))
                                                         :findings (into findings (:findings res)) :warnings (into warnings (:warnings res))
                                                         :failed (:check res)}
                                  :else (recur (rest cs) done findings (into warnings (:warnings res)))))))
                  passed? (and (not (:cannot-run outcome)) (empty? (:findings outcome)))
                  rec (cond-> (assoc record :outcome (if passed? :passed :refused) :checks (:checks outcome))
                        (not passed?) (assoc :reason (or (:cannot-run outcome) (:reason (:failed outcome)))))]
              (write-ratified! ratified-file rec)
              (when (:cannot-run outcome)
                (project/cannot-run! (:cannot-run outcome)))
              (let [result {:verify {:cycle cycle :cut cut :outcome (:outcome rec) :checks (:checks outcome)}}]
                (if passed?
                  (do (bus/publish! :verification/verify :CycleVerified {:cycle cycle :cut cut :checkout build-rel :checks (:checks outcome)})
                      (let [subs (plugins/run-subscribers! cfg (filter #(= :CycleVerified (:event %)) (bus/published)))]
                        (project/report result (:findings subs) (concat (:warnings outcome) (:warnings subs)))))
                  (project/report result (:findings outcome) (:warnings outcome)))))))))))
