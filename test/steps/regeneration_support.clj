(ns steps.regeneration-support
  "Fixtures the regeneration steps share: throwaway git repositories (a local
  bare repository as origin), built in temporary directories, never the room's
  own repository nor any real remote."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [steps.support :as s]))

(def git-env
  {"GIT_AUTHOR_NAME" "Fixture" "GIT_AUTHOR_EMAIL" "fixture@example.invalid"
   "GIT_COMMITTER_NAME" "Fixture" "GIT_COMMITTER_EMAIL" "fixture@example.invalid"
   "GIT_TERMINAL_PROMPT" "0"})

(defn git
  "Runs git in `dir`: {:exit :out :err}, :out trimmed."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env git-env}
                 "git" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    {:exit (:exit r) :out (str/trim (:out r)) :err (:err r)}))

(defn git!
  "Runs git in `dir` and returns its trimmed output; fails the step on an error."
  [dir & args]
  (let [r (apply git dir args)]
    (when-not (zero? (:exit r))
      (s/fail! (str "git " (str/join " " args) " failed in " dir ": " (:err r))))
    (:out r)))

(defn lines [s] (vec (remove str/blank? (str/split-lines (or s "")))))

(defn commit-all! [dir message]
  (git! dir "add" "-A" "-f" ".")
  (git! dir "commit" "-q" "--allow-empty" "-m" message)
  (git! dir "rev-parse" "HEAD"))

;; --- cut fixtures ------------------------------------------------------------

(defn cut-repo!
  "A working repository `work` whose origin is a local bare repository, main
  pushed and origin/HEAD set to origin/main. Returns {:work :origin}."
  []
  (let [base (s/tmp-dir)
        origin (str (fs/file base "origin.git"))
        work (str (fs/file base "work"))]
    (git! base "init" "-q" "--bare" "-b" "main" origin)
    (git! base "init" "-q" "-b" "main" work)
    (s/write! work "dhf/harness/config.edn" (pr-str (s/room-config)))
    (s/write! work "README.md" "A project.\n")
    (commit-all! work "First merge")
    (git! work "remote" "add" "origin" origin)
    (git! work "push" "-q" "-u" "origin" "main")
    (git! work "remote" "set-head" "origin" "main")
    {:work work :origin origin}))

(defn merge-commit!
  "Adds a commit on the current branch of `work` (a stand-in for a merge)."
  [work n]
  (s/write! work (str "dhf/spec/change-" n ".md") (str "Change " n ".\n"))
  (commit-all! work (str "Merge change " n)))

(defn tags-at
  "The tags of `dir` (a working or bare repository) pointing at `rev`."
  [dir rev]
  (sort (lines (:out (git dir "tag" "--points-at" rev)))))

(defn all-tags [dir] (sort (lines (:out (git dir "tag" "--list")))))

;; --- fence fixtures ----------------------------------------------------------

(def secret-content "THE PREVIOUS IMPLEMENTATION: must never reach a clean room\n")

(def implementation-files
  "Implementation files the fixture holds at its cut, beside the spec."
  {"src/rdm_spec/kernel/command.clj" "(ns rdm-spec.kernel.command)\n"
   "src/rdm_spec/verification/status.clj" "(ns rdm-spec.verification.status)\n"
   "src/rdm_spec/verification/results.clj" "(ns rdm-spec.verification.results)\n"
   "src/rdm_spec/spec/parser.clj" "(ns rdm-spec.spec.parser)\n"
   "src/rdm_spec/spec/stamper.clj" "(ns rdm-spec.spec.stamper)\n"
   "src/rdm_spec/spec/parser_helpers/x.clj" "(ns rdm-spec.spec.parser-helpers.x)\n"
   "test/rdm_spec/spec/parser_test.clj" "(ns rdm-spec.spec.parser-test)\n"
   "test/rdm_spec/spec/stamper_test.clj" "(ns rdm-spec.spec.stamper-test)\n"
   "test/rdm_spec/verification/status_test.clj" "(ns rdm-spec.verification.status-test)\n"
   "test/steps/verification_status.clj" "(ns steps.verification-status)\n"
   "test.clj" "(println \"tests\")\n"
   "bb.edn" "{:paths [\"src\" \"test\"]}\n"})

(def other-files
  "Files at the cut that are neither allowed nor implementation."
  {"README.md" "The project.\n"
   "dhf/decisions/adr-0001-old-decision.md" "# An older decision, not allowed\n"
   "dhf/cycles/01/score.edn" "{:score :secret-measurement}\n"
   "dhf/allure-results/x-result.json" "{}\n"
   "site/index.html" "<html></html>\n"})

(defn fence-repo!
  "A repository holding a copy of the room's allowed paths, implementation and
  other files, with history: a first commit holding a previous implementation
  later deleted, the cut (tagged spec-3), a commit after the cut and an
  uncommitted change. Options: :without — allowed paths absent at the cut (and
  added only after it). Returns {:dir :cut-sha :secret-blob :head-sha}."
  ([] (fence-repo! {}))
  ([{:keys [without]}]
   (let [dir (s/copy-of-room!)
         held {}]
     (git! dir "init" "-q" "-b" "main")
     ;; a previous implementation, in history only
     (s/write! dir "old/legacy_impl.py" secret-content)
     (s/write! dir "src/rdm_spec/old_impl.clj" secret-content)
     (let [_ (commit-all! dir "Previous implementation")
           secret-blob (git! dir "rev-parse" "HEAD:old/legacy_impl.py")]
       (fs/delete-tree (fs/file dir "old"))
       (fs/delete (fs/file dir "src/rdm_spec/old_impl.clj"))
       (doseq [[f c] (merge implementation-files other-files held)] (s/write! dir f c))
       ;; an executable file and a binary one among the allowed paths
       (s/write! dir "dhf/harness/run.sh" "#!/bin/sh\necho hi\n")
       (.setExecutable (fs/file dir "dhf/harness/run.sh") true)
       (fs/write-bytes (fs/file dir "dhf/raw/blob.bin") (byte-array (map unchecked-byte (range 256))))
       (let [moved (into {} (for [w without :let [src (fs/file dir w)] :when (fs/exists? src)]
                              (let [tmp (fs/file (s/tmp-dir) "held")]
                                (fs/move src tmp)
                                [w (str tmp)])))
             cut-sha (commit-all! dir "Merge: the cut")]
         (git! dir "tag" "spec-3")
         ;; after the cut: the allowed paths held back come in, files change
         (doseq [[w tmp] moved] (fs/move tmp (fs/file dir w)))
         (s/write! dir "src/rdm_spec/spec/parser.clj" "(ns rdm-spec.spec.parser) ;; after the cut\n")
         (s/write! dir "dhf/spec/after-cut.md" "After the cut.\n")
         (let [head-sha (commit-all! dir "Merge: after the cut")]
           ;; uncommitted changes in the working tree
           (spit (fs/file dir "dhf/spec/README.md") "\nUncommitted.\n" :append true)
           (s/write! dir "src/rdm_spec/verification/uncommitted.clj" "(ns x)\n")
           {:dir dir :cut-sha cut-sha :secret-blob secret-blob :head-sha head-sha}))))))

(defn tree-entries
  "{path object-id} of the blobs under `paths` in `rev` of the repository `dir`."
  [dir rev paths]
  (into {} (for [l (lines (apply git! dir "ls-tree" "-r" "--full-tree" rev "--" paths))
                 :let [[meta path] (str/split l #"\t" 2)
                       [_mode type obj] (str/split meta #" ")]
                 :when (= "blob" type)]
             [path obj])))

(defn room-files
  "Every file in the working tree of `room`, outside .git, relative, with /."
  [room]
  (sort (for [f (fs/glob room "**" {:hidden true})
              :let [r (str/replace (str (fs/relativize room f)) "\\" "/")]
              :when (and (not (fs/directory? f))
                         (not= ".git" r)
                         (not (str/starts-with? r ".git/")))]
          r)))

(defn hash-of [room rel] (git! room "hash-object" "--" rel))
