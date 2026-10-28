(ns rdm-spec.project.init
  "Writes a new project's skeleton into a path that does not exist
  (dhf/spec/01-start-a-project/init.feature). The skeleton is shared with adopt."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.project.hooks :as hooks]))

(def gitignore-lines ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"])

(defn config-text [prefix]
  (str ";; The harness configuration: everything the harness must not know by heart.
;; Every path is relative to the project's root, the folder holding this file.
{;; Stamped scenario ids are @<id-prefix>-NNNN.
 :id-prefix " (pr-str prefix) "

 ;; Where the spec, the id ledger, the C4 model, the events and the needs live.
 :spec-dir \"dhf/spec\"
 :ledger \"dhf/spec/ids.edn\"
 :model \"dhf/spec/model.edn\"
 :events \"dhf/spec/events.edn\"
 :needs \"dhf/spec/needs.edn\"
 :views-dir \"dhf/views\"
 :raw-dir \"dhf/raw\"
 :wiki-dir \"dhf/wiki\"

 ;; The words neither the spec nor the wiki may use: the stack (dhf/harness/stack.md).
 :stack-words []

 ;; Where step definitions for \"rdm-spec run\" live.
 :steps-dir \"test/steps\"

 ;; The implementation: every path a regeneration rebuilds. None yet.
 :implementation []

 ;; The clean room is the cut minus what is denied; every committed top-level
 ;; path, and every path directly under dhf/, is denied or kept.
 :clean-room {:deny {:implementation true
                     :measurements [\"dhf/cycles\"]
                     :generated [\"site\" \"site-src\" \"dhf/allure-results\"]}
              :keep [\".gitignore\" \".githooks\" \".github\" \"dhf/spec\" \"dhf/harness\" \"dhf/raw\"
                     \"dhf/wiki\" \"dhf/views\" \"dhf/decisions\"]}

 ;; Observe: the watched directories (\"~\" is the home directory) and the temporary directory.
 :agency {:watch [\"/\" \"~\"] :tmp \"/tmp\"}

 ;; What verify proves, in order: the tests first. Set the test command.
 :verify [{:kind :tests :command []}]
 :results-dir \"dhf/allure-results\"
 :cycles-dir \"dhf/cycles\"

 ;; The site.
 :site {:dir \"site\" :source \"site-src\" :builder [\"mkdocs\" \"build\" \"--strict\" \"-f\" \"site-src/mkdocs.yml\"]}}
"))

(def spec-readme
  "# The spec

The source of truth for what this project does. The code is built from it,
never the other way round.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: the system, its containers, bounded contexts, components and views |
| `events.edn` | The event catalogue: the events between bounded contexts and their contracts |
| `needs.edn` | The user needs every scenario traces to, each citing its sources |
| `ids.edn` | The id ledger; only `rdm-spec stamp` writes it |
| `glossary.md` | The vocabulary |
| `<context>/<component>.feature` | A component's scenarios, at the component's spec path in the model |

## Conventions

- **One scenario is one requirement**, tagged with exactly one id (`@<prefix>-NNNN`,
  handed out by `rdm-spec stamp`, never reused) and one level (`@e2e` or `@contract`).
- **Every scenario traces to a user need** (`@UN-nnn` on it, its rule or its feature).
- **`@wip` marks planned work.** No other tag is allowed.
- **The spec never names its stack**; the configuration's `:stack-words` are refused.
- **Every task reports the same way**: its result as EDN on standard output; one line
  per finding on the error stream, `FILE:LINE: RULE: what`; exit 0 a pass, 1 a finding,
  2 a task that cannot run.

## The results contract

A scenario is verified when a test run, in any stack, writes an Allure result
labelled with its id that passed, and no result labelled with its id that did
not pass. Each result is one `<uuid>-result.json` in a results directory, in
Allure's own model, filled from the Gherkin scenario:

| Allure element | Filled from |
|---|---|
| `name` | the scenario's title |
| `fullName` | `<feature file>:<title>` |
| `historyId` | the scenario's id; for an outline row, the id and the row's values joined by `|` |
| `status`, `stage` | `passed`, `failed` (a Then whose assertion did not hold), `broken` (anything else that went wrong) or `skipped`; `finished` |
| `statusDetails` | when it did not pass: the message and the trace |
| `start`, `stop` | the scenario's start and stop, in milliseconds since the epoch |
| `steps` | one per Gherkin step, in order: its keyword and text as its name, its status, start and stop |
| `parameters` | an outline row's values, named by the table's headings |
| `labels` | `feature`; `story` (the rule); `epic` and `suite` (the bounded context); `subSuite` (the component's id without its colon); one `tag` per Gherkin tag, without its `@`, the feature's, the rule's and the scenario's own, in that order; `framework` `gherkin`; `spec` (the scenario id, exactly one), `context`, `component`, and `planned` `true` for a scenario tagged `@wip` |
| `links` | one `issue` link per need, its url `<needs>#UN-nnn` |
| `attachments` | evidence, on the result or on its steps |

A status is decided by `status`, the `spec` label and an outline row's
`parameters` only; everything else is kept for the report. A result with no
`spec` label verifies nothing.

## The default Allure mapping

Which element of the spec each Allure element carries. A configuration's
`:allure` may replace it; verify's tests check holds every result to it.

```
{:epic :context :feature :feature :story :rule :name :scenario
 :step :gherkin-step :parameter :example-row
 :labels {\"spec\" :scenario-id \"component\" :component \"tag\" :gherkin-tags}
 :links {:need {:type \"issue\" :url \"<needs>#{id}\"}}
 :evidence {}}
```
")

(def hook-text
  "#!/bin/sh
# Installed by rdm-spec: every commit is checked (core.hooksPath is .githooks).
exec rdm-spec check
")

(def workflow-text
  "# Installed by rdm-spec: every merge to the default branch is cut, once.
name: cut
on:
  push:
    branches: [main, master]
permissions:
  contents: write
jobs:
  cut:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - run: git fetch --tags origin
      - run: rdm-spec cut
")

(defn skeleton
  "The skeleton's files, in order: [[path text] ...]."
  [system-name prefix]
  [["dhf/spec/model.edn" (str ";; The C4 model: the one source every view is generated from.\n"
                              (project/edn-str {:system {:id (keyword (str/replace (str/lower-case (str system-name)) #"[^a-z0-9-]+" "-"))
                                                         :name (str system-name)
                                                         :description "What this system does"}}) "\n")]
   ["dhf/spec/events.edn" ";; The event catalogue: the events between bounded contexts, and their contracts.\n{}\n"]
   ["dhf/spec/needs.edn" ";; User needs: what a user requires, each citing the sources it rests on.\n{}\n"]
   ["dhf/spec/ids.edn" ";; The id ledger: the highest id ever issued. Only rdm-spec stamp writes it.\n{:issued 0}\n"]
   ["dhf/spec/glossary.md" "# Glossary\n\nThe vocabulary of the spec.\n"]
   ["dhf/spec/README.md" spec-readme]
   ["dhf/harness/config.edn" (config-text prefix)]
   ["dhf/harness/stack.md" "# The stack\n\nWhat the implementation is made of, kept out of the spec on purpose.\n\n| What | Choice |\n|---|---|\n"]
   ["dhf/raw/README.md" "# Raw sources\n\nWhat the spec is distilled from. Each source is indexed in `sources.edn`\nwith its repository, the commits it is pinned to and its fenced paths, and\nrecorded in a provenance record beside it.\n\n## Rules\n\n- **Never edited.** A record only gains a newer pin; an old pin stays.\n- **Cited as `<source>:<path>@<commit>`**, never copied in.\n- **A source's implementation is fenced**: never cited.\n- **A source is retired, never deleted.**\n"]
   ["dhf/raw/sources.edn" ";; The sources index: {source {:repository :record :pins [commit] :fenced [path]}}\n{}\n"]
   ["dhf/wiki/index.md" "# Domain wiki\n\nThe domain, distilled from the raw sources.\n\n## Rules\n\n- **Every claim footnotes the source it came from**, as `<source>:<path>@<commit>`.\n- **No page names an implementation.**\n- **Every page is listed below**, and nothing is listed that is not a page.\n\n## Pages\n"]
   ["dhf/decisions/README.md" "# Decisions\n\nEach decision is a numbered record, `adr-NNNN-<title>.md`: its context, the\ndecision, and its consequences. A decision is superseded, never edited away.\n"]
   [".githooks/pre-commit" hook-text]
   [".github/workflows/cut.yml" workflow-text]
   [".gitignore" (str "# rdm-spec: what a run writes, never the record\n" (str/join "\n" gitignore-lines) "\n")]])

(defn prefix-problem [p]
  (cond (= "UN" p) (str "the prefix UN is taken by need tags (@UN-nnn)")
        (not (re-matches #"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*" p))
        (str "the prefix " p " is refused: a prefix starts with a letter and holds letters, digits and single hyphens")))

(defn write-file! [dir rel text]
  (let [f (fs/path dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)
    (when (= rel ".githooks/pre-commit")
      (fs/set-posix-file-permissions f "rwxr-xr-x"))))

(def next-steps
  ["commit the skeleton"
   "describe the system in dhf/spec/model.edn and its needs in dhf/spec/needs.edn"
   "write scenarios under dhf/spec/, then run rdm-spec stamp and rdm-spec check"
   "set the :tests check's command in dhf/harness/config.edn"])

(defn init [run]
  (let [path (first (:args run))
        prefix (get-in run [:opts "--id-prefix"] "spec")
        dir (fs/absolutize (fs/path (:root run) path))]
    (when-let [p (prefix-problem prefix)] (project/cannot-run! p))
    (when (fs/exists? dir {:nofollow-links true})
      (project/cannot-run! (str path " already exists: init writes a new project into a path that does not exist")))
    (let [files (skeleton (fs/file-name dir) prefix)]
      (fs/create-dirs dir)
      (doseq [[rel text] files] (write-file! dir rel text))
      (project/git! dir "init" "-q")
      (hooks/install! dir)
      {:result {:init {:path path :wrote (mapv first files) :next next-steps}}})))
