(ns rdm-spec.kernel.plugins-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.plugins :as plugins]))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:cannot-run (ex-data e)))))

(def config {:implementation ["src" "test" "test.clj"]})

(deftest declarations
  (testing "a plugin with no :seconds has 60"
    (is (= [{:name "a" :subscribes [:SpecParsed] :command ["sh" "a.sh"] :seconds 60}
            {:name "b" :answers :Gate :command ["sh" "b.sh"] :seconds 5}]
           (plugins/declarations {:plugins [{:name "a" :subscribes [:SpecParsed] :command ["sh" "a.sh"]}
                                            {:name "b" :answers :Gate :command ["sh" "b.sh"] :seconds 5}]})))))

(deftest refusals
  (let [refuse (fn [entries] (refusal #(plugins/validate! (assoc config :plugins entries))))]
    (is (nil? (refuse [{:name "a" :subscribes [:SpecParsed] :command ["sh" "a.sh"]}])))
    (is (str/includes? (refuse [{:name "a" :command ["x"]}]) "a plugin subscribes to events or answers a request"))
    (is (str/includes? (refuse [{:name "a" :command ["x"] :answers :Gate :subscribes [:SpecParsed]}]) "not both"))
    (is (str/includes? (refuse [{:name "a" :command ["x"] :subscribes [:Nope]}]) "rdm-spec publishes no event Nope"))
    (is (str/includes? (refuse [{:name "a" :command ["x"] :answers :Review}]) "the requests are :Gate and :Check"))
    (is (str/includes? (refuse [{:name "a" :command ["x"] :answers :Gate} {:name "a" :command ["y"] :answers :Check}])
                       "a plugin's name is declared once"))
    (is (str/includes? (refuse [{:name "a" :command ["bb" "src/a.clj"] :answers :Gate}]) "a judge the builder never writes"))
    (is (str/includes? (refuse [{:name "a" :command ["x"] :answers :Gate :colour "red"}]) ":colour"))
    (is (str/includes? (refuse [{:name "a" :command [] :answers :Gate}]) "command"))))

(deftest what-a-plugin-says
  (let [p {:name "j" :command ["x"] :seconds 60}]
    (testing "exit 1: its lines are findings, a warning line a warning"
      (let [r (plugins/report p {:exit 1 :out "" :err "a:1: r: x\na:2: warning: w: y\n"})]
        (is (= ["a:1: r: x"] (map :line-text (:findings r))))
        (is (= ["a:2: warning: w: y"] (map :line-text (:warnings r))))))
    (testing "exit 1 with no finding is still a finding"
      (is (= 1 (count (:findings (plugins/report p {:exit 1 :out "" :err ""}))))))
    (testing "exit 0 holds"
      (is (empty? (:findings (plugins/report p {:exit 0 :out "" :err ""})))))
    (testing "exit 2 cannot run, naming the plugin and relaying its lines"
      (is (str/includes? (:cannot-run (plugins/report p {:exit 2 :out "" :err "cannot read x\n"})) "cannot read x")))
    (testing "a timeout is named as one, with what it printed"
      (let [why (:cannot-run (plugins/report p {:timed-out true :out "started\n" :err ""}))]
        (is (str/includes? why "timed out after 60 seconds"))
        (is (str/includes? why "started"))))))

(deftest running-a-plugin
  (let [d (fs/create-temp-dir)]
    (try
      (spit (fs/file d "p.sh") "cat\necho to-err >&2\n")
      (let [r (plugins/run {:name "p" :command ["sh" "p.sh"] :seconds 5} {:event :X} (str d))]
        (is (= 0 (:exit r)))
        (is (= "{:event :X}" (str/trim (:out r))))
        (is (= "to-err" (str/trim (:err r)))))
      (testing "past its time it is stopped"
        (spit (fs/file d "s.sh") "echo started\nexec sleep 5\n")
        (let [t0 (System/currentTimeMillis)
              r (plugins/run {:name "s" :command ["sh" "s.sh"] :seconds 1} {} (str d))]
          (is (:timed-out r))
          (is (< (- (System/currentTimeMillis) t0) 4000))
          (is (str/includes? (:out r) "started"))))
      (testing "a command not found cannot be started"
        (is (:not-found (plugins/run {:name "n" :command ["no-such-command-anywhere"] :seconds 1} {} (str d)))))
      (finally (fs/delete-tree d)))))

(deftest templates
  (is (some #{"ddd-domain"} (plugins/template-names))))
