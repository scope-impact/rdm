(ns steps.regeneration-fence
  "Steps of the component Fence (dhf/spec/06-regenerate/fence.feature).
  The project holds an implementation laid out as rdm-spec's own."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(def impl-model
  (-> (k/context-model [[:spec [[:parser "Parser"] [:stamper "Stamper"]]]
                        [:knowledge [[:sources "Sources"]]]
                        [:verification [[:status "Status"]]]
                        [:publishing [[:site "Site"]]]
                        [:measurement [[:checklist "Checklist"]]]])
      (assoc :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus" :description "Delivers events"}
                      {:id :kernel/command :name "Command" :spec "kernel/command" :description "The command"}])))

(defn components [m]
  (concat (for [c (:containers m) ctx (:contexts c) comp (:components ctx)] [(name (:id ctx)) (name (:id comp))])
          (for [k (:kernel m)] ["kernel" (name (:id k))])))

(defn impl-files [m & [extra]]
  (into {"test.clj" "(println :tests)\n" "bb.edn" "{:paths [\"src\" \"test\"]}\n"}
        (concat
         (for [[ctx comp] (concat (components m) extra)
               [f t] [[(str "src/rdm_spec/" ctx "/" comp ".clj") (str "(ns rdm-spec." ctx "." comp ")\n")]
                      [(str "test/rdm_spec/" ctx "/" comp "_test.clj") (str "(ns rdm-spec." ctx "." comp "-test)\n")]
                      [(str "test/steps/" ctx "_" comp ".clj") (str "(ns steps." ctx "-" comp ")\n")]]]
           [f t]))))

(def impl-config
  {:implementation ["src" "test" "test.clj" "bb.edn"]
   :code-layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
                 :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
                             "test/steps/{context}_{component}.clj"]}
   :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site" "site-src" "dhf/allure-results"]}
                :keep [".gitignore" ".githooks" ".github" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"]}})

(defn impl-project!
  "A project whose commit tagged spec-3 holds an implementation of every component of MODEL."
  [ctx & [{:keys [model extra files cfg]}]]
  (let [model (or model impl-model)
        features (into {} (map-indexed (fn [i [ctx comp]]
                                         [(str "dhf/spec/" ctx "/" comp ".feature")
                                          (k/feature-text comp ["UN-001"] [{:id (format "spec-%04d" (inc i)) :title (str comp " works")}])])
                                       (components model)))
        ctx (k/spec-project! ctx {:model model :features features})]
    (k/config! ctx merge impl-config cfg)
    (doseq [[f t] (merge (impl-files model extra) files)] (k/write! ctx f t))
    (k/commit-tag! ctx "spec-3")))

(defn room [ctx] (get-in ctx [:ph "ROOM"] (str (:base ctx) "/room")))

(defn room-files [ctx]
  (let [r (room ctx)]
    (set (for [f (fs/glob r "**" {:hidden true}) :when (and (fs/regular-file? f) (not (str/starts-with? (str (fs/relativize r f)) ".git/")))]
           (str (fs/relativize r f))))))

(defn committed [ctx rev]
  (set (remove str/blank? (str/split-lines (k/git! ctx "ls-tree" "-r" "--name-only" rev)))))

(defn impl-file? [f] (some #(or (= f %) (str/starts-with? f (str % "/"))) ["src" "test" "test.clj" "bb.edn"]))

(defn keeps-the-rest!
  "The room holds every implementation file at the cut but REMOVED, unchanged."
  [ctx removed?]
  (let [expected (filter #(and (impl-file? %) (not (removed? %))) (committed ctx "spec-3"))]
    (doseq [f expected]
      (k/check! (= (k/git! ctx "show" (str "spec-3:" f)) (str/trim (slurp (str (room ctx) "/" f)))) f " is not kept unchanged"))
    (k/check! (= (set expected) (set (filter impl-file? (room-files ctx)))) "the room's implementation is " (pr-str (sort (filter impl-file? (room-files ctx)))))
    ctx))

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [ctx] (let [ctx (k/spec-project! ctx)] (k/commit! ctx "not a cut"))))

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "regeneration reads only a cut") (k/check! (not (fs/exists? (room ctx))) "a room was built") ctx))

(defstep #"the configuration denies the implementation, the measurements dhf/cycles and the generated site, and keeps every other path"
  (fn [ctx] (assoc ctx ::cycles true)))

(defstep #"the cut also holds dhf/cycles/01/score.edn"
  (fn [ctx] (impl-project! ctx {:files {"dhf/cycles/01/score.edn" "{:cycle \"01\"}\n"}})))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [r (assoc ctx :dir (room ctx))]
      (k/check! (= "1" (k/git! r "rev-list" "--count" "HEAD")) "the room has more than one commit")
      (k/check! (= 1 (count (str/split (k/git! r "rev-list" "--parents" "-n" "1" "HEAD") #" "))) "the room's commit has a parent")
      ctx)))

(defstep #"its working tree holds every file committed at the cut that is not denied, unchanged, and no other file"
  (fn [ctx]
    (let [expected (set (remove #(or (impl-file? %) (str/starts-with? % "dhf/cycles/")) (committed ctx "spec-3")))]
      (k/check! (= expected (room-files ctx)) "the room holds " (pr-str (sort (room-files ctx))))
      (doseq [f expected] (k/check! (= (k/git! ctx "show" (str "spec-3:" f)) (str/trim (slurp (str (room ctx) "/" f)))) f " changed"))
      ctx)))

(defstep #"no object reachable in it belongs to a denied path"
  (fn [ctx]
    (let [r (assoc ctx :dir (room ctx))
          paths (str/split-lines (k/git! r "rev-list" "--objects" "--all"))]
      (k/check! (not-any? #(re-find #" (src|test|test\.clj|bb\.edn|dhf/cycles)(/|$)" %) paths) "a denied path is reachable")
      ctx)))

(defstep #"a project whose working tree holds an untracked file notes.md and a file git ignores, beside its commit tagged spec-3"
  (fn [ctx]
    (let [ctx (impl-project! ctx)]
      (k/write! ctx ".gitignore" "secret.txt\n")
      (k/write! ctx "notes.md" "untracked\n")
      (k/write! ctx "secret.txt" "ignored\n"))))

(defstep #"ROOM holds neither file"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #{"notes.md" "secret.txt"} (room-files ctx)) "the room holds " (pr-str (sort (room-files ctx))))
    ctx))

(defstep #"a project whose commit tagged spec-3 holds dhf/wiki/pages/copy.md with the same bytes as an implementation file"
  (fn [ctx] (impl-project! ctx {:files {"dhf/wiki/pages/copy.md" "(ns rdm-spec.spec.parser)\n"}})))

(defstep #"it exits 1, names dhf/wiki/pages/copy.md and the implementation file, and builds no room"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/err-line-has! ctx "dhf/wiki/pages/copy.md" "src/rdm_spec/spec/parser.clj")
    (k/check! (not (fs/exists? (room ctx))) "a room was built")
    ctx))

(defstep #"a project whose configuration denies the implementation and the measurements and keeps dhf/spec, and a committed top-level file notes.md"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (k/config! ctx assoc :clean-room {:deny {:implementation true :measurements ["dhf/cycles"]} :keep ["dhf/spec"]})
      (k/write! ctx "notes.md" "notes\n")
      (k/commit! ctx "notes"))))

(defstep #"it exits 1 with a finding naming (notes.md|dhf/scratch) as neither denied nor kept"
  (fn [ctx p] (k/exits! ctx 1) (k/err-line-has! ctx p "neither denied nor kept") ctx))

(defstep #"a project whose configuration keeps dhf/spec, and a committed directory dhf/scratch"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (k/config! ctx assoc :clean-room {:deny {:implementation true} :keep ["dhf/spec"]})
      (k/write! ctx "dhf/scratch/x.txt" "scratch\n")
      (k/commit! ctx "scratch"))))

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx] (impl-project! ctx)))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? impl-file? (room-files ctx)) "the room holds " (pr-str (filter impl-file? (room-files ctx))))
    ctx))

(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx] (impl-project! ctx)))

(defstep #"ROOM holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(re-find #"^(src|test)/rdm_spec/verification/" %) (room-files ctx)) "the room holds verification's code")
    (assoc ctx ::removed #(re-find #"^(src/rdm_spec/verification/|test/rdm_spec/verification/|test/steps/verification_)" %))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx] (keeps-the-rest! ctx (::removed ctx))))

(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>.clj and its unit tests at test/rdm_spec/<context>/<component>_test.clj"
  (fn [ctx] (impl-project! ctx)))

(defn removed-of [comps] (fn [f] (some (fn [[c n]] (#{(str "src/rdm_spec/" c "/" n ".clj") (str "test/rdm_spec/" c "/" n "_test.clj") (str "test/steps/" c "_" n ".clj")} f)) comps)))

(defstep #"ROOM holds neither (src/rdm_spec/spec/parser.clj nor test/rdm_spec/spec/parser_test.clj|src/rdm_spec/kernel/bus.clj nor test/rdm_spec/kernel/bus_test.clj)"
  (fn [ctx which]
    (k/exits! ctx 0)
    (let [comp (if (str/includes? which "parser") ["spec" "parser"] ["kernel" "bus"])
          removed (removed-of [comp])]
      (k/check! (not-any? removed (room-files ctx)) "the room holds " (pr-str (filter removed (room-files ctx))))
      (assoc ctx ::removed removed))))

(defstep #"ROOM holds no file of the bounded contexts knowledge and publishing or of the component spec/parser"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [removed (fn [f] (or (re-find #"^(src/rdm_spec|test/rdm_spec)/(knowledge|publishing)/" f)
                              (re-find #"^test/steps/(knowledge|publishing)_" f)
                              ((removed-of [["spec" "parser"]]) f)))]
      (k/check! (not-any? removed (room-files ctx)) "the room holds " (pr-str (filter removed (room-files ctx))))
      (assoc ctx ::removed removed))))

(def slice-plan "# Slice\n\n## Goal\n\nA goal.\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n- a\n\n**Out:**\n\n- b\n\n## Decisions\n\nNone.\n")

(defstep #"a configuration with :spec-layout :slices, and the slice 12-several-specs reaching :spec/parser, :spec/stamper and :measurement/checklist"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx {:model impl-model
                                    :features (into {} (map-indexed (fn [i [c n]]
                                                                      [(str "dhf/spec/" (if (#{"parser" "stamper" "checklist"} n) "12-several-specs" "01-rest") "/" n ".feature")
                                                                       (k/feature-text n ["UN-001"] [{:id (format "spec-%04d" (inc i)) :title n}])])
                                                                    (components impl-model)))})]
      (k/config! ctx merge impl-config {:spec-layout :slices})
      (k/write! ctx "dhf/spec/12-several-specs/plan.md" slice-plan)
      (k/write! ctx "dhf/spec/01-rest/plan.md" slice-plan)
      (doseq [[f t] (impl-files impl-model)] (k/write! ctx f t))
      (k/commit-tag! ctx "spec-3"))))

(defstep #"ROOM holds no code, unit test or step definition of those three components"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [removed (removed-of [["spec" "parser"] ["spec" "stamper"] ["measurement" "checklist"]])]
      (k/check! (not-any? removed (room-files ctx)) "the room holds " (pr-str (filter removed (room-files ctx))))
      (assoc ctx ::removed removed))))

(defstep #"a slice the cut does not hold is refused with exit 2, naming it"
  (fn [ctx]
    (let [r (k/run-rdm (assoc-in ctx [:ph "ROOM"] (str (:base ctx) "/room2")) "fence ROOM spec-3 --slice 99-nothing")]
      (k/exits! r 2)
      (k/err-has! r "99-nothing")
      ctx)))

(defstep #"the cut spec-4's model no longer names ratification/ratify, and the cut still holds src/rdm_spec/ratification/ratify.clj and test/rdm_spec/ratification/ratify_test.clj"
  (fn [ctx]
    (let [ctx (impl-project! ctx {:extra [["ratification" "ratify"]]})]
      (k/commit-tag! ctx "spec-4"))))

(defstep #"ROOM holds neither file, and its result names ratification/ratify as a removed component"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [removed (removed-of [["ratification" "ratify"]])]
      (k/check! (not-any? removed (room-files ctx)) "the room holds " (pr-str (filter removed (room-files ctx))))
      (k/check! (str/includes? (pr-str (:result ctx)) "ratification/ratify") "the result does not name it: " (k/shows ctx))
      (assoc ctx ::removed removed ::cut "spec-4"))))

(defstep #"it exits 2 and names archive and the model's bounded contexts and kernel"
  (fn [ctx]
    (k/exits! ctx 2)
    (k/err-line-has! ctx "archive" "spec" "knowledge" "verification" "publishing" "measurement" "kernel")
    ctx))

(defstep #"so does a component the model does not name whose files the code layout finds none of"
  (fn [ctx]
    (let [r (k/run-rdm (assoc-in ctx [:ph "ROOM"] (str (:base ctx) "/room2")) "fence ROOM spec-3 --component nothing/here")]
      (k/exits! r 2)
      (k/err-line-has! r "nothing/here" "kernel")
      ctx)))
