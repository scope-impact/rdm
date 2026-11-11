;; clojure.test -> Allure, from outside the code under test.
;;
;; Put this directory on the JVM's boot class path and Clojure loads this file
;; at start-up, before any test runner:
;;
;;   ALLURE_RESULTS_DIR=/abs/allure-results \
;;   JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Xbootclasspath/a:/abs/this-dir" ./test.sh
;;
;; It does nothing unless ALLURE_RESULTS_DIR is set. Each test var that runs
;; is written, when it ends, as one Allure result (<uuid>-result.json): failed
;; on a failed assertion, broken on an error, else passed. A var claims a
;; requirement through a `tag` label: its :item metadata ("@item-06-001"), else
;; an id at the start of its name (item-01-001-...). ALLURE_COMMIT, when set,
;; labels every result with the commit under test (and ALLURE_WORKTREE=dirty
;; marks a checkout that had uncommitted changes).
(when-let [dir (System/getenv "ALLURE_RESULTS_DIR")]
  (require 'clojure.test)
  (let [report @(resolve 'clojure.test/report)
        current (atom nil)
        commit (System/getenv "ALLURE_COMMIT")
        worktree (System/getenv "ALLURE_WORKTREE")
        id-pattern (re-pattern (or (System/getenv "ALLURE_ID_PATTERN") "item-\\d+-\\d+"))
        esc (fn [s] (-> (str s)
                        (.replace "\\" "\\\\") (.replace "\"" "\\\"")
                        (.replace "\n" "\\n") (.replace "\r" "\\r") (.replace "\t" "\\t")
                        (clojure.string/replace #"[\x00-\x1f]" "")))
        json (fn json [v]
               (cond (map? v) (str "{" (clojure.string/join "," (map (fn [[k x]] (str (json (name k)) ":" (json x))) v)) "}")
                     (sequential? v) (str "[" (clojure.string/join "," (map json v)) "]")
                     (number? v) (str v)
                     :else (str "\"" (esc v) "\"")))
        claim (fn [v]
                (let [m (meta v)
                      tagged (some-> (:item m) str (clojure.string/replace #"^@" ""))]
                  (or tagged (re-find (re-pattern (str "^" id-pattern)) (str (:name m))))))
        write! (fn [{:keys [var start fails errors]}]
                 (let [m (meta var)
                       nsn (str (ns-name (:ns m)))
                       full (str nsn "/" (:name m))
                       uuid (str (java.util.UUID/randomUUID))
                       status (cond (seq errors) "broken" (seq fails) "failed" :else "passed")
                       problem (first (concat errors fails))
                       id (claim var)]
                   (.mkdirs (java.io.File. dir))
                   (spit (java.io.File. dir (str uuid "-result.json"))
                         (json (cond-> {:uuid uuid
                                        :historyId full
                                        :name (str (:name m))
                                        :fullName full
                                        :status status
                                        :stage "finished"
                                        :start start
                                        :stop (System/currentTimeMillis)
                                        :labels (cond-> [{:name "framework" :value "clojure.test"}
                                                         {:name "suite" :value nsn}]
                                                  id (conj {:name "tag" :value id})
                                                  (seq commit) (conj {:name "commit" :value commit})
                                                  (= "dirty" worktree) (conj {:name "worktree" :value "dirty"}))}
                                 problem (assoc :statusDetails {:message (str problem)}))))))]
    (alter-var-root
     (resolve 'clojure.test/report)
     (constantly
      (fn [m]
        (case (:type m)
          :begin-test-var (reset! current {:var (:var m) :start (System/currentTimeMillis) :fails [] :errors []})
          :fail (when @current
                  (swap! current update :fails conj
                         (str (:message m) " expected: " (pr-str (:expected m)) " actual: " (pr-str (:actual m)))))
          :error (when @current
                   (swap! current update :errors conj
                          (str (:message m) " " (let [a (:actual m)] (if (instance? Throwable a) (str a) (pr-str a))))))
          :end-test-var (when-let [c @current] (write! c) (reset! current nil))
          nil)
        (report m))))))
