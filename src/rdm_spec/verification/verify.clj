(ns rdm-spec.verification.verify
  "The Verify component of the verification context
  (dhf/spec/verification/verify.feature; glossary, \"Verify\" and
  \"Check\"): `rdm-spec verify ROOM CYCLE`, a cycle's gate. The room's
  implementation, in place of the implementation of a fresh export of the
  cycle's cut, passes every check the cut's configuration declares
  (:verify), in order, the first that fails stopping it; the checks after
  it are listed :not-run. The oracle runs before it as a gate (the task
  table's), so verify never knows the oracle.

  In order: the cycle's cycle.edn (<cycles-dir>/CYCLE/cycle.edn, read first:
  cannot run without it or its :cut), the room (a directory), the cut (a
  commit), the cut's configuration, its checks and its Allure mapping (a
  configuration verify cannot trust cannot run, and no check runs); then
  the checkout <cycles-dir>/CYCLE/build: the cut's files, no history, every
  :implementation path removed and the room's copied in. A cut holding a
  scenario with no single id is not verified (verify-unstamped).

  The checks (glossary, \"Check\"), each {:name :kind :outcome :passed |
  :failed | :not-run :seconds ...}:
    :tests   the command, run in the checkout with RESULTS_DIR the absolute
             <cycles-dir>/CYCLE/results (emptied first), its output in
             <cycles-dir>/CYCLE/test.log; passes when it exits 0, wrote Allure
             results, every stamped scenario not tagged @wip is verified by
             them, and every result keeps the mapping. Reports :scenarios, the
             stamped scenarios the results name.
    :serves  the start command ({port} filled) on a port nobody holds (else
             cannot run), polled with GET / every budget/polls seconds; any
             HTTP response is an answer, an accepted connection with none is
             not; then the probe ({url} filled) must exit 0. Reports
             :answered-on-poll, :polls and :margin, the polls left.
    :runs    the command answers within its budget with the expected :exit
             and standard output starting :stdout-starts. Reports :budget
             and :margin, the seconds left.
  A check whose command is not found cannot run (exit 2). Every process a
  check starts is stopped, its descendants too.

  Findings (exit 1): verify-unstamped, verify-tests-failed,
  verify-no-results, verify-unnamed, verify-not-passed, result-unreadable,
  steps-differ-from-scenario, mapping-differs, evidence-missing (the
  mapping's, at the scenario, naming the element first),
  verify-serves-failed, verify-runs-failed.

  It writes <cycles-dir>/CYCLE/ratified.edn (the name verify's record keeps):
  {:cut :room-tree :outcome :passed | :refused :checkout :checks [check]},
  :reason when refused; :room-tree the git tree hash of the room's
  implementation paths. Its result: {:verify {:cycle :cut :outcome :checks}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

;; ---------------------------------------------------------------------------
;; Small things

(defn seconds
  "Milliseconds as seconds, to the hundredth."
  [ms]
  (/ (Math/round (/ (double ms) 10.0)) 100.0))

(defn- one-line [s] (str/replace (str/trim (str s)) #"\s*\r?\n\s*" " "))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn- git [dir env & args]
  (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env (or env {})}
         "git" (map str args)))

(defn- under? [rel p]
  (let [p (strip-slash p)] (or (= rel p) (str/starts-with? rel (str p "/")))))

;; ---------------------------------------------------------------------------
;; The checks a configuration declares

(def kinds #{:tests :serves :runs})

(defn checks-of
  "The checks the configuration `cfg` declares, each named (its kind's name
  when it names none); the task cannot run on one verify cannot trust."
  [cfg]
  (let [v (:verify cfg)
        impl (:implementation cfg)]
    (when (nil? v) (command/cannot-run! "the configuration declares no :verify: verify has no check to run"))
    (when-not (and (sequential? v) (every? map? v))
      (command/cannot-run! "the configuration's :verify is not a vector of checks"))
    (doseq [c v :when (not (kinds (:kind c)))]
      (command/cannot-run! (str (pr-str (:kind c)) " is not a kind of check: the kinds are :tests, :serves and :runs")))
    (when-not (= :tests (:kind (first v)))
      (command/cannot-run! "the configuration's :verify declares no :tests check first: a project is verified by its tests first"))
    (doseq [c v]
      (case (:kind c)
        :tests (when-not (and (sequential? (:command c)) (seq (:command c)))
                 (command/cannot-run! "the tests check names no command: declare {:kind :tests :command [...]}"))
        :runs (when-not (and (sequential? (:command c)) (seq (:command c)) (number? (get-in c [:budget :seconds])))
                (command/cannot-run! (str "the :runs check " (pr-str (:name c)) " needs a :command and a :budget {:seconds S}")))
        :serves (do (when-not (and (sequential? (:start c)) (integer? (:port c))
                                   (number? (get-in c [:budget :seconds])) (pos-int? (get-in c [:budget :polls]))
                                   (sequential? (:probe c)))
                      (command/cannot-run! (str "the :serves check " (pr-str (:name c))
                                                " needs :start, :port, :budget {:seconds S :polls K} and :probe")))
                    (doseq [a (:probe c) p impl :when (under? (str a) p)]
                      (command/cannot-run! (str "the :serves check's probe " a " is under " p
                                                ": the probe is in the implementation the builder writes; it lives in a kept path"))))))
    (mapv #(update % :name (fn [n] (or n (name (:kind %))))) v)))

;; ---------------------------------------------------------------------------
;; Running a command within a budget

(defn- stop-tree! [^Process proc]
  (when proc
    (let [h (.toHandle proc)]
      (doseq [d (iterator-seq (.iterator (.descendants h)))] (.destroyForcibly ^java.lang.ProcessHandle d))
      (.destroyForcibly proc)
      (try (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS) (catch Exception _ nil)))))

(defn- start! [cmd {:keys [dir env out check]}]
  (try
    (p/process (mapv str cmd) {:dir (str dir) :extra-env (or env {}) :in "" :out (or out :string)
                               :err (if out out :string)})
    (catch java.io.IOException e
      (command/cannot-run! (str (if check (str "the check " check " cannot run: ") "")
                                "its command " (first cmd) " is not found (a check that cannot run never passes)")))))

(defn run-command
  "Runs `cmd` in `:dir` with `:env` within `:budget-ms` (none: no limit):
  {:exit :out :err :ms :timed-out?}. Its processes are stopped when the
  budget runs out. A command that is not found cannot run."
  [cmd {:keys [dir env budget-ms check]}]
  (let [t0 (System/currentTimeMillis)
        pr (start! cmd {:dir dir :env env :check check})
        proc ^Process (:proc pr)
        done? (if budget-ms
                (.waitFor proc (long budget-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
                (do (.waitFor proc) true))
        ms (- (System/currentTimeMillis) t0)]
    (if done?
      (let [r @pr] {:exit (:exit r) :out (str (:out r)) :err (str (:err r)) :ms ms :timed-out? false})
      (do (stop-tree! proc)
          {:exit nil :out "" :err "" :ms ms :timed-out? true}))))

;; ---------------------------------------------------------------------------
;; The room's tree

(defn room-tree
  "The git tree hash of the implementation paths `impl` of the room `room`."
  [room impl]
  (let [g (str (fs/create-temp-dir {:prefix "rdm-spec-tree-"}))]
    (try
      (let [env {"GIT_DIR" g "GIT_WORK_TREE" (str (fs/absolutize room))}
            _ (git room env "init" "-q")
            paths (filter #(fs/exists? (fs/file room %)) impl)]
        (when (seq paths) (apply git room env "add" "-f" "--" paths))
        (let [r (git room env "write-tree")]
          (when-not (zero? (:exit r)) (command/cannot-run! (str "the room's tree cannot be read: " (one-line (:err r)))))
          (str/trim (:out r))))
      (finally (fs/delete-tree g)))))

;; ---------------------------------------------------------------------------
;; :tests

(defn- scenarios-of
  "The scenarios of `pickles`, as the Status component reads them."
  [pickles]
  (vec (for [[_ pks] (group-by (juxt :file :line) pickles)
             :let [pk (first pks)]]
         (assoc pk :rows (vec (keep :row pks))))))

(defn- pickle-for [pickles-by-id r]
  (let [pks (get pickles-by-id (:spec r))]
    (if (:outline? (first pks))
      (first (filter #(= (:parameters r) (get-in % [:row :values])) pks))
      (first pks))))

(defn- tests-findings
  "The findings of the tests check over the results read `read`."
  [read pickles m]
  (let [scs (scenarios-of pickles)
        {:keys [statuses by-id]} (status/derive-statuses scs (:results read))
        graded (filter #(and (:id %) (not (:wip? %))) scs)
        by-pk-id (group-by :id (filter :id pickles))]
    (vec
     (concat
      (results/unreadable-findings read)
      (for [sc graded :when (= :unverified (get statuses (:id sc)))]
        (command/finding (:file sc) (:line sc) "verify-unnamed"
                         (str (:id sc) " is named by no result that verifies it: every scenario not tagged @wip is tested")))
      (for [sc graded :when (= :failed (get statuses (:id sc)))]
        (command/finding (:file sc) (:line sc) "verify-not-passed"
                         (str (:id sc) " has a result that did not pass: "
                              (str/join ", " (distinct (remove #{"passed"} (map :status (get by-id (:id sc)))))))))
      (for [r (:results read)
            :let [pk (pickle-for by-pk-id r)]
            :when pk
            d (runner/mapping-differences pk (:result r) m)]
        (command/finding (:file pk) (or (get-in pk [:row :line]) (:line pk)) (:rule d)
                         (str (when (:element d) (str (:element d) ": ")) (:message d))))))))

(defn- run-tests [req check {:keys [checkout results-dir log-file log-rel pickles mapping]}]
  (fs/delete-tree results-dir)
  (fs/create-dirs results-dir)
  (let [budget (get-in check [:budget :seconds])
        r (run-command (:command check) {:dir checkout :env (merge (:env req) {"RESULTS_DIR" (str results-dir)}) :check (:name check)
                                         :budget-ms (when budget (long (* 1000 budget)))})
        _ (spit (str log-file) (str (:out r) (:err r)))
        base (cond-> {:name (:name check) :kind :tests :seconds (seconds (:ms r))}
               budget (assoc :budget budget :margin (seconds (- (* 1000 budget) (:ms r)))))
        fail (fn [rule msg] {:check (assoc base :outcome :failed) :findings [(command/finding log-rel rule msg)] :reason msg})]
    (cond
      (:timed-out? r) (fail "verify-tests-failed" (str "the test command was still running after the budget of " budget
                                                       " seconds; its output is in " log-rel))
      (not (zero? (:exit r))) (fail "verify-tests-failed" (str "the test command exited " (:exit r) ": it failed; its output is in " log-rel))
      (empty? (fs/glob results-dir "*-result.json"))
      (fail "verify-no-results" (str "the test command exited 0 and wrote no Allure result into " (:results-rel check)
                                     ": a green exit with no results is not a run"))
      :else
      (let [read (results/read-results req (str results-dir) {:keep? true})
            fs* (tests-findings read pickles mapping)
            named (count (distinct (filter (set (keep :id pickles)) (keep :spec (:results read)))))
            c (assoc base :outcome (if (seq fs*) :failed :passed) :scenarios named)]
        {:check (if (seq fs*) c c) :findings fs*
         :reason (when (seq fs*) (str "the tests check failed: " (one-line (:message (first fs*)))))}))))

;; ---------------------------------------------------------------------------
;; :runs

(defn- run-runs [req check {:keys [checkout]}]
  (let [budget (get-in check [:budget :seconds])
        r (run-command (:command check) {:dir checkout :env (:env req) :budget-ms (long (* 1000 budget)) :check (:name check)})
        {:keys [exit stdout-starts]} (:expect check)
        base {:name (:name check) :kind :runs :seconds (seconds (:ms r)) :budget budget
              :margin (seconds (- (* 1000 budget) (:ms r)))}
        why (cond
              (:timed-out? r) (str "the command did not answer within the budget of " budget " seconds")
              (and (some? exit) (not= exit (:exit r))) (str "the command exited " (:exit r) " where " exit " was expected")
              (and stdout-starts (not (str/starts-with? (:out r) stdout-starts)))
              (str "the first line it printed is " (pr-str (first (str/split-lines (str (:out r)))))
                   ", where standard output starting " (pr-str stdout-starts) " was expected"))]
    (if why
      (let [msg (str "the check " (:name check) " failed: " why)]
        {:check (assoc base :outcome :failed) :findings [(command/finding nil nil "verify-runs-failed" msg)] :reason msg})
      {:check (assoc base :outcome :passed) :findings []})))

;; ---------------------------------------------------------------------------
;; :serves

(defn http-response?
  "Whether `line`, the first line an app sent back, is an HTTP response."
  [line]
  (boolean (and line (re-find #"^HTTP/\d" line))))

(defn- port-held? [port]
  (or (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true) (catch Exception _ false))
      (try (with-open [ss (java.net.ServerSocket.)]
             (.setReuseAddress ss false)
             (.bind ss (java.net.InetSocketAddress. "127.0.0.1" (int port)))
             false)
           (catch Exception _ true))))

(defn- answers?
  "Whether GET / on `port` gets an HTTP response within `timeout-ms`."
  [port timeout-ms]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) (int (max 1 timeout-ms)))
      (.setSoTimeout s (int (max 1 timeout-ms)))
      (let [o (.getOutputStream s)]
        (.write o (.getBytes (str "GET / HTTP/1.0\r\nHost: 127.0.0.1:" port "\r\n\r\n") "US-ASCII"))
        (.flush o))
      (let [rd (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "ISO-8859-1"))]
        (http-response? (.readLine rd))))
    (catch Exception _ false)))

(defn- fill [args k v] (mapv #(str/replace (str %) k (str v)) args))

(defn- run-serves [req check {:keys [checkout]}]
  (let [port (:port check)
        {:keys [polls] secs :seconds} (:budget check)]
    (when (port-held? port)
      (command/cannot-run! (str "the :serves check " (:name check) " cannot run: the port " port
                                " is already held by another process, and a check never measures another process")))
    (let [interval (long (/ (* 1000 secs) polls))
          t0 (System/currentTimeMillis)
          log (fs/file checkout ".serves.log")
          pr (start! (fill (:start check) "{port}" port) {:dir checkout :env (:env req) :out (fs/file log) :check (:name check)})
          proc ^Process (:proc pr)]
      (try
        (let [answered (loop [i 1]
                         (when (<= i polls)
                           (let [due (+ t0 (* (dec i) interval))
                                 now (System/currentTimeMillis)]
                             (when (> due now) (Thread/sleep (- due now)))
                             (if (answers? port (max 50 (- (+ due interval) (System/currentTimeMillis) 10)))
                               i
                               (recur (inc i))))))
              base {:name (:name check) :kind :serves :polls polls :budget secs}]
          (if-not answered
            (let [msg (str "the check " (:name check) " failed: no answer came within " secs " seconds (" polls " polls of GET / on port " port ")")]
              {:check (assoc base :outcome :failed :seconds (seconds (- (System/currentTimeMillis) t0)))
               :findings [(command/finding nil nil "verify-serves-failed" msg)] :reason msg})
            (let [probe (run-command (fill (:probe check) "{url}" (str "http://127.0.0.1:" port))
                                     {:dir checkout :env (:env req) :check (:name check)
                                      :budget-ms (max 1000 (- (* 1000 secs) (- (System/currentTimeMillis) t0)))})
                  c (assoc base :answered-on-poll answered :margin (- polls answered)
                           :seconds (seconds (- (System/currentTimeMillis) t0)))]
              (if (and (not (:timed-out? probe)) (zero? (:exit probe)))
                {:check (assoc c :outcome :passed) :findings []}
                (let [msg (str "the check " (:name check) " failed: the app answered, but its probe "
                               (if (:timed-out? probe) "did not finish" (str "exited " (:exit probe)))
                               ", so it is not this application: " (one-line (str (:out probe) " " (:err probe))))]
                  {:check (assoc c :outcome :failed) :findings [(command/finding nil nil "verify-serves-failed" msg)]
                   :reason msg})))))
        (finally
          (stop-tree! proc)
          (fs/delete-if-exists log))))))

;; ---------------------------------------------------------------------------
;; The cycle, the cut, the checkout

(defn- read-cycle [req cycles cycle]
  (let [rel (str cycles "/" cycle "/cycle.edn")
        f (command/path req rel)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "there is no " rel ": verify reads the cycle before anything else it does")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))]
      (when-not (and (map? c) (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str rel " names no :cut: verify reads the cut and nothing else")))
      c)))

(defn- export! [root commit dir]
  (fs/create-dirs dir)
  (let [tar (str (fs/file (fs/create-temp-dir {:prefix "rdm-spec-export-"}) "cut.tar"))]
    (try
      (let [r (git root nil "archive" "--format=tar" "-o" tar commit)]
        (when-not (zero? (:exit r)) (command/cannot-run! (str "the cut cannot be exported: " (one-line (:err r)))))
        (let [t (p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "tar" "-xf" tar)]
          (when-not (zero? (:exit t)) (command/cannot-run! (str "the cut cannot be exported: " (one-line (:err t)))))))
      (finally (fs/delete-tree (fs/parent tar))))))

(defn- checkout! [root commit room impl dir]
  (fs/delete-tree dir)
  (export! root commit dir)
  (doseq [p impl] (fs/delete-tree (fs/file dir p)))
  (doseq [p impl
          :let [src (fs/file room p) dst (fs/file dir p)]
          :when (fs/exists? src)]
    (if (fs/directory? src)
      (fs/copy-tree src dst {:replace-existing true})
      (do (fs/create-dirs (fs/parent dst)) (fs/copy src dst {:replace-existing true})))))

(defn- write-edn! [f x]
  (fs/create-dirs (fs/parent f))
  (spit (str f) (binding [*print-namespace-maps* false] (str (pr-str x) "\n"))))

;; ---------------------------------------------------------------------------
;; The stage

(defn verify
  "The verify stage: `rdm-spec verify ROOM CYCLE`."
  [req]
  (let [[room-arg cycle] (:args req)
        cycles (strip-slash (command/config-value req :cycles-dir))
        c (read-cycle req cycles cycle)
        room (command/path req room-arg)
        _ (when-not (fs/directory? room)
            (command/cannot-run! (str "the room " room-arg " is not a directory")))
        cut (:cut c)
        rr (git (:root req) nil "rev-parse" "--verify" "-q" (str cut "^{commit}"))
        _ (when-not (zero? (:exit rr))
            (command/cannot-run! (str "the cut " cut " that cycle " cycle " names is not in " (:root req))))
        commit (str/trim (:out rr))
        cfg-text (:out (git (:root req) nil "show" (str commit ":" command/config-file)))
        cfg (let [v (try (edn/read-string cfg-text) (catch Exception _ nil))]
              (if (map? v) v (command/cannot-run! (str "the cut " cut " holds no readable " command/config-file))))
        checks (checks-of cfg)
        mapping (runner/mapping-of cfg)
        _ (when-let [why (runner/mapping-problem mapping)]
            (command/cannot-run! (str "the configuration's :allure cannot be used: " why)))
        impl (vec (:implementation cfg))
        cycle-rel (str cycles "/" cycle)
        checkout-rel (str cycle-rel "/build")
        checkout (command/path req checkout-rel)
        tree (room-tree room impl)
        _ (checkout! (:root req) commit room impl checkout)
        {:keys [pickles]} (runner/plan checkout {:spec-dir (:spec-dir cfg) :id-prefix (:id-prefix cfg) :model (:model cfg)})
        unstamped (for [pk (distinct (map #(select-keys % [:file :line :ids :title]) pickles))
                        :when (not= 1 (count (:ids pk)))]
                    (command/finding (:file pk) (:line pk) "verify-unstamped"
                                     (str "the scenario " (pr-str (:title pk)) " at the cut " cut " carries "
                                          (if (empty? (:ids pk)) "no id" (str "the ids " (str/join ", " (:ids pk))))
                                          ": a cut holding an unstamped scenario is not verified")))
        ctx {:checkout checkout :results-dir (command/path req (str cycle-rel "/results"))
             :log-file (command/path req (str cycle-rel "/test.log")) :log-rel (str cycle-rel "/test.log")
             :pickles pickles :mapping mapping}
        {:keys [done findings reason]}
        (if (seq unstamped)
          {:done [] :findings (vec unstamped) :reason (str "the cut " cut " holds a scenario with no single id")}
          (reduce (fn [acc check]
                    (let [out (case (:kind check)
                                :tests (run-tests req (assoc check :results-rel (str cycle-rel "/results")) ctx)
                                :runs (run-runs req check ctx)
                                :serves (run-serves req check ctx))
                          acc (-> acc (update :done conj (:check out)) (update :findings into (:findings out)))]
                      (if (= :failed (:outcome (:check out)))
                        (reduced (assoc acc :reason (:reason out)))
                        acc)))
                  {:done [] :findings []}
                  checks))
        all (vec (concat done (for [c (drop (count done) checks)] {:name (:name c) :kind (:kind c) :outcome :not-run})))
        outcome (if (and (empty? findings) (every? #(= :passed (:outcome %)) all)) :passed :refused)
        record (cond-> {:cut cut :room-tree tree :outcome outcome :checkout checkout-rel :checks all}
                 (= :refused outcome) (assoc :reason (or reason "a check failed")))]
    (write-edn! (command/path req (str cycle-rel "/ratified.edn")) record)
    {:result {:verify {:cycle cycle :cut cut :outcome outcome :checks all}}
     :findings findings}))
