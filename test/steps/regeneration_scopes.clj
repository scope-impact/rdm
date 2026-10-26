(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature: `rdm-spec
  scopes spec-3 spec-4` in a regeneration project (a copy of the room made a
  git repository, steps.regeneration-support) whose spec is cut spec-3, then
  changed as a step says and cut spec-4.

  The task's result, {:scopes {:from :to :scopes [scope] :shared [path]}},
  is the shape rdm-spec.regeneration.scopes states; each shared file is also
  a warning (shared-file-changed).

  Context keys set here: :dir, :publisher (the changed event's publisher),
  :readers (the subscribers the step expects as scopes), :subscribers (every
  subscriber of the event)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The two cuts

(defn- cuts!
  "ctx with a project cut spec-3, changed by `change!` (fn [dir]), and cut
  spec-4."
  [ctx change!]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (rs/commit! dir "the cut spec-3")
    (rs/tag! dir "spec-3")
    (change! dir)
    (rs/commit! dir "the spec change")
    (rs/tag! dir "spec-4")
    (assoc ctx :dir dir)))

(defn- touch! [dir rel]
  (let [f (fs/file dir "dhf/spec" rel)]
    (s/write! dir (str "dhf/spec/" rel) (str (when (fs/exists? f) (slurp (str f))) "\n# changed between spec-3 and spec-4\n"))))

(defn- events-rel [dir] (or (:events (kc/config-of dir)) "dhf/spec/events.edn"))

(defn- catalogue [dir] (edn/read-string (slurp (str (fs/file dir (events-rel dir))))))

(defn- write-catalogue! [dir c] (s/write! dir (events-rel dir) (str (pr-str c) "\n")))

(defn- readers-of
  "Every subscriber the read-by `rb` of a key names (per sub-key too)."
  [rb]
  (set (if (map? rb) (mapcat val rb) rb)))

(defn- subscribers [ev]
  (set (mapcat (comp readers-of val) (:read-by ev))))

(defstep #"the cuts spec-3 and spec-4, between which architecture/views\.feature and kernel/bus\.feature changed"
  (fn [ctx] (cuts! ctx #(do (touch! % "architecture/views.feature") (touch! % "kernel/bus.feature")))))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views\.md changed"
  (fn [ctx] (cuts! ctx #(touch! % "architecture/views.md"))))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary\.md changed"
  (fn [ctx] (cuts! ctx #(touch! % "glossary.md"))))

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx]
    (cuts! ctx #(doseq [f ["verification/status.feature" "spec/parser.feature" "kernel/command.feature"]] (touch! % f)))))

;; The event: SpecParsed, whose subscribers read different keys of it.

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events\.edn changed"
  (fn [ctx]
    (let [ctx (cuts! ctx (fn [dir]
                           (let [c (catalogue dir)]
                             (write-catalogue! dir (update-in c [:SpecParsed :shape :needs] str ", each need's :sources sorted")))))
          ev (:SpecParsed (catalogue (:dir ctx)))
          readers (set (get-in ev [:read-by :needs]))]
      (s/check! (seq (remove readers (subscribers ev))) "every subscriber reads :needs: the fixture proves nothing")
      (assoc ctx :publisher (:published-by ev) :readers readers :subscribers (subscribers ev)))))

(defstep #"the cuts spec-3 and spec-4, between which one event's scenarios gained a key that :read-by names one subscriber as reading"
  (fn [ctx]
    (let [ctx (cuts! ctx (fn [dir]
                           (let [c (catalogue dir)]
                             (write-catalogue! dir (-> c
                                                       (update-in [:SpecParsed :shape :scenarios] str "; :checksum the scenario's text hashed")
                                                       (assoc-in [:SpecParsed :read-by :scenarios :checksum] [:architecture/lint]))))))
          ev (:SpecParsed (catalogue (:dir ctx)))]
      (assoc ctx :publisher (:published-by ev) :readers #{:architecture/lint} :subscribers (subscribers ev)))))

(defstep #"the cuts spec-3 and spec-4, between which only an event's :read-by changed"
  (fn [ctx]
    (let [ctx (cuts! ctx (fn [dir]
                           (let [c (catalogue dir)]
                             (write-catalogue! dir (update-in c [:SpecParsed :read-by :needs] (comp vec butlast))))))
          ev (:SpecParsed (catalogue (:dir ctx)))]
      (assoc ctx :publisher (:published-by ev) :readers #{} :subscribers (subscribers ev)))))

;; ---------------------------------------------------------------------------
;; What is listed

(defn- listed
  "{:scopes [scope] :shared [path]} of the last run, which passed."
  [ctx]
  (let [r (kc/exits! ctx 0)
        out (:scopes (kc/stdout-edn r))]
    (s/check! (and (map? out) (vector? (:scopes out))) (str "standard output holds no {:scopes {:scopes [..]}}\n" (s/show r)))
    out))

(defn- component [k] {:component k})

(defstep #"it lists the scopes \{:component :architecture/views\} and \{:component :kernel/bus\}, and nothing else"
  (fn [ctx]
    (let [{:keys [scopes]} (listed ctx)]
      (s/check! (= #{(component :architecture/views) (component :kernel/bus)} (set scopes))
                (str "the scopes are " (pr-str scopes)))
      (s/check! (= 2 (count scopes)) (str "a scope is listed twice: " (pr-str scopes)))
      nil)))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx]
    (let [{:keys [scopes]} (listed ctx)]
      (s/check! (= [(component :architecture/views)] scopes) (str "the scopes are " (pr-str scopes)))
      nil)))

(defstep #"it lists no scope, and names glossary\.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (let [{:keys [scopes shared]} (listed ctx)
          r (kc/the-run ctx)]
      (s/check! (empty? scopes) (str "the scopes are " (pr-str scopes)))
      (s/check! (= ["dhf/spec/glossary.md"] shared) (str "the shared files are " (pr-str shared)))
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "dhf/spec/glossary.md") (str/includes? % "judge"))
                      (s/err-lines r))
                (str "no warning names glossary.md for a person to judge\n" (s/show r)))
      nil)))

(defstep #"it lists as scopes the event's publisher and (?:the subscribers its :read-by names for that key|that one subscriber), and no other subscriber"
  (fn [ctx]
    (let [{:keys [scopes]} (listed ctx)
          want (set (map component (conj (:readers ctx) (:publisher ctx))))]
      (s/check! (seq (:readers ctx)) "the step names no reader")
      (s/check! (= want (set scopes)) (str "the scopes are " (pr-str scopes) ", not " (pr-str want)))
      nil)))

(defstep #"it lists no subscriber of that event as a scope"
  (fn [ctx]
    (let [{:keys [scopes]} (listed ctx)
          subs (set (map component (:subscribers ctx)))]
      (s/check! (seq subs) "the event has no subscriber")
      (s/check! (not-any? subs scopes) (str "a subscriber is a scope: " (pr-str scopes)))
      nil)))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx]
    (let [{:keys [scopes]} (listed ctx)]
      (s/check! (= (mapv component [:kernel/command :spec/parser :verification/status]) scopes)
                (str "the scopes are " (pr-str scopes)))
      nil)))
