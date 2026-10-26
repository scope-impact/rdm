(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature: `rdm-spec
  scopes spec-3 spec-4` in a regeneration project (steps.regeneration-support)
  whose spec changed between the two cuts as a step says.

  Context keys set here: :dir, :event (the event whose shape changed)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- two-cuts!
  "A project cut at spec-3, `change!` applied, then cut at spec-4."
  [ctx change!]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (rs/commit! dir "the cut spec-3")
    (rs/tag! dir "spec-3")
    (change! dir)
    (rs/commit! dir "the cut spec-4")
    (rs/tag! dir "spec-4")
    (assoc ctx :dir dir)))

(defn- touch! [dir rel]
  (let [f (fs/file dir rel)]
    (s/check! (fs/exists? f) (str "the project holds no " rel))
    (spit (str f) "\n# A line the spec change added.\n" :append true)))

(defstep #"the cuts spec-3 and spec-4, between which architecture/views\.feature and kernel/bus\.feature changed"
  (fn [ctx]
    (two-cuts! ctx (fn [dir]
                     (touch! dir "dhf/spec/architecture/views.feature")
                     (touch! dir "dhf/spec/kernel/bus.feature")))))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views\.md changed"
  (fn [ctx]
    (two-cuts! ctx (fn [dir] (s/write! dir "dhf/spec/architecture/views.md" "# Views\n\nThe design of the views.\n")))))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary\.md changed"
  (fn [ctx] (two-cuts! ctx (fn [dir] (touch! dir "dhf/spec/glossary.md")))))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one event in dhf/spec/events\.edn changed"
  (fn [ctx]
    (let [event :SpecGated]
      (assoc (two-cuts! ctx (fn [dir]
                              (let [f (fs/file dir "dhf/spec/events.edn")
                                    c (edn/read-string (slurp (str f)))]
                                (s/check! (contains? c event) (str "the catalogue declares no " event))
                                (spit (str f) (pr-str (assoc-in c [event :shape :passed?] "a new meaning"))))))
             :event event))))

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx]
    (two-cuts! ctx (fn [dir]
                     (doseq [f ["dhf/spec/verification/status.feature" "dhf/spec/spec/parser.feature"
                                "dhf/spec/kernel/command.feature"]]
                       (touch! dir f))))))

(defn- listed [ctx]
  (let [r (kc/exits! ctx 0)
        out (kc/stdout-edn r)]
    (s/check! (vector? (:scopes out)) (str "standard output lists no :scopes\n" (s/show r)))
    (:scopes out)))

(defstep #"it lists the scopes \{:component (\S+)\} and \{:component (\S+)\}, and nothing else"
  (fn [ctx a b]
    (let [got (listed ctx)]
      (s/check! (= #{{:component (edn/read-string a)} {:component (edn/read-string b)}} (set got))
                (str "the scopes are " (pr-str got)))
      (s/check! (= 2 (count got)) (str "the scopes are " (pr-str got)))
      nil)))

(defstep #"it lists the scope \{:component (\S+)\}"
  (fn [ctx a]
    (let [got (listed ctx)]
      (s/check! (= [{:component (edn/read-string a)}] got) (str "the scopes are " (pr-str got)))
      nil)))

(defstep #"it lists no scope, and names glossary\.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (let [got (listed ctx)
          r (kc/the-run ctx)]
      (s/check! (empty? got) (str "the scopes are " (pr-str got)))
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "dhf/spec/glossary.md") (str/includes? % "person"))
                      (s/err-lines r))
                (str "no warning names dhf/spec/glossary.md for a person to judge\n" (s/show r)))
      nil)))

(defstep #"it lists as scopes the component the event catalogue names as its publisher and every component the model draws subscribing to it"
  (fn [ctx]
    (let [got (listed ctx)
          dir (:dir ctx)
          catalogue (edn/read-string (slurp (str (fs/file dir "dhf/spec/events.edn"))))
          m (rs/model-of dir)
          want (set (concat [(get-in catalogue [(:event ctx) :published-by])]
                            (for [r (:relationships m) :when (= (:event ctx) (:event r))] (:to r))))]
      (s/check! (< 1 (count want)) "the event has no subscriber in the model")
      (s/check! (= want (set (map :component got))) (str "the scopes are " (pr-str got) ", not " (pr-str want)))
      (s/check! (every? #(= #{:component} (set (keys %))) got) (str "the scopes are " (pr-str got)))
      nil)))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx]
    (let [got (listed ctx)]
      (s/check! (= [{:component :kernel/command} {:component :spec/parser} {:component :verification/status}] got)
                (str "the scopes are " (pr-str got)))
      nil)))
