(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.knowledge.sources :as sources]))

(defn- write! [dir rel text]
  (fs/create-dirs (fs/parent (fs/file dir rel)))
  (spit (str (fs/file dir rel)) text))

(def index
  {:loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md"
           :pins ["17d68d2"] :fenced ["impl" "harness/fence.sh"]}})

(defn- project
  ([] (project index))
  ([idx]
   (let [dir (str (fs/create-temp-dir {:prefix "sources-test-"}))]
     (write! dir "dhf/raw/sources.edn" (pr-str idx))
     (write! dir "dhf/raw/loyly.md" "# Source: loyly\n")
     (write! dir "dhf/spec/needs.edn"
             "{\"UN-001\" {:text \"t\"\n           :sources [\"loyly:README.md@17d68d2\"]}}\n")
     dir)))

(defn- req [dir & [opts]]
  {:root dir :opts (or opts {}) :bus (bus/make-bus) :state (atom {})
   :config {:raw-dir "dhf/raw" :needs "dhf/spec/needs.edn" :wiki-dir "dhf/wiki"}})

(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string}
         "git" "-c" "user.name=t" "-c" "user.email=t@example.org" "-c" "commit.gpgsign=false" args))

(defn- checkout! [base id pin paths]
  (let [d (fs/file base id)]
    (doseq [p paths] (write! d p "x"))
    (git! d "init" "-q") (git! d "add" "-A") (git! d "commit" "-q" "-m" "c") (git! d "tag" pin)
    (str base)))

(defn- resolve-one [dir citation & [opts]]
  (let [r (req dir opts)
        co (sources/checkouts r)]
    ((sources/resolver r (sources/read-index r) co) {:citation citation})))

(defn- rules [fs*] (set (map :rule fs*)))

(deftest parsing-citations
  (is (= {:source "loyly" :path "docs/x.md" :commit "17d68d2"} (sources/parse-citation "loyly:docs/x.md@17d68d2")))
  (is (nil? (sources/parse-citation "loyly:docs/x.md")))
  (is (sources/pin-matches? "17d68d2" "17D68D2abc"))
  (is (not (sources/pin-matches? "17d68d2" "0000000")))
  (is (= "impl" (sources/fence-of ["impl"] "impl/a.clj")))
  (is (nil? (sources/fence-of ["impl"] "implementation.md"))))

(deftest resolving-without-a-checkout
  (let [dir (project)]
    (testing "unknown source"
      (let [c (resolve-one dir "innolitix:README.md@f21b508")]
        (is (= :failed (:status c)))
        (is (str/includes? (:why c) "innolitix"))))
    (testing "commit not a pin"
      (let [c (resolve-one dir "loyly:README.md@0000000")]
        (is (= :failed (:status c)))
        (is (str/includes? (:why c) "0000000"))
        (is (str/includes? (:why c) "17d68d2"))))
    (testing "fenced path and fenced directory"
      (is (= ["citation-fenced"] (map :rule (:reasons (resolve-one dir "loyly:harness/fence.sh@17d68d2")))))
      (is (str/includes? (:why (resolve-one dir "loyly:impl/x.clj@17d68d2")) "fenced directory \"impl\"")))
    (testing "unchecked, never passed"
      (let [c (resolve-one dir "loyly:README.md@17d68d2")]
        (is (= :unchecked (:status c)))
        (is (false? (:checked? c)))
        (is (false? (:resolved? c)))))))

(deftest resolving-with-a-checkout
  (let [dir (project)
        base (checkout! (str (fs/create-temp-dir)) "loyly" "17d68d2" ["README.md" "impl/a.clj"])]
    (is (= :resolved (:status (resolve-one dir "loyly:README.md@17d68d2" {:sources base}))))
    (let [c (resolve-one dir "loyly:docs/plan.md@17d68d2" {:sources base})]
      (is (= :failed (:status c)))
      (is (str/includes? (:why c) "docs/plan.md")))
    (testing "the fenced path that matches nothing at the pin"
      (let [r (req dir {:sources base})
            fs* (sources/fence-findings r (sources/read-index r) (sources/checkouts r))]
        (is (= ["fenced-path-missing"] (map :rule fs*)))
        (is (str/includes? (:message (first fs*)) "harness/fence.sh"))))
    (testing "a missing --sources directory cannot run"
      (is (thrown? clojure.lang.ExceptionInfo (sources/checkouts (req dir {:sources "/no/such/dir"})))))))

(deftest the-index
  (let [dir (project (assoc index :demo {:repository "https://example.org/demo" :pins []}))
        r (req dir)
        fs* (sources/index-findings r (sources/read-index r))]
    (is (= #{"source-pin" "source-record"} (rules fs*)))
    (is (every? #(str/includes? (:message %) "demo") fs*)))
  (testing "a pin dropped since HEAD"
    (let [dir (project)]
      (git! dir "init" "-q") (git! dir "add" "-A") (git! dir "commit" "-q" "-m" "c")
      (write! dir "dhf/raw/sources.edn" (pr-str (assoc-in index [:loyly :pins] ["9abcdef"])))
      (let [r (req dir)
            fs* (sources/index-findings r (sources/read-index r))]
        (is (= ["pin-dropped"] (map :rule fs*)))
        (is (str/includes? (:message (first fs*)) "17d68d2")))))
  (testing "no index"
    (let [dir (project)]
      (fs/delete (fs/file dir "dhf/raw/sources.edn"))
      (is (= #{"sources-index"} (rules (sources/index-findings (req dir) (sources/read-index (req dir)))))))))

(deftest the-stage-publishes-and-reports
  (let [dir (project)
        r (req dir)
        out (sources/check r)
        evs (bus/events (:bus r))]
    (is (= [:KnowledgeIndexed] (map :event evs)))
    (is (= [{:citation "loyly:README.md@17d68d2" :file "dhf/spec/needs.edn" :line 2 :status :unchecked
             :why "no checkout of loyly was given with --sources, so whether README.md exists at 17d68d2 is unchecked"}]
           (get-in out [:result :knowledge :citations])))
    (is (= [:warning] (map :severity (:findings out))))
    (is (str/includes? (:message (first (:findings out))) "UN-001 cites loyly:README.md@17d68d2"))))
