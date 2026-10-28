(ns steps.project-hooks
  "Steps of the component Hooks (01-start-a-project/hooks.feature). The hook
  runs whatever rdm-spec is on PATH; here that is a wrapper, in the fixture,
  around the build under test, which also records that it ran."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep]]))

(defstep #"the pre-commit hook is installed"
  (fn [_]
    (let [ctx (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})
                                "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002"}]})})
          d (:dir ctx)
          bin (k/path (:top ctx) "bin")
          log (k/path (:top ctx) "hook.log")]
      (k/git! d "config" "core.hooksPath" ".githooks")
      (k/write! bin "rdm-spec" (str "#!/bin/sh\necho \"rdm-spec $*\" >> " log "\nexec " (str/join " " (k/rdm-argv)) " \"$@\"\n"))
      (fs/set-posix-file-permissions (k/path bin "rdm-spec") "rwxr-xr-x")
      (assoc ctx :log log :env {"PATH" (str bin ":" (System/getenv "PATH"))}))))

(defn- commit [ctx]
  (let [r (babashka.process/shell {:dir (:dir ctx) :out :string :err :string :continue true
                                   :env (k/base-env (:top ctx) (:env ctx))}
                                  "git" "commit" "-q" "-m" "a change")]
    r))

(defstep #"a commit is made"
  (fn [ctx]
    (k/git! (:dir ctx) "add" "-A")
    (let [ok (commit ctx)
          _ (k/write! (:dir ctx) "dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002" :level nil}]}))
          _ (k/git! (:dir ctx) "add" "-A")
          refused (commit ctx)]
      (assoc ctx :ok ok :refused refused))))

(defstep #"the hook runs \"rdm-spec check\""
  (fn [ctx]
    (k/expect! (zero? (:exit (:ok ctx))) (merge ctx (:ok ctx)) "the commit of a passing spec was refused")
    (k/expect! (str/includes? (str (k/slurp-in (:top ctx) "hook.log")) "rdm-spec check") ctx "the hook did not run rdm-spec check")
    ctx))

(defstep #"the commit is refused when the check exits other than 0"
  (fn [ctx]
    (k/expect! (not (zero? (:exit (:refused ctx)))) (merge ctx (:refused ctx)) "the commit of a failing spec was made")
    ctx))
