(ns rdm-spec.verification.verify
  "Verify (dhf/spec/verification/verify.feature): the gate. The room's
  implementation is put into a fresh export of the cycle's cut, and every
  check the cut's configuration declares runs there, in order, the first
  that fails stopping it. It records what it verified in ratified.edn."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; What the configuration declares

(def kinds #{:tests :serves :runs})

(defn- under-implementation? [impl word]
  (some #(let [e (str/replace % #"/$" "")] (or (= word e) (str/starts-with? word (str e "/")))) impl))

(defn verify-problem
  "Why verify cannot trust the configuration's :verify, or nil."
  [checks impl]
  (cond
    (nil? checks) "the configuration declares no :verify"
    (not (and (sequential? checks) (seq checks) (every? map? checks))) "the configuration's :verify is not a vector of checks"
    :else
    (or (first (for [c checks :when (not (kinds (:kind c)))]
                 (str (pr-str (:kind c)) " is not a kind of check: the kinds are :tests, :serves and :runs")))
        (when-not (= :tests (:kind (first checks)))
          "a project is verified by its tests first: the configuration declares no :tests check before its others")
        (first (for [c checks :when (and (= :tests (:kind c)) (empty? (:command c)))]
                 "the tests check names no command: set its :command"))
        (first (for [c checks :when (and (= :runs (:kind c)) (empty? (:command c)))]
                 (str "the check " (:name c "runs") " names no command")))
        (first (for [c checks :when (= :serves (:kind c))
                     :when (or (empty? (:start c)) (not (integer? (:port c))) (empty? (:probe c))
                               (not (pos? (get-in c [:budget :seconds] 0))) (not (pos? (get-in c [:budget :polls] 0))))]
                 (str "the :serves check " (:name c "serves") " needs :start, :port, :budget {:seconds :polls} and :probe")))
        (first (for [c checks :when (= :serves (:kind c)) w (:probe c) :when (under-implementation? impl (str w))]
                 (str "the probe " w " of the check " (:name c "serves")
                      " is in the implementation the builder writes: a probe lives in a kept path"))))))

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(def allure-elements #{:epic :feature :story :name :step :parameter :labels :links :evidence :suite :sub-suite :full-name :description})
(def spec-elements #{:context :feature :rule :scenario :gherkin-step :example-row :component :scenario-id :gherkin-tags :level :need})

(defn allure-problem
  "Why verify cannot read the mapping, or nil."
  [m]
  (cond
    (not (map? m)) "the configuration's :allure is not a map"
    :else
    (or (first (for [k (keys m) :when (not (allure-elements k))]
                 (str k " is not an element of an Allure result")))
        (first (for [[k v] m :when (and (not (#{:labels :links :evidence} k)) (not (spec-elements v)))]
                 (str (pr-str v) " is not an element of the spec (mapped from " k ")")))
        (first (for [[_ v] (:labels m) :when (not (spec-elements v))]
                 (str (pr-str v) " is not an element of the spec (a label)")))
        (when-not (= :scenario-id (get-in m [:labels "spec"]))
          "the label spec, from the scenario's id, is required in the mapping's :labels"))))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))
(defn margin [budget seconds] (max 0.0 (round2 (- budget seconds))))
(defn- since [t0] (round2 (/ (- (System/nanoTime) t0) 1e9)))

(defn- rev-ok? [root rev] (= 0 (:exit (cmd/git root "rev-parse" "-q" "--verify" (str rev "^{commit}")))))

(defn- impl-files
  "Every file of the implementation paths under dir, relative."
  [dir impl]
  (sort
   (for [e impl
         :let [p (fs/path dir e)]
         :when (fs/exists? p)
         f (if (fs/directory? p) (filter fs/regular-file? (fs/glob p "**" {:hidden true})) [p])]
     (str (fs/relativize dir f)))))

(defn room-tree
  "The git tree hash of the room's implementation paths."
  [room impl]
  (let [gd (cmd/temp-dir "rdm-spec-tree-")]
    (try
      (let [env {"GIT_DIR" (str gd) "GIT_WORK_TREE" (str room) "GIT_INDEX_FILE" (str (fs/path gd "index"))}
            g (fn [& a] (let [r (apply cmd/sh {:dir (str room) :env env} "git" a)]
                          (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "git " (first a) " failed: " (:err r))))
                          (str/trim (:out r))))]
        (let [r (cmd/sh {:dir (str gd)} "git" "init" "-q" "--bare" (str gd))]
          (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "git init failed: " (:err r)))))
        (let [files (impl-files room impl)]
          (when (seq files)
            (apply g "add" "-f" "--" files)))
        (g "write-tree"))
      (finally (fs/delete-tree gd)))))

;; ---------------------------------------------------------------------------
;; The mapping: does a result carry what its scenario says?

(defn- label-values [j n] (keep #(when (= n (get % "name")) (get % "value")) (get j "labels")))

(defn- element-value [s element row]
  (case element
    :context (some-> (:context s) name)
    :feature (:feature s)
    :rule (:rule s)
    :scenario (:title s)
    :component (:component-name s)
    :scenario-id (:id s)
    :level (some-> (:level s) name)
    :example-row row
    nil))

(defn- substitute [text row]
  (reduce-kv (fn [t k v] (str/replace t (str "<" k ">") v)) text (or row {})))

(defn mapping-findings
  "The findings about one result of a stamped scenario that is not planned."
  [mapping s r row]
  (let [j (:json r)
        f (fn [rule what] (cmd/finding (:file s) (:line s) rule (str what " (" (:file r) ")")))
        lbl {:epic "epic" :feature "feature" :story "story" :suite "suite" :sub-suite "subSuite"}]
    (vec
     (concat
      (for [[k el] mapping
            :when (lbl k)
            :let [want (element-value s el row) got (label-values j (lbl k))]
            :when (not= (if want [want] []) (vec got))]
        (f "mapping-differs" (str (name k) ": " (:id s) "'s result holds the " (lbl k) " "
                                  (if (seq got) (pr-str (first got)) "nothing")
                                  " where the scenario's " (name el) " is " (pr-str want))))
      (when-let [el (:name mapping)]
        (let [want (element-value s el row)]
          (when (not= want (get j "name"))
            [(f "mapping-differs" (str "name: " (:id s) "'s result is named " (pr-str (get j "name")) " where the scenario is " (pr-str want)))])))
      (when (= :gherkin-step (:step mapping))
        (let [want (mapv #(str (:keyword %) " " (substitute (:text %) row)) (concat (:background s) (:steps s)))
              got (mapv #(get % "name") (get j "steps"))]
          (when (not= want got)
            [(f "steps-differ-from-scenario"
                (str (:id s) "'s result has the steps " (pr-str got) " where the scenario's lines are " (pr-str want)))])))
      (when (and (= :example-row (:parameter mapping)) (:outline? s) row)
        (let [got (into {} (map (fn [p] [(get p "name") (str (get p "value"))]) (get j "parameters")))]
          (when (not= row got)
            [(f "mapping-differs" (str "parameter: " (:id s) "'s result holds the parameters " (pr-str got) " where its row is " (pr-str row)))])))
      (for [[label el] (:labels mapping)
            :when (not= "spec" label)
            :let [got (set (label-values j label))]
            :when (case el
                    :gherkin-tags (not (every? got (:tags s)))
                    (let [want (element-value s el row)] (and want (not (got want)))))]
        (f "mapping-differs" (str "labels: " (:id s) "'s result lacks the label " label " the scenario's " (name el) " gives")))
      (when-let [{:keys [type url]} (get-in mapping [:links :need])]
        (let [links (set (map (juxt #(get % "type") #(get % "url")) (get j "links")))]
          (for [n (:needs s)
                :let [u (str/replace (str url) "{id}" n)]
                :when (not (links [type u]))]
            (f "mapping-differs" (str "links: " (:id s) "'s result has no " type " link to " n " (" u ")")))))
      (let [names (set (concat (map #(get % "name") (get j "attachments"))
                               (mapcat (fn [st] (map #(get % "name") (get st "attachments"))) (get j "steps"))))]
        (for [a (get-in mapping [:evidence (:level s)]) :when (not (names a))]
          (f "evidence-missing" (str a ": " (:id s) "'s result carries no attachment named " a))))))))

;; ---------------------------------------------------------------------------
;; The checks

(defn- subst [words m] (mapv (fn [w] (reduce-kv (fn [w k v] (str/replace w (str "{" k "}") (str v))) (str w) m)) words))

(defn- not-found! [check command]
  (cmd/cannot-run! (str "the check " (:name check) " cannot run: the command " (first command) " was not found")))

(defn run-tests-check
  [{:keys [root build cycle-dir check scenarios mapping]}]
  (let [t0 (System/nanoTime)
        results-dir (fs/path root cycle-dir "results")
        log (str cycle-dir "/test.log")
        _ (do (fs/delete-tree results-dir) (fs/create-dirs results-dir))
        r (apply cmd/sh {:dir (str build) :env {"RESULTS_DIR" (str (fs/absolutize results-dir))}} (:command check))
        _ (when (:not-found r) (not-found! check (:command check)))
        _ (spit (fs/file root log) (str (:out r) (:err r)))
        seconds (since t0)
        base {:name (:name check) :kind :tests}
        files (fs/glob results-dir "*-result.json")]
    (cond
      (not= 0 (:exit r))
      {:check (assoc base :outcome :failed :seconds seconds)
       :findings [(cmd/finding log nil "tests-failed"
                               (str "the test command failed with the exit status " (:exit r) "; its output is in " log))]}
      (empty? files)
      {:check (assoc base :outcome :failed :seconds seconds)
       :findings [(cmd/finding (str cycle-dir "/results") nil "tests-no-results"
                               "the test command exited 0 and wrote no Allure result: a green exit with no results is not a run")]}
      :else
      (let [{rs :results unreadable :unreadable} (results/read-dir root (str cycle-dir "/results"))
            stamped (filter :id scenarios)
            by-id (group-by :spec rs)
            checked (for [s stamped :when (not (:wip? s))] [s (by-id (:id s))])
            findings (vec
                      (concat
                       (results/unreadable-findings unreadable)
                       (for [[s mine] checked :when (empty? mine)]
                         (cmd/finding (:file s) (:line s) "scenario-untested" (str (:id s) " is named by no result: every scenario is tested")))
                       (for [[s mine] checked r mine :when (not= "passed" (:status r))]
                         (cmd/finding (:file s) (:line s) "scenario-not-passed"
                                      (str (:id s) "'s result is " (:status r) " (" (:file r) ")")))
                       (for [[s mine] checked r mine
                             :let [row (when (:outline? s)
                                         (let [pm (into {} (map (fn [p] [(get p "name") (str (get p "value"))]) (:parameters r)))]
                                           (first (filter #(= % pm) (for [ex (:examples s) rw (:rows ex)]
                                                                      (zipmap (:headings ex) (map str (:cells rw))))))))]
                             x (mapping-findings mapping s r row)]
                         x)))]
        {:check (assoc base :outcome (if (seq findings) :failed :passed) :seconds seconds
                       :scenarios (count (filter (fn [[_ mine]] (seq mine)) checked)))
         :findings findings}))))

(defn run-runs-check [{:keys [build check]}]
  (let [budget (get-in check [:budget :seconds] 60)
        t0 (System/nanoTime)
        r (apply cmd/sh {:dir (str build) :timeout-ms (long (* 1000 budget))} (:command check))
        _ (when (:not-found r) (not-found! check (:command check)))
        seconds (since t0)
        base {:name (:name check) :kind :runs :seconds seconds :budget budget :margin (margin budget seconds)}
        {:keys [exit stdout-starts]} (:expect check)
        f (fn [msg] {:check (assoc base :outcome :failed)
                     :findings [(cmd/finding nil nil "check-failed" (str "the check " (:name check) " " msg))]})]
    (cond
      (:timed-out r) (f (str "gave no answer within its budget of " budget " seconds"))
      (and (some? exit) (not= exit (:exit r))) (f (str "answered with the exit " (:exit r) " where " exit " was expected"))
      (and stdout-starts (not (str/starts-with? (str (:out r)) stdout-starts)))
      (f (str "printed " (pr-str (first (str/split-lines (str (:out r))))) " first, where its output was expected to start "
              (pr-str stdout-starts)))
      :else {:check (assoc base :outcome :passed)})))

(defn port-held? [port]
  (try (with-open [s (java.net.ServerSocket.)]
         (.setReuseAddress s true)
         (.bind s (java.net.InetSocketAddress. (int port)))
         false)
       (catch java.io.IOException _ true)))

(defn answers?
  "True when GET / on the port returns any HTTP response."
  [port timeout-ms]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int timeout-ms))
      (.setSoTimeout s (int timeout-ms))
      (let [out (.getOutputStream s)]
        (.write out (.getBytes "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n"))
        (.flush out)
        (let [buf (byte-array 5) n (.read (.getInputStream s) buf)]
          (and (pos? n) (str/starts-with? (String. buf 0 (int n)) "HTTP")))))
    (catch Exception _ false)))

(defn run-serves-check [{:keys [root build cycle-dir check]}]
  (let [{:keys [port budget]} check
        {:keys [seconds polls]} budget
        url (str "http://127.0.0.1:" port "/")
        start (subst (:start check) {"port" port "url" url})
        probe (subst (:probe check) {"port" port "url" url})
        t0 (System/nanoTime)
        log (fs/file root cycle-dir (str (:name check) ".log"))
        proc (try (p/process start {:dir (str build) :out log :err log})
                  (catch java.io.IOException _ (not-found! check start)))
        interval (/ (* 1000.0 seconds) polls)
        base {:name (:name check) :kind :serves :budget seconds :polls polls}]
    (try
      (let [answered (loop [i 1]
                       (when (<= i polls)
                         (let [due (+ t0 (* i interval 1e6)) wait (- due (System/nanoTime))]
                           (when (pos? wait) (Thread/sleep (long (/ wait 1e6))))
                           (if (answers? port (long (max 50 (min 1000 (* 0.8 interval)))))
                             i
                             (recur (inc i))))))]
        (if-not answered
          {:check (assoc base :outcome :failed :seconds (since t0) :margin 0)
           :findings [(cmd/finding nil nil "check-failed"
                                   (str "the check " (:name check) ": no answer came within " seconds " seconds (" polls " polls of GET / on port " port ")"))]}
          (let [pr (apply cmd/sh {:dir (str build) :timeout-ms (long (* 1000 seconds))} probe)
                _ (when (:not-found pr) (not-found! check probe))
                c (assoc base :seconds (since t0) :answered-on-poll answered :margin (- polls answered))]
            (if (= 0 (:exit pr))
              {:check (assoc c :outcome :passed)}
              {:check (assoc c :outcome :failed)
               :findings [(cmd/finding nil nil "check-failed"
                                       (str "the check " (:name check) ": the app answered, and its probe exited " (:exit pr)
                                            ", so it is not this application; the probe said: " (str/trim (str (:out pr) " " (:err pr)))))]}))))
      (finally
        (p/destroy-tree proc)
        (deref proc 5000 nil)))))

;; ---------------------------------------------------------------------------
;; The stages

(defn prepare-stage
  "Reads the cycle before anything else, then the cut's configuration, and
  refuses what verify cannot trust before anything runs."
  [{:keys [root config args]}]
  (let [[room-arg cycle] args
        cycles (:cycles-dir config "dhf/cycles")
        cycle-dir (str cycles "/" cycle)
        cycle-file (str cycle-dir "/cycle.edn")
        decl (or (try (cmd/read-edn (cmd/read-at root nil cycle-file)) (catch Exception _ nil))
                 (cmd/cannot-run! (str "the cycle " cycle " has no readable " cycle-file)))
        cut (or (:cut decl) (cmd/cannot-run! (str cycle-file " names no :cut")))
        _ (when-not (rev-ok? root cut) (cmd/cannot-run! (str "the cut " cut " that " cycle-file " names does not exist")))
        cut-config (or (try (cmd/read-edn (cmd/read-at root cut "dhf/harness/config.edn")) (catch Exception _ nil))
                       (cmd/cannot-run! (str "the cut " cut " holds no readable dhf/harness/config.edn")))
        room (fs/absolutize (fs/path root room-arg))]
    (when-let [why (verify-problem (:verify cut-config) (:implementation cut-config []))] (cmd/cannot-run! why))
    (when-let [why (allure-problem (get cut-config :allure default-mapping))] (cmd/cannot-run! why))
    (when-not (fs/directory? room) (cmd/cannot-run! (str "the room " room-arg " does not exist")))
    {:ctx {:revision cut :config cut-config
           ::verify {:cycle cycle :cycle-dir cycle-dir :cut cut :room room}}}))

(defn stage [{:keys [root config] :as ctx}]
  (let [{:keys [cycle cycle-dir cut room]} (::verify ctx)
        impl (:implementation config [])
        tree (room-tree room impl)
        {:keys [scenarios refusals]} (bus/latest :SpecParsed)
        unstamped (filter #(empty? (:ids %)) scenarios)
        ratified (fs/file root cycle-dir "ratified.edn")
        record! (fn [m] (fs/create-dirs (fs/parent ratified)) (spit ratified (with-out-str (clojure.pprint/pprint m))))]
    (if (or (seq refusals) (seq unstamped))
      (let [fs (vec (concat refusals
                            (for [s unstamped]
                              (cmd/finding (:file s) (:line s) "scenario-unstamped"
                                           (str "the cut " cut " holds the unstamped scenario \"" (:title s) "\": it is not verified")))))]
        (record! {:cut cut :room-tree tree :outcome :refused :checks []
                  :reason (str "the spec at " cut " holds a scenario with no id or a construct the parser refuses")})
        {:findings fs :result {:verify {:cycle cycle :cut cut :outcome :refused :checks []}}})
      (let [build (fs/path root cycle-dir "build")
            build-rel (str cycle-dir "/build")
            tar (cmd/temp-file ".tar")]
        ;; the checkout: an export of the cut, its implementation replaced by the room's
        (fs/delete-tree build)
        (fs/create-dirs build)
        (try
          (let [a (cmd/sh {:dir root} "git" "archive" "--format=tar" "-o" (str tar) cut)]
            (when-not (= 0 (:exit a)) (cmd/cannot-run! (str "git archive failed: " (:err a)))))
          (let [x (cmd/sh {:dir (str build)} "tar" "-x" "-f" (str tar))]
            (when-not (= 0 (:exit x)) (cmd/cannot-run! (str "tar failed: " (:err x)))))
          (finally (fs/delete-if-exists tar)))
        (doseq [e impl] (fs/delete-tree (fs/path build e)))
        (doseq [f (impl-files room impl)]
          (fs/create-dirs (fs/parent (fs/path build f)))
          (fs/copy (fs/path room f) (fs/path build f) {:replace-existing true :copy-attributes true}))
        (let [mapping (get config :allure default-mapping)
              checks (:verify config)
              named (fn [c] (assoc c :name (or (:name c) (name (:kind c)))))
              run (loop [cs (map named checks) out [] findings []]
                    (if-let [c (first cs)]
                      (let [{:keys [check] :as r}
                            (case (:kind c)
                              :tests (run-tests-check {:root root :build build :cycle-dir cycle-dir :check c
                                                       :scenarios scenarios :mapping mapping})
                              :runs (run-runs-check {:build build :check c})
                              :serves (do (when (port-held? (:port c))
                                            (cmd/cannot-run! (str "the port " (:port c) " of the check " (:name c)
                                                                  " is held by another process: verify measures no other process")))
                                          (run-serves-check {:root root :build build :cycle-dir cycle-dir :check c})))
                            findings (into findings (:findings r))]
                        (if (= :passed (:outcome check))
                          (recur (rest cs) (conj out check) findings)
                          {:checks (into (conj out check) (map (fn [x] {:name (:name x) :kind (:kind x) :outcome :not-run}) (rest cs)))
                           :findings findings :failed check}))
                      {:checks out :findings findings}))
              outcome (if (:failed run) :refused :passed)]
          (record! (cond-> {:cut cut :room-tree tree :outcome outcome :checkout build-rel :checks (:checks run)}
                     (:failed run) (assoc :reason (str "the check " (:name (:failed run)) " failed: "
                                                       (str/join "; " (map :message (:findings run)))))))
          {:findings (:findings run)
           :result {:verify {:cycle cycle :cut cut :outcome outcome :checks (:checks run)}}})))))
