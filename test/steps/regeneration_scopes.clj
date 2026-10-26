(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature: `rdm-spec
  scopes spec-3 spec-4` in a regeneration project (steps.regeneration-support):
  a copy of the room made a git repository, committed and tagged spec-3, a
  change a step describes committed and tagged spec-4. The model and the
  event catalogue are the room's own; the catalogue is written back as EDN
  before the first cut, so only what a step changes differs between cuts.

  The result: {:scopes {:from :to :scopes [{:component id}] :shared [path]}};
  a changed shared file is also a warning on the error stream.

  Context keys set here: :dir, :event (the event a step changed), :key (the
  carried key it changed), :readers (the subscribers expected after the
  publisher, as a set)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- events-rel [dir] (or (:events (kc/config-of dir)) "dhf/spec/events.edn"))

(defn- read-events [dir] (edn/read-string (slurp (str (fs/file dir (events-rel dir))))))

(defn- write-events! [dir cat] (s/write! dir (events-rel dir) (str (pr-str cat) "\n")))

(defn- update-events! [dir f] (write-events! dir (f (read-events dir))))

(defn- append! [dir rel line]
  (let [f (fs/file dir rel)]
    (s/check! (fs/exists? f) (str "the project has no " rel))
    (spit (str f) (str "\n# " line "\n") :append true)))

(defn- cuts!
  "ctx with :dir: spec-3 is the room's copy after `base`, spec-4 after `change`."
  [ctx base change]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (update-events! dir identity)
    (base dir)
    (rs/commit! dir "spec-3")
    (rs/tag! dir "spec-3")
    (change dir)
    (rs/commit! dir "spec-4")
    (rs/tag! dir "spec-4")
    (assoc ctx :dir dir)))

(defn- subscribers
  "The components the room's model names as subscribers of `event`."
  [dir event]
  (set (for [r (:relationships (rs/model-of dir)) :when (= event (:event r))] (:to r))))

(defn- scopes-out [ctx]
  (let [r (kc/exits! ctx 0)
        out (:scopes (kc/stdout-edn r))]
    (s/check! (and (map? out) (vector? (:scopes out))) (str "standard output holds no {:scopes {:scopes [..]}}\n" (s/show r)))
    out))

(defn- listed [ctx] (mapv :component (:scopes (scopes-out ctx))))

;; ---------------------------------------------------------------------------
;; Feature files and prose

(defstep #"the cuts spec-3 and spec-4, between which architecture/views\.feature and kernel/bus\.feature changed"
  (fn [ctx]
    (cuts! ctx (fn [_]) (fn [d]
                          (append! d "dhf/spec/architecture/views.feature" "a change")
                          (append! d "dhf/spec/kernel/bus.feature" "a change")))))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views\.md, design prose that spec-3 did not have, was added"
  (fn [ctx]
    (cuts! ctx
           (fn [d] (fs/delete-if-exists (fs/file d "dhf/spec/architecture/views.md")))
           (fn [d] (s/write! d "dhf/spec/architecture/views.md" "# Views\n\nHow a view is drawn.\n")))))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary\.md changed"
  (fn [ctx]
    (cuts! ctx (fn [_]) (fn [d] (spit (str (fs/file d "dhf/spec/glossary.md")) "\n**Turn**: an hour.\n" :append true)))))

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx]
    (cuts! ctx (fn [_]) (fn [d]
                          (doseq [rel ["dhf/spec/verification/status.feature" "dhf/spec/spec/parser.feature"
                                       "dhf/spec/kernel/command.feature"]]
                            (append! d rel "a change"))))))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx]
    (s/check! (= [:kernel/bus :architecture/views] (listed ctx)) (str "it lists " (pr-str (listed ctx))))
    nil))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx]
    (s/check! (= [:architecture/views] (listed ctx)) (str "it lists " (pr-str (listed ctx))))
    nil))

(defstep #"it lists no scope, and names glossary\.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (let [out (scopes-out ctx)
          r (kc/the-run ctx)]
      (s/check! (= [] (:scopes out)) (str "it lists " (pr-str (:scopes out))))
      (s/check! (some #{"dhf/spec/glossary.md"} (:shared out)) (str "the shared files are " (pr-str (:shared out))))
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "glossary.md") (str/includes? % "person"))
                      (s/err-lines r))
                (str "no warning names glossary.md for a person to judge\n" (s/show r)))
      nil)))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx]
    (s/check! (= [:kernel/command :spec/parser :verification/status] (listed ctx)) (str "it lists " (pr-str (listed ctx))))
    nil))

;; ---------------------------------------------------------------------------
;; Event keys

(defn- reshape [cat event k]
  (update-in cat [event :shape k] #(str % "; reworded")))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events\.edn changed"
  (fn [ctx]
    (let [ctx (cuts! ctx (fn [_]) (fn [d] (update-events! d #(reshape % :SpecParsed :needs))))
          readers (set (get-in (read-events (:dir ctx)) [:SpecParsed :read-by :needs]))]
      (s/check! (seq readers) "SpecParsed's :needs names no readers")
      (s/check! (not= readers (subscribers (:dir ctx) :SpecParsed)) "every subscriber reads :needs")
      (assoc ctx :event :SpecParsed :publisher :spec/parser :readers readers))))

(defstep #"the cuts spec-3 and spec-4, between which one event's scenarios gained a key that :read-by names one subscriber as reading"
  (fn [ctx]
    (-> (cuts! ctx (fn [_])
               (fn [d] (update-events! d #(-> %
                                              (update-in [:SpecParsed :shape :scenarios] (fn [sh] (str/replace-first sh "[{:id " "[{:id :weight ")))
                                              (assoc-in [:SpecParsed :read-by :scenarios :weight] [:verification/status])))))
        (assoc :publisher :spec/parser :readers #{:verification/status}))))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of an event without :read-by changed"
  (fn [ctx]
    (let [ctx (cuts! ctx
                     (fn [d] (update-events! d #(update % :SpecParsed dissoc :read-by)))
                     (fn [d] (update-events! d #(reshape % :SpecParsed :needs))))]
      (assoc ctx :publisher :spec/parser :readers (subscribers (:dir ctx) :SpecParsed)))))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key changed, whose :read-by entry is empty"
  (fn [ctx]
    (-> (cuts! ctx
               (fn [d] (update-events! d #(assoc-in % [:SpecParsed :read-by :refusals] [])))
               (fn [d] (update-events! d #(reshape % :SpecParsed :refusals))))
        (assoc :publisher :spec/parser :readers #{}))))

(defn- publisher-then-readers! [ctx]
  (let [l (listed ctx)]
    (s/check! (= (:publisher ctx) (first l)) (str "it lists " (pr-str l) ", not the publisher first"))
    (s/check! (= (:readers ctx) (set (rest l))) (str "it lists " (pr-str l) " after the publisher, not " (pr-str (:readers ctx))))
    (s/check! (= (count (rest l)) (count (set (rest l)))) (str "it lists a scope twice: " (pr-str l)))
    nil))

(defstep #"it lists as scopes the event's publisher, then the subscribers its :read-by names for that key, and no other subscriber"
  (fn [ctx] (publisher-then-readers! ctx)))

(defstep #"it lists as scopes the event's publisher, then that one subscriber, and no other subscriber"
  (fn [ctx] (publisher-then-readers! ctx)))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event"
  (fn [ctx] (publisher-then-readers! ctx)))

(defstep #"it lists the event's publisher as the only scope"
  (fn [ctx]
    (s/check! (= [(:publisher ctx)] (listed ctx)) (str "it lists " (pr-str (listed ctx))))
    nil))

;; ---------------------------------------------------------------------------
;; Who reads a key is metadata (spec-0301)

(def ^:private read-by-changes
  {"an event's :read-by changed"
   [(fn [_]) (fn [d] (update-events! d #(update-in % [:SpecParsed :read-by :needs] conj :verification/status)))]

   "an event that had no :read-by gained one naming every key"
   [(fn [d] (update-events! d #(update % :SpecGated dissoc :read-by)))
    (fn [d] (update-events! d #(assoc-in % [:SpecGated :read-by] {:revision [] :passed? [:publishing/site] :findings [:publishing/site]})))]

   "an event that had no :read-by gained one naming a scenario key's readers"
   [(fn [d] (update-events! d #(update % :SpecParsed dissoc :read-by)))
    (fn [d] (update-events! d #(assoc-in % [:SpecParsed :read-by] {:scenarios {:id [:verification/status]}})))]})

(defstep #"the cuts spec-3 and spec-4, between which only (an event.*)"
  (fn [ctx change]
    (let [[base after] (or (get read-by-changes change) (s/fail! (str "no such change: " change)))]
      (cuts! ctx base after))))

(defstep #"it lists no scope"
  (fn [ctx]
    (s/check! (= [] (listed ctx)) (str "it lists " (pr-str (listed ctx))))
    nil))
