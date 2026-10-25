(ns steps.support
  "Helpers the step definitions share: temporary projects, driving the
  rdm-spec command as a separate process, reading Allure results, and marking
  a scenario broken while a context it needs is not built yet."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def room-root
  "The repository root: four levels above src/rdm_spec/kernel/command.clj."
  (-> (io/resource "rdm_spec/kernel/command.clj") io/file fs/absolutize
      fs/parent fs/parent fs/parent fs/parent str))

(def bb (or (some-> (fs/which "bb") str) "bb"))

(defn fail!
  "Fails the step with `message`."
  [message & [data]]
  (throw (ex-info message (or data {}))))

(defn check!
  "Fails the step with `message` unless `ok`. Returns nil, so a step ending\n  with it passes its context on unchanged."
  [ok message & [data]]
  (when-not ok (fail! message data))
  nil)

(defn built?
  "True when the namespace `ns-sym` exists in the implementation."
  [ns-sym]
  (some? (io/resource (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))))

(defn needs-built!
  "Marks the scenario broken (not failed) while any of the namespaces is not
  built: its steps cannot be verified yet."
  [& ns-syms]
  (let [missing (remove built? ns-syms)]
    (when (seq missing)
      (throw (ex-info (str "cannot be verified yet: " (str/join ", " missing) " is not built")
                      {:rdm-spec.kernel.runner/broken true})))))

(defn tmp-dir []
  (str (fs/create-temp-dir {:prefix "rdm-spec-test-"})))

(defn write!
  "Writes `content` to `rel` under `dir`, creating directories."
  [dir rel content]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit f content)
    (str f)))

(defn room-config []
  (edn/read-string (slurp (str (fs/file room-root "dhf/harness/config.edn")))))

(defn project!
  "A temporary project holding only dhf/harness/config.edn: the room's
  configuration merged with `overrides`."
  ([] (project! {}))
  ([overrides]
   (let [dir (tmp-dir)]
     (write! dir "dhf/harness/config.edn" (pr-str (merge (room-config) overrides)))
     dir)))

(defn copy-of-room!
  "A temporary project holding a copy of the room's allow-listed paths (the
  spec, the harness configuration, the raw sources, the wiki, the ADR)."
  []
  (let [dir (tmp-dir)]
    (doseq [a (get-in (room-config) [:clean-room :allow])
            :let [src (fs/file room-root a)]
            :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/file dir a))
        (do (fs/create-dirs (fs/parent (fs/file dir a)))
            (fs/copy src (fs/file dir a)))))
    dir))

(defn rdm-spec!
  "Runs `rdm-spec args...` as its own process in `dir`, the implementation
  under test on its classpath. Returns {:exit :out :err}."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                 bb "-cp" (str (fs/file room-root "src")) "-m" "rdm-spec.kernel.command" args)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec args)}))

(defn split-args [s] (vec (remove str/blank? (str/split s #"\s+"))))

(defn err-lines [{:keys [err]}] (vec (remove str/blank? (str/split-lines (or err "")))))

(defn read-results
  "Every Allure result in `dir`, keywordized, in no particular order."
  [dir]
  (if (fs/directory? dir)
    (vec (for [f (fs/glob dir "*-result.json")]
           (json/parse-string (slurp (str f)) true)))
    []))

(defn label [result k]
  (some #(when (= k (:name %)) (:value %)) (:labels result)))

(defn labels [result k]
  (vec (keep #(when (= k (:name %)) (:value %)) (:labels result))))

(defn feature
  "The text of a feature file holding `body` under a feature tagged @UN-001."
  [body]
  (str "@UN-001\nFeature: Demo\n  A feature for the runner's tests.\n\n" body "\n"))

(defn step-file
  "A step definition file requiring the runner's step API, then `body`."
  [body]
  (str "(require '[rdm-spec.kernel.runner :refer [defstep]])\n\n" body "\n"))

(defn show [r]
  (str "exit " (:exit r) "\nstdout:\n" (:out r) "\nstderr:\n" (:err r)))
