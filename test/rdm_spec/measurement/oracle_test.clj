(ns rdm-spec.measurement.oracle-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.oracle :as oracle]))

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "oracle-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(def score {:cycle "01" :items [{:id "spec-0012" :verdict :pass} {:id "spec-0014" :verdict :pass}] :passed 2})

(deftest differences-name-where-and-both-values
  (is (= [] (oracle/differences score score)))
  (let [actual (assoc-in score [:items 1 :verdict] :fail)
        ds (oracle/differences score actual)]
    (is (= [{:path [:items 1 :verdict] :golden :pass :actual :fail}] ds))
    (is (= ":items 1 (spec-0014) :verdict" (oracle/path-text score [:items 1 :verdict]))))
  (testing "a sequence of another length is one difference"
    (is (= [{:path [:items] :golden [1 2] :actual [1]}] (oracle/differences {:items [1 2]} {:items [1]}))))
  (testing "a missing key is nothing"
    (is (= [{:path [:b] :golden 1 :actual :rdm-spec.measurement.oracle/absent}] (oracle/differences {:b 1} {})))))

(deftest a-golden-that-differs-is-a-finding
  (let [[f & more] (oracle/golden-findings :scorer "01" score (assoc-in score [:items 1 :verdict] :fail))
        line (command/format-finding f)]
    (is (nil? more))
    (is (str/includes? line "golden-differs"))
    (doseq [w [":scorer" "\"01\"" "spec-0014" ":pass" ":fail"]] (is (str/includes? line w) w)))
  (testing "many differences are capped, then counted"
    (let [fs* (oracle/golden-findings :checklist "01" (vec (range 15)) (vec (range 1 16)) 3)]
      (is (= 4 (count fs*)))
      (is (str/includes? (:message (last fs*)) "12 more")))))

(deftest comparing-every-golden
  (let [o {:checklist {"01" {:cut "spec-1"}} :scorer {"01" score} :scoreboard {"01-02" {:better 1}}}
        {:keys [compared findings]} (oracle/compare-goldens o {[:checklist "01"] {:cut "spec-1"}
                                                               [:scorer "01"] score
                                                               [:scoreboard "01-02"] {:better 2}})]
    (is (= [{:component :checklist :key "01"} {:component :scorer :key "01"} {:component :scoreboard :key "01-02"}]
           compared))
    (is (= 1 (count findings)))
    (testing "a golden with no output is not compared"
      (is (= 2 (count (:compared (oracle/compare-goldens o {[:checklist "01"] {:cut "spec-1"} [:scorer "01"] score}))))))))

(deftest recording-rewrites-and-says-why
  (let [o {:scorer {"01" score} :scoreboard {"01-02" {:better 1}} :recorded [{:date "d0" :reason "r0" :changed []}]}
        o' (oracle/recorded o {[:scorer "01"] score [:scoreboard "01-02"] {:better 2}} "2026-10-25" "moved")]
    (is (= {:better 2} (get-in o' [:scoreboard "01-02"])))
    (is (= [{:date "d0" :reason "r0" :changed []} {:date "2026-10-25" :reason "moved" :changed [:scoreboard]}]
           (:recorded o')))))

(deftest the-new-digest-keeps-the-configuration
  (let [text ";; a comment\n{:oracle {:repository \"r\"\n          :digest \"sha256:aaa\"}}\n"]
    (is (= ";; a comment\n{:oracle {:repository \"r\"\n          :digest \"sha256:bbb\"}}\n"
           (oracle/with-digest text "sha256:aaa" "sha256:bbb"))))
  (is (= {:oracle {:repository "r" :digest "sha256:c"}}
         (edn/read-string (oracle/with-digest "{:oracle {:repository \"r\" :digest \"x\"}}" "sha256:zzz" "sha256:c")))))

(deftest the-configured-oracle
  (is (nil? (oracle/configured {:config {}})))
  (is (= {:repository "r" :digest (str "sha256:" (apply str (repeat 64 "a")))}
         (oracle/configured {:config {:oracle {:repository "r" :digest (str "sha256:" (apply str (repeat 64 "a")))}}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":oracle" (oracle/configured {:config {:oracle {:repository "r"}}})))
  (is (= oracle/no-oracle (get-in (oracle/oracle {:task "oracle" :config {}}) [:result :oracle :note]))))

(deftest read-by-digest-from-a-layout
  (with-tmp
    (fn [d]
      (let [layout (str (fs/file d "layout"))
            src (fs/file d "src")
            _ (fs/create-dirs src)
            _ (spit (str (fs/file src "oracle.edn")) "{:cycles {}}\n")
            _ (p/shell {:dir (str src) :out :string :err :string} "oras" "push" "--oci-layout" layout
                       "oracle.edn:application/edn" "--artifact-type" oracle/artifact-type)
            req {:env (assoc (into {} (System/getenv)) "ORACLE_LAYOUT" layout)}
            digest (oracle/store! req {:repository "r"} {:cycles {"01" {}}})]
        (is (re-matches #"sha256:[0-9a-f]{64}" digest))
        (is (= {:cycles {"01" {}}} (edn/read-string (oracle/fetch req {:repository "r" :digest digest}))))
        (let [e (try (oracle/fetch req {:repository "r" :digest (str "sha256:" (apply str (repeat 64 "0")))})
                     (catch Exception e e))]
          (is (command/cannot-run? e))
          (is (str/includes? (ex-message e) "r@sha256:000")))
        (testing "an oracle with no fixture cycle judged nothing"
          (let [cfg-digest (oracle/store! req {:repository "r"} {:cycles {}})
                out (oracle/judge (assoc req :config {:oracle {:repository "r" :digest cfg-digest}}))]
            (is (= ["oracle-empty"] (map :rule (:findings out))))))))))
