(ns rdm-spec.measurement.oracle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.oracle :as oracle]))

(defn- cannot-run [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (when (command/cannot-run? e) (ex-message e)))))

(def digest (str "sha256:" (apply str (repeat 64 "a"))))

(deftest the-configured-oracle
  (is (nil? (oracle/configured {})))
  (is (= {:repository "r" :digest digest} (oracle/configured {:oracle {:repository "r" :digest digest}})))
  (doseq [o [nil "r@sha256:x" {:repository "r"} {:repository "r" :digest "latest"}]]
    (is (cannot-run #(oracle/configured {:oracle o})) (pr-str o))))

(deftest reading-oracle-edn
  (is (= {:cycles {}} (oracle/read-oracle "{:cycles {}}" "x")))
  (testing "what cannot be read cannot run"
    (doseq [t ["{" "[]" "{:cycles []}" "{:project {\"../x\" \"t\"}}" "{:project {\"/x\" \"t\"}}"
               "{:scoreboard {\"0102\" {}}}" "{:cycles {\"01\" {}}}"]]
      (is (cannot-run #(oracle/read-oracle t "x")) t))))

(deftest differences-name-the-item
  (is (= [] (oracle/differences {:a 1} {:a 1})))
  (is (= [{:path [:items "b" :verdict] :golden :pass :produced :fail}]
         (oracle/differences {:items [{:id "a" :verdict :pass} {:id "b" :verdict :pass}]}
                             {:items [{:id "a" :verdict :pass} {:id "b" :verdict :fail}]})))
  (is (= [{:path [:x 1] :golden 2 :produced 3}] (oracle/differences {:x [1 2]} {:x [1 3]})))
  (is (= [{:path [:items "b"] :golden {:id "b"} :produced nil}]
         (oracle/differences {:items [{:id "a"} {:id "b"}]} {:items [{:id "a"}]})))
  (let [line (command/format-finding (oracle/difference-finding :scorer "01" {:path [:items "b" :verdict] :golden :pass :produced :fail}))]
    (is (every? #(str/includes? line %) ["oracle-golden-differs" ":scorer" "\"01\"" "\"b\"" ":pass" ":fail"]))))

(def fixture
  {:cycles {"01" {:cycle {:cut "spec-1"}}}
   :checklist {"01" {:cycle "01" :items []}}
   :scorer {"01" {:passed 1}}
   :scoreboard {"01-02" {:compared 1}}})

(deftest judging
  (testing "no fixture cycle judged nothing"
    (is (= ["oracle-empty"] (map :rule (:findings (oracle/judge {:cycles {}} {}))))))
  (let [outputs {[:checklist "01"] {:value {:cycle "01" :items []}}
                 [:scorer "01"] {:value {:passed 0}}
                 [:scoreboard "01-02"] {:why "rdm-spec scoreboard 01 02 could not run"}}
        {:keys [compared findings]} (oracle/judge fixture outputs)]
    (is (= [[:checklist "01"] [:scorer "01"] [:scoreboard "01-02"]] compared))
    (is (= ["oracle-golden-differs" "oracle-output-missing"] (map :rule findings)))))

(deftest recording
  (let [outputs {[:checklist "01"] {:value {:cycle "01" :items []}}
                 [:scorer "01"] {:value {:passed 0}}
                 [:scoreboard "01-02"] {:value {:compared 1}}}
        {:keys [oracle changed missing]} (oracle/rerecord fixture outputs)]
    (is (= [:scorer] changed))
    (is (= [] missing))
    (is (= {:passed 0} (get-in oracle [:scorer "01"])))
    (is (= (:checklist fixture) (:checklist oracle))))
  (testing "a golden with no output is missing, never recorded as nothing"
    (is (= [[:scoreboard "01-02" "why"]]
           (:missing (oracle/rerecord fixture {[:checklist "01"] {:value {}} [:scorer "01"] {:value {}}
                                               [:scoreboard "01-02"] {:why "why"}}))))))
