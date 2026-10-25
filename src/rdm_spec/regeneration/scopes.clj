(ns rdm-spec.regeneration.scopes
  "Component Scopes of the regeneration context
  (dhf/spec/regeneration/scopes.feature): `rdm-spec scopes CUT CUT` lists
  what a spec change touched between two cuts, so a cycle's scopes come from
  the spec, never from memory.

  A changed feature file or design prose (<spec-dir>/<context>/<component>
  .feature or .md) is its component's: the scope {:component :context/component},
  by the model's ids. A changed file on the clean room's allow-list that no
  component owns (the model, the events, the glossary, the wiki...) is a
  shared file: a warning names it, for a person to judge who reads it. Files
  outside the allow-list (the implementation, the cycles) are not the spec."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(defn- git
  [req & args]
  (let [r (try (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""
                               :env (or (:env req) (into {} (System/getenv)))}
                      "git" args)
               (catch java.io.IOException e
                 (command/cannot-run! (str "git cannot be run (" (ex-message e) "): rdm-spec scopes needs git on PATH"))))]
    {:exit (:exit r) :out (:out r) :err (str/trim (str (:err r)))}))

(defn- commit-of [req rev]
  (let [r (git req "rev-parse" "--verify" "-q" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn- edn-at [req rev path]
  (let [r (git req "show" (str rev ":" path))]
    (when (zero? (:exit r))
      (try (edn/read-string (:out r)) (catch Exception _ nil)))))

(defn- strip [s] (str/replace (str s) #"/+$" ""))

(defn owners
  "{\"context/component\" component-id} of the model `m`, the kernel's
  components included."
  [m]
  (into {}
        (concat (for [c (:containers m) x (:contexts c) comp (:components x)
                      :let [sp (or (:spec comp) (str (name (:id x)) "/" (name (:id comp))))]]
                  [sp (:id comp)])
                (for [comp (:kernel m)
                      :let [sp (or (:spec comp) (str "kernel/" (name (:id comp))))]]
                  [sp (:id comp)]))))

(defn owner
  "The component id owning the spec file `path`, by `owner-map`, or nil."
  [owner-map spec-dir path]
  (let [prefix (str (strip spec-dir) "/")]
    (when (str/starts-with? path prefix)
      (when-let [[_ sp] (re-matches #"(.+)\.(?:feature|md)" (subs path (count prefix)))]
        (get owner-map sp)))))

(defn classify
  "{:scopes [{:component id}] :shared [path]} of the changed `paths`."
  [{:keys [owner-map spec-dir allow]} paths]
  (reduce (fn [acc path]
            (if-let [id (owner owner-map spec-dir path)]
              (update acc :scopes #(if (some #{{:component id}} %) % (conj % {:component id})))
              (if (some #(let [a (strip %)] (or (= path a) (str/starts-with? path (str a "/"))))
                        (cons spec-dir allow))
                (update acc :shared conj path)
                acc)))
          {:scopes [] :shared []}
          paths))

(defn scopes
  "The scopes stage: `rdm-spec scopes CUT CUT`."
  [req]
  (let [[a b] (:args req)
        _ (when-not (zero? (:exit (git req "rev-parse" "--git-dir")))
            (command/cannot-run! (str (:root req) " is not a git repository: rdm-spec scopes reads two cuts, which are git tags")))
        ca (or (commit-of req a) (command/cannot-run! (str a " names no commit: rdm-spec scopes reads two cuts")))
        cb (or (commit-of req b) (command/cannot-run! (str b " names no commit: rdm-spec scopes reads two cuts")))
        cfg-a (let [c (edn-at req ca command/config-file)] (when (map? c) c))
        cfg-b (let [c (edn-at req cb command/config-file)] (when (map? c) c))
        cfg (or cfg-b cfg-a {})
        spec-dir (strip (or (:spec-dir cfg) "dhf/spec"))
        model-rel (or (:model cfg) (str spec-dir "/model.edn"))
        owner-map (merge (owners (edn-at req ca model-rel)) (owners (edn-at req cb model-rel)))
        allow (distinct (concat (get-in cfg-b [:clean-room :allow]) (get-in cfg-a [:clean-room :allow])))
        diff (git req "diff" "--name-only" "-z" "--no-renames" ca cb)
        _ (when-not (zero? (:exit diff))
            (command/cannot-run! (str "the cuts " a " and " b " cannot be compared: " (:err diff))))
        paths (sort (remove str/blank? (str/split (:out diff) #"\u0000")))
        {:keys [scopes shared]} (classify {:owner-map owner-map :spec-dir spec-dir :allow allow} paths)]
    {:result {:scopes scopes :shared shared :from a :to b}
     :findings (vec (for [path shared]
                      (command/warning path "shared-file-changed"
                                       (str "changed between " a " and " b
                                            ", and no component owns it: a person must judge its readers"))))}))
