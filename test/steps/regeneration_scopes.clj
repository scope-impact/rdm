(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature: `rdm-spec
  scopes spec-3 spec-4` on a regeneration project (steps.regeneration-
  support) whose cut spec-3 is a copy of the room's spec and whose cut
  spec-4 is spec-3 with one change.

  The catalogue is rewritten as EDN in both cuts (`events!`), so the only
  difference between them is the change a Given states.

  Context keys set here: :dir (the project)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(def ^:private events-rel "dhf/spec/events.edn")

(defn- events [dir] (edn/read-string (slurp (str (fs/file dir events-rel)))))

(defn- events! [dir f] (s/write! dir events-rel (str (pr-str (f (events dir))) "\n")))

(defn- cuts!
  "ctx with :dir: spec-3 the room's spec with `before` applied to its
  catalogue, spec-4 that with `change!` applied to the project."
  ([ctx change!] (cuts! ctx identity change!))
  ([ctx before change!]
   (let [dir (rs/repo! (s/copy-of-room!))]
     (events! dir before)
     (rs/commit! dir "the cut spec-3")
     (rs/tag! dir "spec-3")
     (change! dir)
     (rs/commit! dir "the cut spec-4")
     (rs/tag! dir "spec-4")
     (s/check! (seq (rs/git! dir "diff" "--name-only" "spec-3" "spec-4")) "the cuts do not differ")
     (assoc ctx :dir dir))))

(defn- touch! [dir rel]
  (spit (str (fs/file dir rel)) "\n# A change between the cuts.\n" :append true))

(defn- shape! [dir event k f]
  (events! dir #(update-in % [event :shape k] f)))

(defn- listed
  "The scopes the run lists, in order."
  [ctx]
  (let [r (kc/exits! ctx 0)
        out (kc/stdout-edn r)]
    (s/check! (sequential? (get-in out [:scopes :scopes])) (str "standard output lists no {:scopes {:scopes [..]}}\n" (s/show r)))
    (get-in out [:scopes :scopes])))

(defn- lists! [ctx want]
  (let [got (listed ctx)]
    (s/check! (= want got) (str "it lists " (pr-str got) ", not " (pr-str want) "\n" (s/show (kc/the-run ctx))))
    nil))

(defn- components [& ks] (mapv (fn [k] {:component k}) ks))

;; ---------------------------------------------------------------------------
;; A cycle's scopes come from the spec change

(defstep #"the cuts spec-3 and spec-4, between which architecture/views\.feature and kernel/bus\.feature changed"
  (fn [ctx] (cuts! ctx (fn [dir] (touch! dir "dhf/spec/architecture/views.feature") (touch! dir "dhf/spec/kernel/bus.feature")))))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx] (lists! ctx (components :kernel/bus :architecture/views))))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views\.md, design prose that spec-3 did not have, was added"
  (fn [ctx]
    (s/check! (not (fs/exists? (fs/file s/room-root "dhf/spec/architecture/views.md"))) "the room's spec has views.md")
    (cuts! ctx (fn [dir] (s/write! dir "dhf/spec/architecture/views.md" "# Views\n\nHow a view is drawn.\n")))))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx] (lists! ctx (components :architecture/views))))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary\.md changed"
  (fn [ctx] (cuts! ctx (fn [dir] (touch! dir "dhf/spec/glossary.md")))))

(defstep #"it lists no scope, and names glossary\.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (lists! ctx [])
    (let [r (kc/the-run ctx)]
      (s/check! (some #(and (str/includes? % "dhf/spec/glossary.md") (kc/warning-line? %)
                            (str/includes? % "a person must judge"))
                      (s/err-lines r))
                (str "no warning names glossary.md as a shared file a person must judge\n" (s/show r))))
    nil))

;; ---------------------------------------------------------------------------
;; A changed event key reaches its publisher and the subscribers that read it

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events\.edn changed"
  (fn [ctx]
    (s/check! (= [:verification/status :compliance/audit] (get-in (events s/room-root) [:SpecParsed :read-by :revision]))
              "the room's catalogue does not say status and audit read SpecParsed's :revision")
    (cuts! ctx (fn [dir] (shape! dir :SpecParsed :revision #(str % ", as a tag when there is one"))))))

(defstep #"it lists as scopes the event's publisher, then the subscribers its :read-by names for that key, in the order the model's relationships name them, and no other subscriber"
  (fn [ctx] (lists! ctx (components :spec/parser :verification/status :compliance/audit))))

(defstep #"the cuts spec-3 and spec-4, between which the maps SpecParsed's :needs holds gained the key :x, and :read-by gives :needs the flat list \[:publishing/site :compliance/audit\]"
  (fn [ctx]
    (s/check! (= [:publishing/site :compliance/audit] (get-in (events s/room-root) [:SpecParsed :read-by :needs]))
              "the room's catalogue does not give :needs that flat list")
    (cuts! ctx (fn [dir] (shape! dir :SpecParsed :needs #(str/replace % ":sources [citation]}" ":sources [citation] :x}"))))))

(defstep #"it lists as scopes spec/parser, then publishing/site and compliance/audit, and no other subscriber"
  (fn [ctx] (lists! ctx (components :spec/parser :publishing/site :compliance/audit))))

(defstep #"the cuts spec-3 and spec-4, between which one event's scenarios gained a key that :read-by names one subscriber as reading"
  (fn [ctx]
    (cuts! ctx (fn [dir]
                 (events! dir #(-> %
                                   (update-in [:SpecParsed :shape :scenarios] str/replace ":component-name}]" ":component-name :x}]")
                                   (assoc-in [:SpecParsed :read-by :scenarios :x] [:measurement/checklist])))))))

(defstep #"it lists as scopes the event's publisher, then that one subscriber, and no other subscriber"
  (fn [ctx] (lists! ctx (components :spec/parser :measurement/checklist))))

(def ^:private read-by-changes
  {"an event's :read-by changed"
   [identity #(assoc-in % [:SpecGated :read-by :passed?] [])]
   "an event that had no :read-by gained one naming every key"
   [#(update % :KnowledgeIndexed dissoc :read-by)
    #(assoc-in % [:KnowledgeIndexed :read-by] {:revision [:publishing/site] :sources [:publishing/site] :pages [:publishing/site]
                                               :citations [:publishing/site] :findings [:publishing/site]})]
   "an event that had no :read-by gained one naming a scenario key's readers"
   [#(update % :SpecParsed dissoc :read-by)
    #(assoc-in % [:SpecParsed :read-by] {:scenarios {:id [:verification/status]}})]})

(defstep #"the cuts spec-3 and spec-4, between which only (an event's :read-by changed|an event that had no :read-by gained one naming every key|an event that had no :read-by gained one naming a scenario key's readers)"
  (fn [ctx change]
    (let [[before after] (get read-by-changes change)]
      (cuts! ctx before (fn [dir] (events! dir after))))))

(defstep #"it lists no scope"
  (fn [ctx] (lists! ctx [])))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of an event without :read-by changed"
  (fn [ctx]
    (cuts! ctx #(update % :SpecParsed dissoc :read-by)
           (fn [dir] (shape! dir :SpecParsed :revision #(str % ", as a tag when there is one"))))))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event"
  (fn [ctx]
    (lists! ctx (components :spec/parser :architecture/lint :verification/status :measurement/checklist
                            :publishing/site :compliance/audit))))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key changed, whose :read-by entry is empty"
  (fn [ctx]
    (cuts! ctx #(assoc-in % [:SpecParsed :read-by :refusals] [])
           (fn [dir] (shape! dir :SpecParsed :refusals #(str % ", in file order"))))))

(defstep #"it lists the event's publisher as the only scope"
  (fn [ctx] (lists! ctx (components :spec/parser))))

;; ---------------------------------------------------------------------------
;; Scopes are listed in the order a builder builds them

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx]
    (cuts! ctx (fn [dir] (doseq [f ["verification/status" "spec/parser" "kernel/command"]]
                           (touch! dir (str "dhf/spec/" f ".feature")))))))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx] (lists! ctx (components :kernel/command :spec/parser :verification/status))))
