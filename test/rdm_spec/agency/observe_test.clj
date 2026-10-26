(ns rdm-spec.agency.observe-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.agency.observe :as observe]
            [rdm-spec.kernel.command :as command]))

(deftest what-a-run-left-behind
  (testing "the entries added to or removed from a watched directory"
    (is (= ["/w/b" "/w/c"] (observe/changed-entries "/w" ["a" "c"] ["a" "b"]))))
  (testing "a write is not outside the room when it is the room, or holds it"
    (is (not (observe/outside? "/w/room" ["/w/room"])))
    (is (not (observe/outside? "/w" ["/w/room"])))
    (is (observe/outside? "/w/other" ["/w/room"]))))

(deftest a-run-breaches
  (is (not (observe/breached? {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room false})))
  (is (observe/breached? {:writes-outside ["/x"] :tmp-left 0 :repo-touched [] :results-in-room false}))
  (is (observe/breached? {:writes-outside [] :tmp-left 1 :repo-touched [] :results-in-room false}))
  (is (observe/breached? {:writes-outside [] :tmp-left 0 :repo-touched ["a"] :results-in-room false}))
  (is (observe/breached? {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room true})))

(deftest two-cycles-side-by-side
  (let [clean {:writes-outside [] :tmp-left 0 :repo-touched [] :results-in-room false :unobserved []}
        r (observe/report-of ["12" "13"]
                             {"12" [{:minutes 1.5 :tokens 100 :defence clean}
                                    {:minutes 2.25 :tokens nil :defence (assoc clean :tmp-left 2)}]
                              "13" [{:minutes 3.0 :tokens 7 :defence clean}]})]
    (is (= {:observe {:cycles ["12" "13"] :runs {"12" 2 "13" 1} :minutes {"12" 3.75 "13" 3.0}
                      :tokens {"12" 100 "13" 7} :breaches {"12" 1 "13" 0}}}
           r))))

(defn- with-project [f]
  (let [d (str (fs/create-temp-dir {:prefix "observe-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest a-run-is-recorded-begin-to-end
  (with-project
    (fn [d]
      (let [w1 (str (fs/create-dirs (fs/file d "w1")))
            t (str (fs/create-dirs (fs/file d "t")))
            room (str (fs/create-dirs (fs/file d "room")))
            project (str (fs/create-dirs (fs/file d "project")))
            req (fn [args opts] {:root project :config {:agency {:watch [w1] :tmp t}} :args args :opts opts
                                 :env {"HOME" d}})]
        (testing "ending a run that never began cannot run"
          (is (command/cannot-run? (try (observe/observe (req ["end" "13" "builder"] {})) nil (catch Exception e e)))))
        (observe/observe (req ["begin" "13" "builder" room] {}))
        (spit (str (fs/file w1 "stray.txt")) "x")
        (spit (str (fs/file t "left")) "x")
        (let [out (observe/observe (req ["end" "13" "builder"] {:tokens "1200"}))
              runs (edn/read-string (slurp (str (fs/file project "dhf/cycles/13/runs.edn"))))
              run (last runs)]
          (is (= 1 (count runs)))
          (is (= #{:role :room :began :ended :minutes :tokens :defence} (set (keys run))))
          (is (= #{:writes-outside :tmp-left :repo-touched :results-in-room :unobserved} (set (keys (:defence run)))))
          (is (= 1200 (:tokens run)))
          (is (= [(str w1 "/stray.txt")] (get-in run [:defence :writes-outside])))
          (is (= 1 (get-in run [:defence :tmp-left])))
          (is (some #(str/includes? % "reads outside the room") (get-in run [:defence :unobserved])))
          (is (some #(str/includes? (:message %) "stray.txt") (:findings out)))
          (is (every? command/warning? (:findings out)))
          (is (not (fs/exists? (fs/file project "dhf/cycles/13/observing.edn")))))))))
