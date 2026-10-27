(ns rdm-spec.kernel.plugins-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.kernel.plugins :as pl]))

(def config {:implementation ["src" "test" "test.clj"]})

(defn- why [entries] (pl/problem (assoc config :plugins entries)))

(deftest declarations
  (let [ok {:name "lint-a" :subscribes [:SpecParsed] :command ["bb" "dhf/harness/plugins/lint-a/run.clj"] :seconds 20}]
    (is (nil? (why [ok {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}])))
    (is (str/includes? (why [(dissoc ok :subscribes)]) "a plugin subscribes to events or answers a request"))
    (is (str/includes? (why [(assoc ok :answers :Gate)]) "a plugin subscribes to events or answers a request, not both"))
    (let [w (why [(assoc ok :subscribes [:NoSuchEvent])])]
      (is (str/includes? w "rdm-spec publishes no event NoSuchEvent"))
      (is (str/includes? w "SpecParsed")))
    (is (str/includes? (why [(-> ok (dissoc :subscribes) (assoc :answers :Review))]) "the requests are :Gate and :Check"))
    (is (str/includes? (why [ok ok]) "a plugin's name is declared once"))
    (is (str/includes? (why [(assoc ok :command ["bb" "src/x.clj"])]) "a plugin is a judge the builder never writes"))
    (is (str/includes? (why [(assoc ok :command ["./test.clj"])]) "a plugin is a judge the builder never writes"))
    (is (nil? (why [(assoc ok :command ["bb" "srcx/x.clj"])])))
    (testing "the shape is exact"
      (is (why [(assoc ok :colour "red")]))
      (is (why [(assoc ok :subscribes :SpecParsed)]))
      (is (why [(assoc ok :seconds "20")]))
      (is (why [(assoc ok :seconds 0)]))
      (is (why [(dissoc ok :command)]))
      (is (why [(assoc ok :command [])]))
      (is (why [(dissoc ok :name)]))
      (is (why ["not a map"])))
    (testing "every message names the plugin"
      (is (str/includes? (why [(dissoc ok :subscribes)]) "lint-a")))
    (is (= 60 (pl/seconds-of (dissoc ok :seconds))))
    (is (= 20 (pl/seconds-of ok)))))

(deftest relaying
  (testing "exit 1: findings relayed unchanged, warnings as warnings"
    (let [r (pl/relay {:name "p"} {:exit 1 :out "" :err "a/b.feature:4: rule-x: what\nwarning: rule-y: note\n"})]
      (is (= ["a/b.feature:4: rule-x: what"] (map cmd/finding-line (:findings r))))
      (is (= ["warning: rule-y: note"] (map cmd/warning-line (:warnings r))))))
  (testing "a warning line keeps its file"
    (is (= ["a.feature:4: warning: long: x"]
           (map cmd/warning-line (:warnings (pl/relay {:name "p"} {:exit 0 :out "" :err "a.feature:4: warning: long: x\n"}))))))
  (testing "exit 1 with no finding line is still a finding"
    (is (= 1 (count (:findings (pl/relay {:name "p"} {:exit 1 :out "" :err ""}))))))
  (testing "exit 0 with nothing says nothing"
    (is (= {:findings [] :warnings []} (select-keys (pl/relay {:name "p"} {:exit 0 :out "" :err ""}) [:findings :warnings]))))
  (testing "exit 2 cannot run, naming the plugin and relaying its lines"
    (let [e (try (pl/relay {:name "p"} {:exit 2 :out "" :err "cannot read x.yaml\n"}) nil
                 (catch clojure.lang.ExceptionInfo e (:cannot-run (ex-data e))))]
      (is (str/includes? e "p"))
      (is (str/includes? e "cannot read x.yaml"))))
  (testing "a timeout cannot run, naming its limit and keeping what it printed"
    (let [e (try (pl/relay {:name "slow" :seconds 2} {:timed-out true :out "started\n" :err ""}) nil
                 (catch clojure.lang.ExceptionInfo e (:cannot-run (ex-data e))))]
      (is (str/includes? e "timed out after 2 seconds"))
      (is (str/includes? e "started")))))

(deftest running
  (let [d (fs/create-temp-dir {:dir (cmd/tmp-dir) :prefix "plugins-test-"})]
    (try
      (testing "the input on standard input, in the given directory"
        (let [r (pl/run! {:name "cat" :command ["sh" "-c" "cat; pwd >&2"]} {:a 1} {:dir (str d)})]
          (is (= 0 (:exit r)))
          (is (= "{:a 1}" (str/trim (:out r))))
          (is (= (str (fs/canonicalize d)) (str (fs/canonicalize (str/trim (:err r))))))))
      (testing "stopped past its limit, its output kept"
        (let [t0 (System/currentTimeMillis)
              r (pl/run! {:name "slow" :seconds 1 :command ["sh" "-c" "echo started; sleep 5"]} {} {:dir (str d)})]
          (is (:timed-out r))
          (is (str/includes? (:out r) "started"))
          (is (< (- (System/currentTimeMillis) t0) 4000))))
      (testing "a command not found"
        (is (:not-found (pl/run! {:name "ghost" :command ["no-such-plugin-here"]} {} {:dir (str d)}))))
      (finally (fs/delete-tree d)))))

(deftest templates
  (let [d (fs/create-temp-dir {:dir (cmd/tmp-dir) :prefix "plugins-test-"})]
    (try
      (spit (fs/file (fs/create-dirs (fs/path d "a")) "template.edn") (pr-str {:name "a" :version 2 :plugin {:name "a"}}))
      (spit (fs/file d "a" "x.txt") "x")
      (fs/create-dirs (fs/path d "not-a-template"))
      (is (= {"a" 2} (into {} (map (juxt key (comp :version val)) (pl/dir-templates (str d))))))
      (is (= {"template.edn" true "x.txt" true}
             (into {} (map (fn [[k _]] [k true]) (:files (get (pl/dir-templates (str d)) "a"))))))
      (finally (fs/delete-tree d)))))
