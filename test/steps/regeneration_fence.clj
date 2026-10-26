(ns steps.regeneration-fence
  "Step definitions of dhf/spec/regeneration/fence.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def impl-model
  (k/model {:contexts [["spec" ["parser" "check"]] ["knowledge" ["sources"]] ["publishing" ["site"]]
                       ["verification" ["status" "results"]]]
            :kernel ["bus" "command"]}))

(defn impl-files
  "An implementation of the model's components, each file's bytes its own."
  [comps]
  (into {"test.clj" "(println :tests)\n"}
        (for [[c n] comps
              [p t] [[(str "src/rdm_spec/" c "/" n ".clj") (str "(ns rdm-spec." c "." n ")\n")]
                     [(str "test/rdm_spec/" c "/" n "_test.clj") (str "(ns rdm-spec." c "." n "-test)\n")]
                     [(str "test/steps/" c "_" n ".clj") (str "(ns steps." c "-" n ")\n")]]]
          [p t])))

(def comps [["spec" "parser"] ["spec" "check"] ["knowledge" "sources"] ["publishing" "site"]
            ["verification" "status"] ["verification" "results"] ["kernel" "bus"] ["kernel" "command"]])

(defn feature-files []
  (into {} (for [[c n] comps]
             [(str "dhf/spec/" c "/" n ".feature")
              (k/feature-text {:name n :tags ["UN-001"] :scenarios [(k/sc (inc (.indexOf comps [c n])) (str c " " n))]})])))

(defn impl-project!
  "A project with an implementation of its model's components."
  [ctx & [more]]
  (k/project! ctx {:features (feature-files)
                   :issued 100
                   :extra (merge {"dhf/spec/model.edn" (k/pr-edn impl-model)} (impl-files comps) more)}))

(defn cut! [ctx tag] (k/tag-cut! ctx tag))

(defn room-files [ctx]
  (let [d (get-in ctx [:ph "ROOM"])]
    (->> (fs/glob d "**" {:hidden true}) (filter fs/regular-file?)
         (map #(str (fs/relativize d %))) (remove #(str/starts-with? % ".git/")) sort vec)))

(defn files-at [ctx rev]
  (vec (sort (remove str/blank? (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "--name-only" rev))))))

(defn impl? [p] (some #(or (= p %) (str/starts-with? p (str % "/"))) ["src" "test" "test.clj"]))

(defn removed-by-scope
  "The implementation files the last command's scope removes."
  [ctx files]
  (let [args (:last-args ctx)
        opt (fn [k] (some->> (drop-while #(not= k %) args) second (#(str/split % #","))))
        prefixes (concat (for [c (opt "--context")] [(str "src/rdm_spec/" c "/") (str "test/rdm_spec/" c "/") (str "test/steps/" c "_")])
                         (for [cn (opt "--component") :let [[c n] (str/split cn #"/")]]
                           [(str "src/rdm_spec/" c "/" n ".clj") (str "test/rdm_spec/" c "/" n "_test.clj") (str "test/steps/" c "_" n ".clj")]))
        ps (apply concat prefixes)]
    (set (filter (fn [f] (some #(str/starts-with? f %) ps)) files))))

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [ctx] (let [ctx (k/project! ctx)] (k/commit! (:dir ctx) "uncut") ctx)))

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "only a cut")
    (assert (not (fs/exists? (get-in ctx [:ph "ROOM"])))) ctx))

(def all-kept
  {:clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site" "site-src" "dhf/allure-results"]}
                :keep [".gitignore" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"]}})

(defstep #"the configuration denies the implementation, the measurements dhf/cycles and the generated site, and keeps every other path"
  (fn [ctx]
    (let [ctx (impl-project! ctx {"site/index.html" "<p>old</p>\n" ".gitignore" "notes/\n"})]
      (k/update-edn! ctx "dhf/harness/config.edn" merge all-kept))))

(defstep #"the cut also holds dhf/cycles/01/score.edn"
  (fn [ctx]
    (k/write-files! (:dir ctx) {"dhf/cycles/01/score.edn" "{:passed 1}\n"})
    (cut! ctx "spec-3")))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [room (get-in ctx [:ph "ROOM"])
          commits (str/split-lines (k/git room "rev-list" "--all"))]
      (assert (= 1 (count commits)) (pr-str commits))
      (assert (= "" (str/trim (k/git room "log" "--format=%P" "-1"))))
      ctx)))

(defn denied? [p] (or (impl? p) (str/starts-with? p "dhf/cycles/") (str/starts-with? p "site/")
                      (str/starts-with? p "site-src/") (str/starts-with? p "dhf/allure-results/")))

(defstep #"its working tree holds every file committed at the cut that is not denied, unchanged, and no other file"
  (fn [ctx]
    (let [want (remove denied? (files-at ctx "spec-3"))]
      (assert (= want (room-files ctx)) (str (pr-str want) " / " (pr-str (room-files ctx))))
      (doseq [f want]
        (assert (= (k/git (:dir ctx) "show" (str "spec-3:" f))
                   (str/trim (slurp (fs/file (get-in ctx [:ph "ROOM"]) f))))
                f))
      ctx)))

(defstep #"no object reachable in it belongs to a denied path"
  (fn [ctx]
    (let [room (get-in ctx [:ph "ROOM"])
          paths (remove str/blank? (str/split-lines (k/git room "log" "--all" "--name-only" "--format=")))]
      (assert (not-any? denied? paths) (pr-str paths))
      (let [denied-blobs (set (for [l (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "spec-3"))
                                    :let [[_ _ sha p] (re-matches #"(\S+) (\S+) (\S+)\t(.*)" l)
                                          p (last (re-matches #"(\S+) (\S+) (\S+)\t(.*)" l))]
                                    :when (denied? p)] (nth (re-matches #"(\S+) (\S+) (\S+)\t(.*)" l) 3)))
            room-objects (set (map #(first (str/split % #" ")) (str/split-lines (k/git room "rev-list" "--all" "--objects"))))]
        (assert (empty? (filter room-objects (remove (set (for [l (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "spec-3"))
                                                                :let [m (re-matches #"(\S+) (\S+) (\S+)\t(.*)" l)]
                                                                :when (not (denied? (nth m 4)))] (nth m 3)))
                                                     denied-blobs)))))
      ctx)))

(defstep #"a project whose working tree holds an untracked file notes.md and a file git ignores, beside its commit tagged spec-3"
  (fn [ctx]
    (let [ctx (impl-project! ctx {".gitignore" "secret.txt\n"})]
      (cut! ctx "spec-3")
      (k/write-files! (:dir ctx) {"notes.md" "# notes\n" "secret.txt" "s\n"})
      ctx)))

(defstep #"ROOM holds neither file"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #{"notes.md" "secret.txt"} (room-files ctx)) (pr-str (room-files ctx)))
    ctx))

(defstep #"a project whose commit tagged spec-3 holds dhf/wiki/pages/copy.md with the same bytes as an implementation file"
  (fn [ctx]
    (let [ctx (impl-project! ctx {"dhf/wiki/pages/copy.md" "(ns rdm-spec.spec.parser)\n"})]
      (cut! ctx "spec-3"))))

(defstep #"it exits 1, names dhf/wiki/pages/copy.md and the implementation file, and builds no room"
  (fn [ctx]
    (k/check-exit ctx 1)
    (k/err-line-with ctx "dhf/wiki/pages/copy.md" "src/rdm_spec/spec/parser.clj")
    (assert (not (fs/exists? (get-in ctx [:ph "ROOM"]))))
    ctx))

(defstep #"a project whose configuration denies the implementation and the measurements and keeps dhf/spec, and a committed top-level file notes.md"
  (fn [ctx]
    (let [ctx (k/project! ctx {:config {:clean-room {:deny {:implementation true :measurements ["dhf/cycles"]}
                                                     :keep ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki"]}}
                               :extra {"notes.md" "# notes\n"}})]
      (k/commit! (:dir ctx) "notes")
      ctx)))

(defstep #"a project whose configuration keeps dhf/spec, and a committed directory dhf/scratch"
  (fn [ctx]
    (let [ctx (k/project! ctx {:config {:clean-room {:deny {:implementation true :measurements ["dhf/cycles"]}
                                                     :keep ["dhf/spec"]}}
                               :extra {"dhf/scratch/a.md" "# a\n"}})]
      (k/commit! (:dir ctx) "scratch")
      ctx)))

(defstep #"it exits 1 with a finding naming (\S+) as neither denied nor kept"
  (fn [ctx path]
    (k/check-exit ctx 1)
    (k/err-line-with ctx path "neither denied nor kept")))

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx] (cut! (impl-project! ctx) "spec-3")))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? impl? (room-files ctx)) (pr-str (room-files ctx)))
    (assert (some #(str/starts-with? % "dhf/spec/") (room-files ctx)))
    ctx))

(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx] (cut! (impl-project! ctx) "spec-3")))

(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>.clj and its unit tests at test/rdm_spec/<context>/<component>_test.clj"
  (fn [ctx] (cut! (impl-project! ctx) "spec-3")))

(defstep #"ROOM holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(or (str/starts-with? % "src/rdm_spec/verification/") (str/starts-with? % "test/rdm_spec/verification/"))
                      (room-files ctx)))
    ctx))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [cut (if (some #{"spec-4"} (:last-args ctx)) "spec-4" "spec-3")
          impl (filter impl? (files-at ctx cut))
          want (remove (removed-by-scope ctx impl) impl)
          got (filter impl? (room-files ctx))]
      (assert (seq want))
      (assert (= (sort want) (sort got)) (str (pr-str want) " / " (pr-str got)))
      (doseq [f want]
        (assert (= (k/git (:dir ctx) "show" (str cut ":" f)) (str/trim (slurp (fs/file (get-in ctx [:ph "ROOM"]) f))))))
      ctx)))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b]
    (k/check-exit ctx 0)
    (assert (not-any? #{a b} (room-files ctx)))
    (assert (seq (room-files ctx)))
    ctx))

(defstep #"ROOM holds no file of the bounded contexts knowledge and publishing or of the component spec/parser"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(re-find #"(rdm_spec/(knowledge|publishing)/|steps/(knowledge|publishing)_|spec/parser|spec_parser)" %) (filter impl? (room-files ctx)))
            (pr-str (room-files ctx)))
    ctx))

(defstep #"the cut spec-4's model no longer names ratification/ratify, and the cut still holds src/rdm_spec/ratification/ratify.clj and test/rdm_spec/ratification/ratify_test.clj"
  (fn [ctx]
    (let [ctx (impl-project! ctx {"src/rdm_spec/ratification/ratify.clj" "(ns rdm-spec.ratification.ratify)\n"
                                  "test/rdm_spec/ratification/ratify_test.clj" "(ns rdm-spec.ratification.ratify-test)\n"})]
      (cut! ctx "spec-4"))))

(defstep #"ROOM holds neither file, and its result names ratification/ratify as a removed component"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(str/includes? % "ratification") (room-files ctx)))
    (assert (str/includes? (:out ctx) "ratification/ratify") (:out ctx))
    (assert (some #(= "ratification/ratify" (str (if (keyword? %) (subs (str %) 1) %)))
                  (get-in (k/edn-out ctx) [:fence :removed-components])) (:out ctx))
    ctx))

(defstep #"it exits 2 and names archive and the model's bounded contexts and kernel"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "archive" "spec" "knowledge" "publishing" "verification" "kernel")
    (assert (not (fs/exists? (get-in ctx [:ph "ROOM"]))))
    ctx))

(defstep #"so does a component the model does not name whose files the code layout finds none of"
  (fn [ctx]
    (let [r (k/rdm ctx ["fence" (k/path-in ctx "room2") "spec-3" "--component" "archive/old"])]
      (k/check-exit r 2)
      (k/err-line-with r "archive/old")
      ctx)))
