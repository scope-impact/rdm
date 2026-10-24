(ns harness.checklist
  "`harness checklist` (DI-92): the checklist, derived from the specs of the
  slices not `planned` alone, one item per stamped scenario, carrying its slice,
  its Rule and its design input. Never written by hand; an unstamped scenario
  or an empty spec stops it rather than shrinking it."
  (:require [babashka.fs :as fs]
            [harness.events :refer [event fail?]]
            [harness.json :as json]
            [harness.spec :as spec]))

(def derived "Checklist Derived")
(def unstamped "Checklist Not Derived / Unstamped Scenario")
(def empty-spec "Checklist Not Derived / Empty Spec")
(def planned-skipped "Checklist Warned / Planned Slice Skipped")

(defn derive-checklist
  "The checklist's items and the events of deriving it."
  [root dhf]
  (let [slices (spec/slices dhf)
        planned (filter #(= "planned" (spec/status %)) slices)
        ready (remove #(= "planned" (spec/status %)) slices)
        read (for [s ready :let [file (spec/spec-file s)]]
               {:slice s :file file :scenarios (spec/read-spec file)})
        where #(spec/relative dhf %)
        fails (concat
               (when (empty? slices)
                 [(event empty-spec (str "no spec under " (spec/relative root (str dhf "/" spec/plan-dir))))])
               (for [{:keys [file scenarios]} read :when (empty? scenarios)]
                 (event empty-spec (str (where file) ": the spec has no scenario")))
               (for [{:keys [file scenarios]} read, sc scenarios :when (nil? (:item sc))]
                 (event unstamped (str (where file) ":" (:line sc) ": scenario '" (:title sc)
                                       "' carries no item id; run harness stamp"))))
        warnings (for [s planned] (event planned-skipped (str (fs/file-name s) " is planned: its scenarios are scope, not items")))
        items (for [{:keys [slice scenarios]} read, sc scenarios]
                {:item (:item sc) :slice (str (fs/file-name slice)) :scenario (:title sc)
                 :rule (:rule sc) :design_input (spec/design-input (:rule-tags sc))})]
    {:items (vec items) :slices (count read)
     :events (vec (concat fails warnings))}))

(defn run [root dhf args]
  (let [{:keys [items slices events]} (derive-checklist root dhf)
        out (second (drop-while #(not= "--out" %) args))]
    (if (some fail? events)
      events
      (let [text (json/write-str {:items items})]
        (if out
          (do (fs/create-dirs (fs/parent (fs/absolutize out))) (spit out text))
          (print text))
        (flush)
        (conj events (event derived (str slices " slice(s), " (count items) " item(s)")))))))
