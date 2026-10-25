(ns steps.project-adopt
  "Step definitions for dhf/spec/project/adopt.feature: `rdm-spec adopt` in
  a git repository of the scenario's own. Context keys: see
  steps.project-support."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(def ^:private source-and-readme
  {"README.md" "# Sauna booking\n\nBooks sauna turns.\n"
   "src/booking.c" "int main(void) { return 0; }\n"})

(def ^:private source-and-tests
  (merge source-and-readme
         {"tests/booking_test.c" "int test_booking(void) { return 0; }\n"
          "tests/features/booking.feature" "Feature: Booking\n  Scenario: A turn is booked\n    Given a free turn\n"}))

(defn- repo-ctx [ctx files]
  (ps/needs-project!)
  (let [dir (ps/repository! files)]
    (-> ctx
        (assoc :dir dir :repo-files (ps/files-of dir))
        ;; never look above the fixture for a repository
        (assoc-in [:env "GIT_CEILING_DIRECTORIES"] (str (fs/parent dir))))))

(defn- adopted [ctx] (ps/result-of (kc/exits! ctx 0) :adopt))

(defstep #"a git repository holding source code and a README"
  (fn [ctx] (repo-ctx ctx source-and-readme)))

(defstep #"a git repository holding source code and tests"
  (fn [ctx] (repo-ctx ctx source-and-tests)))

(defstep #"it holds the skeleton, as \"rdm-spec init\" writes it"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [dir (:dir ctx)
          ;; init, into a path of the same name, writes the same skeleton
          parent (s/tmp-dir)
          nm (str (fs/file-name dir))
          r (kc/run! ctx parent ["init" nm])
          _ (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
          inited (ps/files-of (fs/file parent nm))
          have (ps/files-of dir)]
      (doseq [rel (sort (ps/skeleton-paths))]
        (s/check! (= (get inited rel) (get have rel)) (str rel " is not as init writes it")))
      (s/check! (= ".githooks" (ps/git! dir "config" "--get" "core.hooksPath")) "core.hooksPath is not .githooks")
      nil)))

(defstep #"its source code and README are unchanged"
  (fn [ctx]
    (let [now (ps/files-of (:dir ctx))]
      (doseq [[rel text] (:repo-files ctx)]
        (s/check! (= text (get now rel)) (str rel " changed")))
      nil)))

(defstep #"adopt lists every file it wrote"
  (fn [ctx]
    (let [res (adopted ctx)]
      (s/check! (= (ps/skeleton-paths) (set (:wrote res))) (str "adopt lists " (sort (:wrote res))))
      nil)))

(defstep #"a git repository that already holds (\S+)"
  (fn [ctx rel]
    (assoc (repo-ctx ctx (assoc source-and-readme rel "# Our own glossary\n\n**Turn**: an hour in the sauna.\n"))
           :kept rel)))

(defstep #"(dhf/\S+) is unchanged"
  (fn [ctx rel]
    (s/check! (= (get (:repo-files ctx) rel) (get (ps/files-of (:dir ctx)) rel)) (str rel " changed"))
    nil))

(defstep #"adopt names it as kept"
  (fn [ctx]
    (let [res (adopted ctx)]
      (s/check! (some #{(:kept ctx)} (:kept res)) (str "adopt does not name " (:kept ctx) " as kept: " (pr-str res)))
      (s/check! (not-any? #{(:kept ctx)} (:wrote res)) (str "adopt says it wrote " (:kept ctx))))
    nil))

(defstep #"a git repository where (\S+) is a symbolic link"
  (fn [ctx rel]
    (let [ctx (repo-ctx ctx (assoc source-and-readme "our-config.edn" "{:id-prefix \"spec\"}\n"))
          link (fs/file (:dir ctx) rel)]
      (fs/create-dirs (fs/parent link))
      (fs/create-sym-link link (fs/relativize (fs/parent link) (fs/file (:dir ctx) "our-config.edn")))
      (assoc ctx :kept rel :repo-files (ps/files-of (:dir ctx))))))

(defstep #"the link and its target are unchanged"
  (fn [ctx]
    (let [link (fs/file (:dir ctx) (:kept ctx))
          now (ps/files-of (:dir ctx))]
      (s/check! (fs/sym-link? link) "the link is no longer a symbolic link")
      (s/check! (= (get (:repo-files ctx) (:kept ctx)) (get now (:kept ctx))) "the link changed")
      (s/check! (= (get (:repo-files ctx) "our-config.edn") (get now "our-config.edn")) "the link's target changed")
      nil)))

(defstep #"adopt names the link as kept"
  (fn [ctx]
    (let [res (adopted ctx)]
      (s/check! (some #{(:kept ctx)} (:kept res)) (str "adopt does not name " (:kept ctx) " as kept: " (pr-str res))))
    nil))

(defstep #"a git repository with its own \.gitignore"
  (fn [ctx] (repo-ctx ctx (assoc source-and-readme ".gitignore" "build/\n*.o\n"))))

(defstep #"the \.gitignore is unchanged"
  (fn [ctx]
    (s/check! (= (get (:repo-files ctx) ".gitignore") (get (ps/files-of (:dir ctx)) ".gitignore")) ".gitignore changed")
    nil))

(defstep #"adopt names the lines it would have added, those init writes to \.gitignore"
  (fn [ctx]
    (let [res (adopted ctx)
          parent (s/tmp-dir)
          _ (kc/run! ctx parent ["init" "x"])
          init-lines (->> (str/split-lines (slurp (str (fs/file parent "x" ".gitignore"))))
                          (map str/trim) (remove #(or (str/blank? %) (str/starts-with? % "#"))) vec)]
      (s/check! (= ps/gitignore-lines init-lines) (str "init writes " init-lines))
      (s/check! (some #{".gitignore"} (:kept res)) (str "adopt does not name .gitignore as kept: " (pr-str res)))
      (s/check! (= init-lines (get-in res [:gitignore :lines-to-add]))
                (str "adopt names " (pr-str (:gitignore res)) ", not " init-lines))
      nil)))

(defstep #"a repository that has been adopted"
  (fn [ctx]
    (let [ctx (repo-ctx ctx source-and-readme)
          r (kc/run! ctx (:dir ctx) ["adopt"])]
      (s/check! (zero? (:exit r)) (str "the first adopt failed\n" (s/show r)))
      (assoc ctx :repo-files (ps/files-of (:dir ctx))
             :git-config (slurp (str (fs/file (:dir ctx) ".git/config")))))))

(defstep #"no file changes"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [now (ps/files-of (:dir ctx))
          changed (sort (filter #(not= (get (:repo-files ctx) %) (get now %))
                                (distinct (concat (keys (:repo-files ctx)) (keys now)))))]
      (s/check! (empty? changed) (str "files changed: " (str/join ", " changed)))
      (s/check! (= (:git-config ctx) (slurp (str (fs/file (:dir ctx) ".git/config")))) ".git/config changed")
      nil)))

(defstep #"adopt names every destination as kept"
  (fn [ctx]
    (let [res (adopted ctx)]
      (s/check! (= (ps/skeleton-paths) (set (:kept res))) (str "adopt names as kept " (sort (:kept res))))
      (s/check! (empty? (:wrote res)) (str "adopt wrote " (:wrote res)))
      nil)))

(defstep #"a directory that is not a git repository"
  (fn [ctx]
    (ps/needs-project!)
    (let [dir (s/tmp-dir)]
      (s/write! dir "notes.txt" "Not a repository.\n")
      (-> ctx
          (assoc :dir dir :repo-files (ps/files-of dir))
          (assoc-in [:env "GIT_CEILING_DIRECTORIES"] (str (fs/parent dir)))))))

(defstep #"it exits 2, says the hook needs a git repository, and writes nothing"
  (fn [ctx]
    (kc/refused-naming! ctx "the hook needs a git repository")
    (s/check! (= (:repo-files ctx) (ps/files-of (:dir ctx))) "adopt wrote something")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) ".git"))) "adopt made a repository")
    nil))

(defstep #"the model, the needs and the ledger are empty"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [rd #(edn/read-string (slurp (str (fs/file (:dir ctx) %))))]
      (s/check! (= #{:system} (set (keys (rd "dhf/spec/model.edn")))) "the model holds more than the system")
      (s/check! (= {} (rd "dhf/spec/needs.edn")) "the needs are not empty")
      (s/check! (= {:issued 0} (rd "dhf/spec/ids.edn")) "the ledger is not at 0"))
    nil))

(defstep #"no feature file is written"
  (fn [ctx]
    (let [res (adopted ctx)
          new (remove (set (keys (:repo-files ctx))) (keys (ps/files-of (:dir ctx))))]
      (s/check! (not-any? #(str/ends-with? % ".feature") new) (str "adopt wrote a feature file: " (vec new)))
      (s/check! (not-any? #(str/ends-with? % ".feature") (:wrote res)) "adopt lists a feature file")
      nil)))
