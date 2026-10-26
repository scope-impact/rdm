(ns rdm-spec.verification.verify
  "The Verify component of the verification context
  (dhf/spec/verification/verify.feature; dhf/spec/glossary.md, \"Verify\"
  and \"Check\"): `rdm-spec verify ROOM CYCLE`, a cycle's gate. The room's
  implementation, put in place of the implementation of a fresh export of
  the cycle's cut (dhf/cycles/CYCLE/build: the cut's files and no history),
  passes every check the cut's configuration declares in :verify, in
  order, the first that fails stopping it, the rest :not-run. It gates and
  never scores. (It was ratify until spec-20; its record keeps the name
  ratified.edn.)

  The checks, of three closed kinds, each run in the checkout:

    {:kind :tests :command [..]}   the command exits 0 with RESULTS_DIR the
        absolute path of dhf/cycles/CYCLE/results, its output in
        dhf/cycles/CYCLE/test.log; it wrote Allure results (a green exit with
        none is not a run), none unreadable; every stamped scenario of the cut
        not tagged @wip is named by a result, and every result naming it
        passed. Reported as {:name :kind :outcome :seconds :scenarios}.
    {:kind :runs :name N :command [..] :expect {:exit E :stdout-starts T}
     :budget {:seconds S}}   the command answers within S seconds with the
        exit E (0 by default) and output starting T. {.. :seconds :budget
        :margin}, the margin the seconds of the budget left.
    {:kind :serves :name N :start [.. \"{port}\" ..] :port P
     :budget {:seconds S :polls K} :probe [.. \"{url}\" ..]}   the app starts
        fresh, answers an HTTP request to http://127.0.0.1:P/ within K polls,
        one every S/K seconds, and the probe (in a kept path, never in the
        implementation) then exits 0. {.. :seconds :budget :answered-on-poll
        :polls :margin}, the margin the polls left. Every process it started is
        stopped before verify returns.

  A check's name is its kind's when it names none. A failed check is
  {.. :outcome :failed :reason R}, R a finding too.

  Cannot run (exit 2), running no check: no cycle.edn, no cut, ROOM not a
  directory, a configuration verify cannot trust (no :verify, no :tests
  check first, an unknown kind, a tests check with no command, a probe in
  the implementation, a check missing what its kind needs); then, after the
  checkout is built and before any check runs, a command not found or a
  port another process holds.

  It writes dhf/cycles/CYCLE/ratified.edn, exactly {:cut :room-tree
  :outcome :passed :checkout :checks}, or with :outcome :refused and
  :reason; :room-tree is the git tree of the room's implementation paths.
  A cut holding an unstamped scenario is refused (verify-unstamped), its
  checks :not-run. Result: {:verify {:cycle :cut :outcome :checks}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.verification.results :as results]))

;; ---------------------------------------------------------------------------
;; Pure

(def kinds #{:tests :serves :runs})

(defn check-name
  "A check's name: its own, else its kind's."
  [c]
  (or (:name c) (some-> (:kind c) name)))

(defn hundredths [x] (/ (Math/round (* 100.0 (double x))) 100.0))

(defn- under? [rel p]
  (let [p (str/replace (str p) #"/+$" "")
        rel (str/replace (str rel) #"^\./" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn- argv? [x] (and (vector? x) (seq x) (every? string? x)))

(defn- seconds? [b] (and (map? b) (number? (:seconds b)) (pos? (:seconds b))))

(defn config-problem
  "Why verify cannot trust the configuration `cfg`'s :verify, or nil."
  [cfg]
  (let [v (:verify cfg)
        impl (:implementation cfg)]
    (cond
      (not (contains? cfg :verify))
      "the configuration declares no :verify: verify proves only the checks a project declares there"
      (not (and (vector? v) (every? map? v)))
      ":verify is not a vector of checks, {:kind :tests :command [..]} ..."
      :else
      (or (first (for [c v :when (not (kinds (:kind c)))]
                   (str (pr-str (:kind c)) " is not a kind of check: the kinds are :tests, :serves and :runs")))
          (when-not (= :tests (:kind (first v)))
            (str "a project is verified by its tests first: :verify declares no :tests check"
                 (when (some #(= :tests (:kind %)) v) " as its first")))
          (when (< 1 (count (filter #(= :tests (:kind %)) v)))
            "the tests check is declared twice: a project declares one")
          (when-not (argv? (:command (first v)))
            "the tests check names no command: declare {:kind :tests :command [\"...\"]}")
          (let [names (map check-name v)]
            (when-not (= (count names) (count (distinct names)))
              (str "two checks share a name: " (pr-str names))))
          (first
           (for [c (rest v)
                 :let [n (check-name c)]
                 :let [why (case (:kind c)
                             :tests nil
                             :runs (cond (not (argv? (:command c))) "names no :command"
                                         (not (seconds? (:budget c))) "states no budget, {:seconds S}")
                             :serves (cond (not (argv? (:start c))) "names no :start"
                                           (not (integer? (:port c))) "names no :port"
                                           (not (and (seconds? (:budget c)) (pos-int? (get-in c [:budget :polls]))))
                                           "states no budget, {:seconds S :polls K}"
                                           (not (argv? (:probe c))) "names no :probe"
                                           (some (fn [a] (some #(under? a %) impl)) (:probe c))
                                           (str "names the probe " (str/join " " (:probe c))
                                                ": the probe is in the implementation the builder writes;"
                                                " a probe lives in a kept path")))]
                 :when why]
             (str "the check " (pr-str n) " " why)))))))

(defn coverage
  "Pure: {:named n :missing [id] :not-passed [[id [status]]]} of the stamped
  `scenarios` not tagged @wip over the results `rs`."
  [scenarios rs]
  (let [by (group-by :spec rs)
        ids (distinct (for [sc scenarios :when (and (:id sc) (not (:wip? sc)))] (:id sc)))]
    {:named (count (filter #(seq (get by %)) ids))
     :missing (vec (remove #(seq (get by %)) ids))
     :not-passed (vec (for [id ids
                            :let [bad (vec (distinct (remove #{"passed"} (map :status (get by id)))))]
                            :when (seq bad)]
                        [id bad]))}))

;; ---------------------------------------------------------------------------
;; git and the checkout

(defn- git [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" args))

(defn room-tree
  "The git tree hash of the implementation paths `impl` of the room `room`."
  [room impl]
  (let [g (str (fs/create-temp-dir {:prefix "rdm-spec-verify-tree-"}))]
    (try
      (let [env {"GIT_DIR" g "GIT_WORK_TREE" (str (fs/absolutize room))}
            sh (fn [& args] (apply p/shell {:dir (str room) :out :string :err :string :continue true :in ""
                                            :extra-env env} "git" args))
            present (filter #(fs/exists? (fs/file room %)) impl)]
        (sh "init" "-q")
        (when (seq present)
          (apply sh "add" "-A" "-f" "--" present))
        (let [r (sh "write-tree")]
          (when-not (zero? (:exit r))
            (command/cannot-run! (str "the room's tree cannot be written: " (str/trim (:err r)))))
          (str/trim (:out r))))
      (finally (fs/delete-tree g)))))

(defn- export! [root commit dir]
  (fs/create-dirs dir)
  (let [tar (p/process {:dir (str root) :err :string} "git" "archive" "--format=tar" commit)
        x (p/process {:dir (str dir) :in (:out tar) :err :string :out :string} "tar" "-x" "-f" "-")]
    (when-not (and (zero? (:exit @x)) (zero? (:exit @tar)))
      (command/cannot-run! (str "the cut cannot be exported: " (str/trim (str (:err @tar) (:err @x))))))))

(defn- copy-impl! [room dir impl]
  (doseq [p impl
          :let [src (fs/file room p) dst (fs/file dir p)]
          :when (fs/exists? src)]
    (if (fs/directory? src)
      (fs/copy-tree src dst {:replace-existing true :copy-attributes true})
      (do (fs/create-dirs (fs/parent dst))
          (fs/copy src dst {:replace-existing true :copy-attributes true})))))

;; ---------------------------------------------------------------------------
;; Processes

(defn- stop!
  "Stops the process `proc` and every process it started."
  [proc]
  (when proc
    (let [^Process jp (:proc proc)
          h (.toHandle jp)]
      (doseq [d (iterator-seq (.iterator (.descendants h)))] (.destroyForcibly ^java.lang.ProcessHandle d))
      (.destroyForcibly jp)
      (.waitFor jp 5 java.util.concurrent.TimeUnit/SECONDS))))

(defn- now [] (System/nanoTime))
(defn- secs [t0] (hundredths (/ (- (now) t0) 1e9)))

(defn- found? [build cmd]
  (let [x (first cmd)]
    (boolean (or (fs/which x)
                 (and (str/includes? x "/") (fs/exists? (fs/file build x)))))))

(defn- port-free? [port]
  (try (with-open [_ (java.net.ServerSocket. (int port) 50 (java.net.InetAddress/getByName "127.0.0.1"))] true)
       (catch Exception _ false)))

(defn- fill [argv k v] (mapv #(str/replace % k (str v)) argv))

(defn- run-for
  "Runs `argv` in `dir` for at most `seconds`: {:exit :out :timed-out?}."
  [dir argv seconds env]
  (let [proc (apply p/process {:dir (str dir) :out :string :err :string :in "" :extra-env (or env {})} argv)
        done (deref proc (long (* 1000 seconds)) ::timeout)]
    (if (= ::timeout done)
      (do (stop! proc) {:timed-out? true :out "" :exit nil})
      {:exit (:exit done) :out (str (:out done)) :err (str (:err done))})))

(defn- answers? [port timeout-ms]
  (try
    (let [c ^java.net.HttpURLConnection (.openConnection (java.net.URL. (str "http://127.0.0.1:" port "/")))]
      (.setConnectTimeout c (int timeout-ms))
      (.setReadTimeout c (int timeout-ms))
      (try (pos? (.getResponseCode c)) (finally (.disconnect c))))
    (catch Exception _ false)))

;; ---------------------------------------------------------------------------
;; The checks

(defn- failed [c t0 reason & {:as more}]
  (merge {:name (check-name c) :kind (:kind c) :outcome :failed :seconds (secs t0) :reason reason} more))

(defn- tests-check [{:keys [build results-abs results-rel log-rel log-abs scenarios]} c]
  (let [t0 (now)]
    (fs/delete-tree results-abs)
    (fs/create-dirs results-abs)
    (let [proc (apply p/process {:dir (str build) :out (fs/file log-abs) :err :out :in ""
                                 :extra-env {"RESULTS_DIR" (str (fs/canonicalize results-abs))}}
                      (:command c))
          exit (:exit @proc)
          has-results? (seq (fs/glob results-abs "*-result.json"))]
      (cond
        (not (zero? exit))
        (failed c t0 (str "the test command " (str/join " " (:command c)) " failed with exit status " exit
                          "; its output is in " log-rel))
        (not has-results?)
        (failed c t0 (str "the test command exited 0 and wrote no Allure result into " results-rel
                          ": a green exit with no results is not a run (its output is in " log-rel ")"))
        :else
        (let [read (results/read-results {:root "/"} (str results-abs))
              {:keys [named missing not-passed]} (coverage scenarios (:results read))
              problems (concat
                        (for [{:keys [file why]} (:unreadable read)]
                          (str "the result " (str results-rel "/" (fs/file-name file)) " is unreadable: " why))
                        (for [id missing] (str id " is named by no result in " results-rel ": every scenario not tagged @wip is"))
                        (for [[id sts] not-passed] (str id " has a result that did not pass: " (str/join ", " sts))))]
          (if (seq problems)
            (failed c t0 (str/join "; " problems) :scenarios named)
            {:name (check-name c) :kind :tests :outcome :passed :seconds (secs t0) :scenarios named}))))))

(defn- runs-check [{:keys [build]} c]
  (let [t0 (now)
        budget (get-in c [:budget :seconds])
        {:keys [exit out timed-out?]} (run-for build (:command c) budget nil)
        want-exit (get-in c [:expect :exit] 0)
        starts (get-in c [:expect :stdout-starts])
        s (secs t0)
        n (pr-str (check-name c))]
    (cond
      timed-out?
      (failed c t0 (str "the check " n " did not answer within its budget of " budget " seconds: "
                        (str/join " " (:command c)) " was still running, and was stopped")
              :budget budget :margin 0.0)
      (not= want-exit exit)
      (failed c t0 (str "the check " n " answered with the exit " exit " where " want-exit " was expected")
              :budget budget :margin (hundredths (- budget s)))
      (and starts (not (str/starts-with? out starts)))
      (failed c t0 (str "the check " n " printed " (pr-str (first (str/split-lines out)))
                        " first, where its standard output was expected to start " (pr-str starts))
              :budget budget :margin (hundredths (- budget s)))
      :else
      {:name (check-name c) :kind :runs :outcome :passed :seconds s :budget budget :margin (hundredths (- budget s))})))

(defn- serves-check [{:keys [build]} c]
  (let [t0 (now)
        {:keys [seconds polls]} (:budget c)
        port (:port c)
        interval (long (/ (* 1000 seconds) polls))
        n (pr-str (check-name c))]
    (when-not (port-free? port)
      (command/cannot-run! (str "the port " port " of the check " n " is held by another process: verify starts nothing"
                                " rather than measure another process")))
    (let [app (apply p/process {:dir (str build) :out :discard :err :discard :in ""} (fill (:start c) "{port}" port))]
      (try
        (let [answered (loop [k 1]
                         (when (<= k polls)
                           (Thread/sleep interval)
                           (if (answers? port (min interval 1000)) k (recur (inc k)))))]
          (if-not answered
            (failed c t0 (str "the check " n ": no answer came within " seconds " seconds (" polls " polls) on port " port)
                    :budget seconds :polls polls)
            (let [{:keys [exit out err timed-out?]} (run-for build (fill (:probe c) "{url}" (str "http://127.0.0.1:" port "/")) 60 nil)]
              (if (and (not timed-out?) (zero? exit))
                {:name (check-name c) :kind :serves :outcome :passed :seconds (secs t0) :budget seconds
                 :answered-on-poll answered :polls polls :margin (- polls answered)}
                (failed c t0 (str "the check " n ": the app answered, and its probe "
                                  (if timed-out? "did not finish" (str "exited " exit))
                                  ", so it is not this application: "
                                  (str/join " | " (remove str/blank? (str/split-lines (str out err)))))
                        :budget seconds :answered-on-poll answered :polls polls)))))
        (finally (stop! app))))))

(defn- run-check [ctx c]
  (case (:kind c)
    :tests (tests-check ctx c)
    :runs (runs-check ctx c)
    :serves (serves-check ctx c)))

(defn- preflight!
  "Before any check runs: every command found, every port free."
  [build checks]
  (doseq [c checks
          cmd (keep c [:command :start :probe])
          :when (not (found? build cmd))]
    (command/cannot-run! (str "the check " (pr-str (check-name c)) " names the command " (first cmd)
                              ", which is not found: a check that cannot run never passes")))
  (doseq [c checks :when (= :serves (:kind c)) :when (not (port-free? (:port c)))]
    (command/cannot-run! (str "the port " (:port c) " of the check " (pr-str (check-name c))
                              " is held by another process: verify starts nothing rather than measure another process"))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- rel-of [req & parts]
  (str/join "/" (cons (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") parts)))

(defn verify
  "The verify stage: `rdm-spec verify ROOM CYCLE`."
  [req]
  (let [[room-arg cycle] (:args req)
        root (:root req)
        cycle-rel (rel-of req cycle "cycle.edn")
        cf (command/path req cycle-rel)
        _ (when-not (fs/regular-file? cf)
            (command/cannot-run! (str "there is no " cycle-rel ": a cycle is declared in its cycle.edn before it is verified")))
        cyc (try (edn/read-string (slurp (str cf)))
                 (catch Exception e (command/cannot-run! (str cycle-rel " is not readable EDN: " (ex-message e)))))
        cut (when (map? cyc) (:cut cyc))
        _ (when-not (and (string? cut) (not (str/blank? cut)))
            (command/cannot-run! (str cycle-rel " names no :cut: verify reads the cut and nothing else")))
        room (command/path req room-arg)
        _ (when-not (fs/directory? room)
            (command/cannot-run! (str "ROOM " room-arg " is not a directory: verify takes the room the builder built")))
        rv (git root "rev-parse" "-q" "--verify" (str "refs/tags/" cut "^{commit}"))
        _ (when-not (zero? (:exit rv))
            (command/cannot-run! (str "there is no cut " cut " in " root ": verify reads the cut and nothing else")))
        commit (str/trim (:out rv))
        cfg-text (let [r (git root "show" (str commit ":" command/config-file))]
                   (when (zero? (:exit r)) (:out r)))
        cfg (or (try (some-> cfg-text edn/read-string) (catch Exception _ nil))
                (command/cannot-run! (str "the cut " cut " holds no readable " command/config-file)))
        _ (when-let [why (config-problem cfg)] (command/cannot-run! why))
        checks (:verify cfg)
        impl (vec (:implementation cfg))
        build-rel (rel-of req cycle "build")
        build (command/path req build-rel)
        tree (room-tree room impl)
        _ (do (fs/delete-tree build)
              (export! root commit build)
              (doseq [p impl] (fs/delete-tree (fs/file build p)))
              (copy-impl! room build impl))
        _ (preflight! build checks)
        {:keys [pickles refusals]} (runner/plan (str build) {:spec-dir (command/config-value {:config cfg} :spec-dir)
                                                             :id-prefix (command/config-value {:config cfg} :id-prefix)
                                                             :model (command/config-value {:config cfg} :model)})
        unstamped (distinct (for [pk pickles :when (nil? (:id pk))] [(:file pk) (:line pk) (:title pk)]))
        stops (vec (concat
                    (for [r refusals] (command/finding (:file r) (:line r) (:rule r) (:message r)))
                    (for [[f l t] unstamped]
                      (command/finding f l "verify-unstamped"
                                       (str "the scenario \"" t "\" at the cut " cut " carries no single id, so nothing can verify it")))))
        ctx {:build build
             :results-abs (fs/absolutize (command/path req (rel-of req cycle "results")))
             :results-rel (rel-of req cycle "results")
             :log-rel (rel-of req cycle "test.log")
             :log-abs (fs/absolutize (command/path req (rel-of req cycle "test.log")))
             :scenarios (distinct (map #(select-keys % [:id :wip?]) pickles))}
        reports (if (seq stops)
                  (mapv (fn [c] {:name (check-name c) :kind (:kind c) :outcome :not-run}) checks)
                  (loop [[c & more] checks out []]
                    (if-not c
                      out
                      (let [r (run-check ctx c)]
                        (if (= :passed (:outcome r))
                          (recur more (conj out r))
                          (into (conj out r) (for [m more] {:name (check-name m) :kind (:kind m) :outcome :not-run})))))))
        bad (filter #(= :failed (:outcome %)) reports)
        passed? (and (empty? stops) (empty? bad))
        reason (when-not passed?
                 (str/join "; " (concat (map :message stops) (map :reason bad))))
        record (cond-> {:cut cut :room-tree tree :outcome (if passed? :passed :refused) :checkout build-rel :checks reports}
                 reason (assoc :reason reason))
        report-checks (mapv #(dissoc % :reason) reports)]
    (spit (str (command/path req (rel-of req cycle "ratified.edn")))
          (binding [*print-namespace-maps* false] (str (pr-str (assoc record :checks report-checks)) "\n")))
    {:result {:verify {:cycle cycle :cut cut :outcome (if passed? :passed :refused) :checks report-checks}}
     :findings (vec (concat stops
                            (for [c bad] (command/finding (if (= :tests (:kind c)) (:log-rel ctx) nil) nil
                                                          (str "verify-" (name (:kind c)) "-failed")
                                                          (str "the " (name (:kind c)) " check " (pr-str (:name c)) " failed: " (:reason c))))))}))
