(ns rdm-spec.verification.verify
  "Verify (dhf/spec/verification/verify.feature): a cycle's gate. The room's
  implementation, put in place of the implementation of a fresh export of the
  cut, passes every check the cut's configuration declares (:verify), in
  order, the first that fails stopping it: :tests (the test command passes
  and its Allure results name and pass every scenario, each held to the
  project's Allure mapping), :serves (the app starts fresh, answers within
  its budget and passes a probe kept outside the implementation), :runs (a
  command answers as expected within its budget) and :plugin (the project's
  own judge, answering :Check). Records ratified.edn, and dhf/cycles/NN/build
  is the checkout the grader grades. The kinds are closed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.kernel.plugins :as plugins]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; The declared checks

(def kinds [:tests :serves :runs :plugin])

(defn- under? [path entry]
  (let [e (str/replace entry #"/$" "")]
    (or (= path e) (str/starts-with? path (str e "/")))))

(defn- in-implementation? [config words]
  (first (filter (fn [w] (some #(under? w %) (:implementation config))) words)))

(defn checks
  "The configuration's checks, each with its name, or a refusal saying why
  verify cannot trust them."
  [config]
  (let [cs (:verify config)]
    (when (nil? cs) (cmd/cannot-run! "the configuration declares no :verify: verify runs the checks a project declares"))
    (when-not (sequential? cs) (cmd/cannot-run! ":verify is a vector of checks"))
    (when-not (= :tests (:kind (first cs)))
      (cmd/cannot-run! "the configuration's :verify declares no :tests check first: a project is verified by its tests first"))
    (doseq [c cs]
      (let [k (:kind c)]
        (when-not (some #{k} kinds)
          (cmd/cannot-run! (str (pr-str k) " is not a kind of check: the kinds are :tests, :serves, :runs and :plugin")))
        (case k
          :tests (when-not (seq (:command c)) (cmd/cannot-run! "the tests check names no command"))
          :runs (when-not (seq (:command c)) (cmd/cannot-run! (str "the check " (:name c "runs") " names no command")))
          :serves (do (when-not (and (seq (:start c)) (integer? (:port c)) (seq (:probe c))
                                     (pos? (get-in c [:budget :seconds] 0)) (pos? (get-in c [:budget :polls] 0)))
                        (cmd/cannot-run! (str "the check " (:name c "serves") " needs :start, :port, :budget {:seconds :polls} and :probe")))
                      (when-let [w (in-implementation? config (:probe c))]
                        (cmd/cannot-run! (str "the check " (:name c "serves") "'s probe " w
                                              " is under the implementation's paths: the probe is in the implementation the builder writes"))))
          :plugin (when-not (plugins/answering config (:name c) :Check)
                    (cmd/cannot-run! (str "the check " (pr-str (:name c)) " names no plugin answering :Check"
                                          ": the check names no plugin answering :Check in :plugins"))))))
    (mapv #(merge {:name (name (:kind %))} %) cs)))

;; ---------------------------------------------------------------------------
;; The Allure mapping

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(def allure-elements #{:epic :feature :story :suite :subSuite :name :fullName :step :parameter :labels :links :evidence})

(def spec-elements #{:context :component :feature :rule :scenario :scenario-id :gherkin-step :example-row
                     :gherkin-tags :level :need})

(defn mapping
  "The project's Allure mapping, rdm-spec's default where it declares none,
  or a refusal naming what it cannot read."
  [config]
  (let [m (merge default-mapping (:allure config))
        spec-el! (fn [v] (when-not (spec-elements v)
                           (cmd/cannot-run! (str (pr-str v) " is not an element of the spec: the Allure mapping maps "
                                                 "an Allure element to one of " (str/join ", " (sort spec-elements))))))]
    (doseq [[k v] m]
      (when-not (allure-elements k)
        (cmd/cannot-run! (str (pr-str k) " is not an element of an Allure result: the mapping's keys are "
                              (str/join ", " (sort allure-elements)))))
      (when-not (#{:labels :links :evidence} k) (spec-el! v)))
    (doseq [[_ v] (:labels m)] (spec-el! v))
    (when-not (= :scenario-id (get (:labels m) "spec"))
      (cmd/cannot-run! "the mapping maps no label \"spec\" to the scenario's id: the label spec, from the scenario's id, is required"))
    m))

(defn- subst [text row] (reduce (fn [t [h v]] (str/replace t (str "<" h ">") (str v))) text row))

(defn expected-steps
  "The steps a scenario's result holds, by name: its background and its own,
  an outline's filled from the row its parameters name."
  [s params]
  (let [row (into {} (map (fn [p] [(get p "name") (str (get p "value"))]) params))]
    (mapv #(subst (str (:keyword %) " " (:text %)) row) (concat (:background s) (:steps s)))))

(defn- spec-value [s el]
  (case el
    :context (some-> (:context s) name)
    :component (:component-name s)
    :feature (:feature s)
    :rule (:rule s)
    :scenario (:name s)
    :scenario-id (:id s)
    :level (some-> (:level s) name)
    nil))

(defn- label-values [j n] (vec (keep #(when (= n (get % "name")) (get % "value")) (get j "labels"))))

(defn- attachments [j]
  (concat (map #(get % "name") (get j "attachments"))
          (mapcat attachments (get j "steps"))))

(defn mapping-findings
  "How one result breaks the mapping for its scenario, each a finding about
  the result's file."
  [m s j file]
  (let [id (:id s)
        f (fn [rule msg] (cmd/finding file nil rule msg))
        differs (fn [el msg] (f "mapping-differs" (str el ": " id "'s result " msg)))
        label-el (fn [k] (let [want (spec-value s (get m k)) got (label-values j (name k))]
                           (when-not (= (if want [want] []) got)
                             [(differs (name k) (str "has the " (name k) " " (pr-str got) ", not " (pr-str want)))])))
        all-tags (set (concat (:feature-tags s) (:rule-tags s) (:tags s)))]
    (vec
     (concat
      (when (= :scenario (:name m))
        (when-not (= (:name s) (get j "name"))
          [(differs "name" (str "is named " (pr-str (get j "name")) ", not " (pr-str (:name s))))]))
      (when (= :gherkin-step (:step m))
        (let [want (expected-steps s (get j "parameters"))
              got (mapv #(get % "name") (get j "steps"))]
          (when-not (= want got)
            [(f "steps-differ-from-scenario"
                (str id " ran the steps " (pr-str got) ", not the scenario's " (pr-str want)))])))
      (mapcat label-el (filter #(get m %) [:epic :feature :story :suite :subSuite]))
      (for [[label el] (:labels m)
            :let [got (label-values j label)
                  ok (if (= :gherkin-tags el)
                       (= all-tags (set got))
                       (= (if-let [v (spec-value s el)] [v] []) got))]
            :when (not ok)]
        (differs "labels" (str "has the label " label " " (pr-str got) ", not what the scenario's "
                               (name el) " says")))
      (when-let [{:keys [type url]} (get-in m [:links :need])]
        (for [n (:needs s)
              :let [u (str/replace url "{id}" n)]
              :when (not-any? #(and (= type (get % "type")) (= u (get % "url"))) (get j "links"))]
          (differs "links" (str "has no " type " link to " n " (" u "), the need it traces to"))))
      (for [a (get-in m [:evidence (:level s)])
            :when (not (some #{a} (attachments j)))]
        (f "evidence-missing" (str a ": " id "'s result carries no attachment " a
                                   ", which the mapping's :evidence requires at the level " (name (:level s)))))))))

;; ---------------------------------------------------------------------------
;; Measuring

(defn- round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn poll-margin [polls answered-on] (- polls answered-on))

(defn runs-margin [budget seconds] (round2 (max 0.0 (- budget seconds))))

(defn port-free?
  "True when the port can be bound now: a port held only by the closing
  connections of an app that has gone is free."
  [port]
  (try
    (with-open [s (java.net.ServerSocket.)]
      (.setReuseAddress s true)
      (.bind s (java.net.InetSocketAddress. (int port)))
      true)
    (catch java.io.IOException _ false)))

(defn answers?
  "True when GET / on the port returns an HTTP response within ms, whatever
  its status; an accepted connection with no response is no answer."
  [port ms]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int ms))
      (.setSoTimeout s (int ms))
      (let [o (.getOutputStream s)]
        (.write o (.getBytes (str "GET / HTTP/1.0\r\nHost: 127.0.0.1:" port "\r\n\r\n")))
        (.flush o))
      (let [in (.getInputStream s)
            buf (byte-array 5)
            n (loop [n 0]
                (if (< n 5)
                  (let [r (.read in buf n (- 5 n))] (if (neg? r) n (recur (+ n r))))
                  n))]
        (= "HTTP/" (String. buf 0 (int n) "US-ASCII"))))
    (catch Exception _ false)))

(defn- now [] (System/currentTimeMillis))

(defn- seconds-since [t0] (round2 (/ (- (now) t0) 1000.0)))

(defn- start [cmd dir]
  (try (p/process (mapv str cmd) {:dir (str dir) :out :string :err :string})
       (catch java.io.IOException _ nil)))

(defn- stop! [proc]
  (when proc (p/destroy-tree proc) (deref proc 5000 nil)))

(defn- run-for
  "Runs cmd in dir for at most seconds: {:exit :out :err} or :timed-out or :not-found."
  [cmd dir seconds]
  (if-let [pr (start cmd dir)]
    (let [r (deref pr (long (* 1000 seconds)) ::timeout)]
      (if (= ::timeout r)
        (let [d (stop! pr)] {:timed-out true :out (str (:out d)) :err (str (:err d))})
        {:exit (:exit r) :out (:out r) :err (:err r)}))
    {:not-found true}))

(defn- lines [s] (remove str/blank? (str/split-lines (str s))))

;; ---------------------------------------------------------------------------
;; The checks

(defn- scenario-findings
  "Every stamped scenario not tagged @wip named by a result, every result
  naming it passed, an outline's every row."
  [scenarios rs]
  (let [passed? #(= "passed" (:status %))
        row-of (fn [r] (into {} (map (fn [p] [(get p "name") (str (get p "value"))]) (:parameters r))))]
    (for [s scenarios
          :let [mine (filter #(= (:id s) (:spec %)) rs)
                rows (for [ex (:examples s) r (:rows ex)] (zipmap (:headings ex) (map str (or (:cells r) r))))
                bad (first (remove passed? mine))]
          f (cond
              (empty? mine)
              [(cmd/finding (:file s) (:line s) "scenario-unverified"
                            (str (:id s) " is named by no result: every scenario is tested before it counts as built"))]
              bad
              [(cmd/finding (:file bad) nil "scenario-failed"
                            (str (:id s) " is " (:status bad) ": a result naming it did not pass"))]
              (and (:outline? s) (seq rows) (not-every? (fn [row] (some #(= row (row-of %)) mine)) rows))
              [(cmd/finding (:file s) (:line s) "scenario-unverified"
                            (str (:id s) " has a row no result names: an outline is verified by all its rows"))]
              :else [])]
      f)))

(defn- tests-check
  [{:keys [root build cycle-dir mapping scenarios]} c]
  (let [rel (str cycle-dir "/results")
        abs (fs/absolutize (fs/path root rel))
        log (str cycle-dir "/test.log")
        t0 (now)]
    (fs/delete-tree abs)
    (fs/create-dirs abs)
    (let [r (try @(p/process (mapv str (:command c)) {:dir (str build) :out :string :err :string
                                                       :extra-env {"RESULTS_DIR" (str abs)}})
                 (catch java.io.IOException e {:not-found (ex-message e)}))
          seconds (seconds-since t0)]
      (when (:not-found r)
        (cmd/cannot-run! (str "the tests check cannot run: its command " (pr-str (:command c)) " was not found")))
      (spit (fs/file root log) (str (:out r) (:err r)))
      (let [record {:name (:name c) :kind :tests :seconds seconds}
            files (sort (map str (fs/glob abs "*-result.json")))]
        (cond
          (not= 0 (:exit r))
          {:record (assoc record :outcome :failed :scenarios 0)
           :findings [(cmd/finding log nil "tests-failed"
                                   (str "the test command failed with exit " (:exit r) "; its output is in " log))]}
          (empty? files)
          {:record (assoc record :outcome :failed :scenarios 0)
           :findings [(cmd/finding rel nil "tests-no-results"
                                   (str "the test command exited 0 and wrote no Allure result into " rel
                                        ": a green exit with no results is not a run"))]}
          :else
          (let [read (for [f files] (assoc (results/parse-result (slurp f)) :file (str rel "/" (fs/file-name f))))
                rs (remove :unreadable read)
                built (filter #(and (:id %) (not (:wip? %))) scenarios)
                by-id (into {} (map (juxt :id identity) built))
                findings (vec (concat (results/unreadable-findings (filter :unreadable read))
                                      (scenario-findings built rs)
                                      (for [r rs :let [s (by-id (:spec r))] :when s
                                            f (mapping-findings mapping s (:json r) (:file r))]
                                        f)))
                named (count (filter (fn [s] (some #(= (:id s) (:spec %)) rs)) built))]
            {:record (assoc record :outcome (if (seq findings) :failed :passed) :scenarios named)
             :findings findings}))))))

(defn- runs-check [{:keys [build]} c]
  (let [budget (get-in c [:budget :seconds] 60)
        t0 (now)
        r (run-for (:command c) build budget)
        seconds (seconds-since t0)
        {:keys [exit stdout-starts]} (:expect c)
        record {:name (:name c) :kind :runs :seconds seconds :budget budget :margin (runs-margin budget seconds)}
        first-line (first (str/split-lines (str (:out r))))
        why (cond
              (:not-found r) (cmd/cannot-run! (str "the check " (:name c) " cannot run: its command "
                                                   (pr-str (:command c)) " was not found"))
              (:timed-out r) (str "the check " (:name c) " gave no answer within the budget of " budget " seconds, and was stopped")
              (and (some? exit) (not= exit (:exit r))) (str "the check " (:name c) " ended with exit " (:exit r)
                                                           " where " exit " was expected")
              (and stdout-starts (not (str/starts-with? (str (:out r)) stdout-starts)))
              (str "the check " (:name c) " printed " (pr-str (or first-line "")) " first, where its standard output"
                   " was expected to start " (pr-str stdout-starts)))]
    (if why
      {:record (assoc record :outcome :failed :margin (if (:timed-out r) 0.0 (:margin record))) :findings [(cmd/finding nil nil "check-failed" why)]}
      {:record (assoc record :outcome :passed)})))

(defn- fill [words k v] (mapv #(str/replace % k (str v)) words))

(defn- serves-check [{:keys [build]} c]
  (let [{:keys [seconds polls]} (:budget c)
        port (:port c)
        url (str "http://127.0.0.1:" port "/")
        step (/ (* 1000.0 seconds) polls)
        t0 (now)
        app (or (start (fill (:start c) "{port}" port) build)
                (cmd/cannot-run! (str "the check " (:name c) " cannot run: its start command " (pr-str (:start c)) " was not found")))]
    (try
      (let [answered (loop [i 1]
                       (when (<= i polls)
                         (let [due (+ t0 (* i step)) wait (- due (now))]
                           (when (pos? wait) (Thread/sleep (long wait)))
                           (if (answers? port (long (max 100 (min step 1000)))) i (recur (inc i))))))
            record {:name (:name c) :kind :serves :budget seconds :polls polls}]
        (if-not answered
          {:record (assoc record :outcome :failed :seconds (seconds-since t0) :margin 0)
           :findings [(cmd/finding nil nil "check-failed"
                                   (str "the check " (:name c) ": no answer came within " seconds " seconds (" polls " polls) on port " port))]}
          (let [probe (run-for (fill (:probe c) "{url}" url) build 60)
                record (assoc record :answered-on-poll answered :margin (poll-margin polls answered)
                              :seconds (seconds-since t0))]
            (cond
              (:not-found probe) (cmd/cannot-run! (str "the check " (:name c) "'s probe " (pr-str (:probe c)) " was not found"))
              (= 0 (:exit probe)) {:record (assoc record :outcome :passed)}
              :else {:record (assoc record :outcome :failed)
                     :findings [(cmd/finding nil nil "probe-failed"
                                             (str "the check " (:name c) " answered, and its probe "
                                                  (if (:timed-out probe) "timed out" (str "exited " (:exit probe)))
                                                  ": it is not this application; the probe said: "
                                                  (str/join " / " (lines (str (:out probe) (:err probe))))))]}))))
      (finally (stop! app)))))

(defn- plugin-check [{:keys [build config cycle cut cycle-dir]} c]
  (let [pl (plugins/answering config (:name c) :Check)
        t0 (now)
        r (plugins/ask pl {:request :Check :cycle cycle :cut cut :checkout (str cycle-dir "/build")} build)
        record {:name (:name c) :kind :plugin :seconds (seconds-since t0)}]
    (when-let [why (:cannot-run r)]
      (cmd/cannot-run! (str "the check " (:name c) " is :refused: " why)))
    (if (seq (:findings r))
      {:record (assoc record :outcome :failed) :findings (:findings r) :warnings (:warnings r)}
      {:record (assoc record :outcome :passed) :warnings (:warnings r)})))

;; ---------------------------------------------------------------------------
;; The checkout and the room

(defn- export! [root cut build]
  (when (fs/exists? build) (fs/delete-tree build))
  (fs/create-dirs build)
  (let [tar (cmd/temp-file ".tar")]
    (try
      (let [a (cmd/sh {:dir root} "git" "archive" "--format=tar" "-o" (str tar) cut)]
        (when-not (= 0 (:exit a)) (cmd/cannot-run! (str "git archive failed: " (:err a)))))
      (let [x (cmd/sh {:dir (str build)} "tar" "-x" "-f" (str tar))]
        (when-not (= 0 (:exit x)) (cmd/cannot-run! (str "tar failed: " (:err x)))))
      (finally (fs/delete-if-exists tar)))))

(defn- put-room! [config room build]
  (doseq [p (:implementation config)
          :let [b (fs/path build p) r (fs/path room p)]]
    (when (fs/exists? b) (if (fs/directory? b) (fs/delete-tree b) (fs/delete b)))
    (when (fs/exists? r)
      (fs/create-dirs (fs/parent b))
      (if (fs/directory? r) (fs/copy-tree r b) (fs/copy r b)))))

(defn room-tree
  "The git tree of the room's implementation paths, in full."
  [config room]
  (let [g (cmd/temp-dir "rdm-spec-tree-")]
    (try
      (cmd/git! (str g) "init" "-q" "--bare" (str g))
      (let [env {"GIT_DIR" (str g) "GIT_WORK_TREE" (str room) "GIT_INDEX_FILE" (str g "/index.tree")}
            paths (filter #(fs/exists? (fs/path room %)) (:implementation config))]
        (when (seq paths)
          (let [a (apply cmd/sh {:dir (str room) :env env} "git" "add" "-A" "--" paths)]
            (when-not (= 0 (:exit a)) (cmd/cannot-run! (str "the room's tree cannot be read: " (:err a))))))
        (let [w (cmd/sh {:dir (str room) :env env} "git" "write-tree")]
          (when-not (= 0 (:exit w)) (cmd/cannot-run! (str "the room's tree cannot be read: " (:err w))))
          (str/trim (:out w))))
      (finally (fs/delete-tree g)))))

;; ---------------------------------------------------------------------------
;; The stages

(defn prepare-stage
  "Reads the cycle before anything else, then the cut's configuration, its
  checks, its mapping and its plugins, refusing before anything runs."
  [{:keys [root config args]}]
  (let [[room-arg cycle] args
        cycles (:cycles-dir config "dhf/cycles")
        cycle-dir (str cycles "/" cycle)
        file (str cycle-dir "/cycle.edn")
        decl (let [t (cmd/read-at root nil file)]
               (when-not t (cmd/cannot-run! (str "the cycle " cycle " has no " file)))
               (try (cmd/read-edn t) (catch Exception _ (cmd/cannot-run! (str file " cannot be read")))))
        cut (or (:cut decl) (cmd/cannot-run! (str file " names no :cut")))
        _ (when-not (= 0 (:exit (cmd/git root "rev-parse" "-q" "--verify" (str cut "^{commit}"))))
            (cmd/cannot-run! (str "the cut " cut " that " file " names does not exist")))
        cut-config (or (try (cmd/read-edn (cmd/read-at root cut "dhf/harness/config.edn")) (catch Exception _ nil))
                       (cmd/cannot-run! (str "the cut " cut " holds no readable dhf/harness/config.edn")))
        room (fs/absolutize (fs/path root room-arg))]
    (plugins/validate! cut-config)
    (let [cs (checks cut-config)
          m (mapping cut-config)]
      (when-not (fs/directory? room) (cmd/cannot-run! (str "the room " room-arg " does not exist")))
      {:ctx {:revision cut :config cut-config :cut cut :cycle cycle
             ::verify {:room room :cycle-dir cycle-dir :checks cs :mapping m}}})))

(defn- pr-edn [x] (binding [*print-namespace-maps* false] (with-out-str (pp/pprint x))))

(defn stage
  "Runs the checks on the room's implementation in a checkout of the cut,
  and records what it verified."
  [{:keys [root config cut cycle] :as ctx}]
  (let [{:keys [room cycle-dir checks mapping]} (::verify ctx)
        {:keys [scenarios]} (bus/latest :SpecParsed)
        ratified (fs/file root cycle-dir "ratified.edn")
        tree (room-tree config room)
        refuse (fn [findings checked made?]
                 (fs/create-dirs (fs/parent ratified))
                 (spit ratified (pr-edn (cond-> {:cut cut :room-tree tree :outcome :refused
                                                 :reason (cmd/finding-line (first findings)) :checks checked}
                                          made? (assoc :checkout (str cycle-dir "/build")))))
                 {:findings findings
                  :result {:verify {:cycle cycle :cut cut :outcome :refused :checks checked}}})
        unstamped (filter #(empty? (:ids %)) scenarios)]
    (if (seq unstamped)
      (refuse (vec (for [s unstamped]
                     (cmd/finding (:file s) (:line s) "scenario-unstamped"
                                  (str "the cut " cut " holds the unstamped scenario \"" (:title s)
                                       "\": a cut is verified only when every scenario has its id"))))
              [] false)
      (do
        (doseq [c checks :when (= :serves (:kind c))]
          (when-not (port-free? (:port c))
            (cmd/cannot-run! (str "the port " (:port c) " of the check " (:name c)
                                  " is already held by another process: verify measures only its own app"))))
        (let [build (fs/absolutize (fs/path root cycle-dir "build"))
              _ (export! root cut build)
              _ (put-room! config room build)
              env {:root root :build build :cycle-dir cycle-dir :mapping mapping :scenarios scenarios
                   :config config :cycle cycle :cut cut}
              run (fn [c] (case (:kind c)
                            :tests (tests-check env c)
                            :runs (runs-check env c)
                            :serves (serves-check env c)
                            :plugin (plugin-check env c)))
              {:keys [records findings warnings]}
              (loop [cs checks out {:records [] :findings [] :warnings []}]
                (if-let [c (first cs)]
                  (let [r (run c)
                        out (-> out (update :records conj (:record r))
                                (update :findings into (:findings r))
                                (update :warnings into (:warnings r)))]
                    (if (= :passed (get-in r [:record :outcome]))
                      (recur (rest cs) out)
                      (update out :records into (for [x (rest cs)] {:name (:name x) :kind (:kind x) :outcome :not-run}))))
                  out))]
          (if (seq findings)
            (assoc (refuse findings records true) :warnings warnings)
            (do (spit ratified (pr-edn {:cut cut :room-tree tree :outcome :passed
                                        :checkout (str cycle-dir "/build") :checks records}))
                {:warnings warnings
                 :result {:verify {:cycle cycle :cut cut :outcome :passed :checks records}}})))))))
