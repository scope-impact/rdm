(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature: `rdm-spec
  scopes spec-3 spec-4` on a copy of the room (its model and event
  catalogue as they are), a git repository whose commit is tagged spec-3,
  then changed as a step says, committed and tagged spec-4
  (steps.regeneration-support).

  The result is read as {:scopes {:from :to :scopes [{:component id}]
  :shared [path]}}.

  Context keys set here: :dir (the project), :expected (the scopes a Then
  expects, when a Given computes them)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- two-cuts!
  "ctx with a project cut spec-3, then changed by `change!` (fn [dir]) and
  cut spec-4; `before!` changes it before spec-3."
  ([ctx change!] (two-cuts! ctx (fn [_]) change!))
  ([ctx before! change!]
   (let [dir (rs/repo! (s/copy-of-room!))]
     (s/hooks-off! dir)
     (before! dir)
     (rs/commit! dir "spec-3")
     (rs/tag! dir "spec-3")
     (change! dir)
     (rs/commit! dir "spec-4")
     (rs/tag! dir "spec-4")
     (assoc ctx :dir dir))))

(defn- touch! [dir rel]
  (spit (str (fs/file dir rel)) "\n# changed between the cuts\n" :append true))

(defn- scopes [ctx]
  (let [r (kc/exits! ctx 0)
        out (:scopes (kc/stdout-edn r))]
    (s/check! (vector? (:scopes out)) (str "the result holds no {:scopes {:scopes [..]}}\n" (s/show r)))
    (:scopes out)))

(defn- lists! [ctx want]
  (let [got (scopes ctx)]
    (s/check! (= want got) (str "it lists " (pr-str got) ", not " (pr-str want) "\n" (s/show (kc/the-run ctx))))
    nil))

(defn- events-rel [dir] (or (:events (kc/config-of dir)) "dhf/spec/events.edn"))

(defn- update-events! [dir f]
  (let [rel (events-rel dir)
        cat (edn/read-string (slurp (str (fs/file dir rel))))]
    (s/write! dir rel (str ";; the catalogue, rewritten\n" (pr-str (f cat)) "\n"))))

;; ---------------------------------------------------------------------------

(defstep #"the cuts spec-3 and spec-4, between which architecture/views\.feature and kernel/bus\.feature changed"
  (fn [ctx] (two-cuts! ctx #(do (touch! % "dhf/spec/architecture/views.feature") (touch! % "dhf/spec/kernel/bus.feature")))))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx] (lists! ctx [{:component :kernel/bus} {:component :architecture/views}])))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views\.md, design prose that spec-3 did not have, was added"
  (fn [ctx]
    (two-cuts! ctx (fn [dir]
                     (s/check! (not (fs/exists? (fs/file dir "dhf/spec/architecture/views.md"))) "spec-3 has views.md")
                     (s/write! dir "dhf/spec/architecture/views.md" "# Views\n\nHow a view is drawn.\n")))))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx] (lists! ctx [{:component :architecture/views}])))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary\.md changed"
  (fn [ctx] (two-cuts! ctx #(touch! % "dhf/spec/glossary.md"))))

(defstep #"it lists no scope, and names glossary\.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (lists! ctx [])
    (let [r (kc/the-run ctx)]
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "dhf/spec/glossary.md") (str/includes? % "shared")
                            (str/includes? % "person"))
                      (s/err-lines r))
                (str "no warning names glossary.md as a shared file\n" (s/show r)))
      (s/check! (= ["dhf/spec/glossary.md"] (get-in (kc/stdout-edn r) [:scopes :shared]))
                (str "the result's :shared is " (pr-str (get-in (kc/stdout-edn r) [:scopes :shared]))))
      nil)))

(defn- subscribers
  "The components the model's relationships name for `event`, in their order."
  [dir event]
  (let [m (rs/model-of dir)]
    (vec (distinct (for [r (:relationships m) :when (= event (:event r))] (:to r))))))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events\.edn changed"
  (fn [ctx]
    (let [ctx (two-cuts! ctx #(update-events! % (fn [cat] (assoc-in cat [:SpecParsed :shape :needs] "[{:id :text}] changed"))))
          dir (:dir ctx)]
      (assoc ctx :expected (mapv (fn [c] {:component c})
                                 (cons :spec/parser (subscribers dir :SpecParsed)))))))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event, in the order they name them"
  (fn [ctx]
    (s/check! (< 2 (count (:expected ctx))) "the event has too few subscribers to show an order")
    (lists! ctx (:expected ctx))))

(defstep #"the cuts spec-3 and spec-4, between which only an event's :description changed"
  (fn [ctx] (two-cuts! ctx #(update-events! % (fn [cat] (assoc-in cat [:SpecGated :description] "Reworded, nothing else"))))))

(defstep #"the cuts spec-3 and spec-4, between which only an event lost a key its catalogue entry does not declare, as :read-by was"
  (fn [ctx]
    (two-cuts! ctx
               #(update-events! % (fn [cat] (assoc-in cat [:SpecGated :read-by] {:publishing/site [:passed?]})))
               #(update-events! % (fn [cat] (update cat :SpecGated dissoc :read-by))))))

(defstep #"it lists no scope"
  (fn [ctx] (lists! ctx [])))

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx]
    (two-cuts! ctx #(doseq [f ["dhf/spec/verification/status.feature" "dhf/spec/spec/parser.feature"
                               "dhf/spec/kernel/command.feature"]]
                      (touch! % f)))))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx] (lists! ctx [{:component :kernel/command} {:component :spec/parser} {:component :verification/status}])))

(defstep #"components with no order between them come in the model's order"
  (fn [ctx]
    (let [dir (:dir ctx)]
      ;; three components no event joins, changed in the reverse of the model's order
      (doseq [f ["dhf/spec/agency/observe.feature" "dhf/spec/architecture/views.feature" "dhf/spec/project/init.feature"]]
        (touch! dir f))
      (rs/commit! dir "spec-5")
      (rs/tag! dir "spec-5")
      (let [c (kc/run-line ctx "scopes spec-4 spec-5")]
        (lists! c [{:component :project/init} {:component :architecture/views} {:component :agency/observe}])))))
