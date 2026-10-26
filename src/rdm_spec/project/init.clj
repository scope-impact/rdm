(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): writes a new project's skeleton into a
  path that does not exist yet, as a git repository with no commit, the hook
  installed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.project.hooks :as hooks]))

(defn prefix-problem
  "Why an id prefix cannot be one, or nil."
  [p]
  (cond
    (not (re-matches #"[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*" p))
    (str "the id prefix " p " cannot start a tag: a prefix starts with a letter and holds letters, digits and single hyphens")
    (= "UN" (str/upper-case p))
    (str "the prefix " p " is taken by need tags (@UN-nnn)")))

(def gitignore-lines
  ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"])

(def spec-readme
  "# The spec

The spec of this project, the source of truth for what it does. The
vocabulary is `glossary.md`.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: the system, its containers, bounded contexts and components, and every view |
| `events.edn` | The event catalogue: the events between the bounded contexts, and their contracts |
| `needs.edn` | The user needs every scenario traces to |
| `ids.edn` | The id ledger; only `rdm-spec stamp` writes it |
| `glossary.md` | The vocabulary |
| `<context>/<component>.feature` | The scenarios a component owns |
| `<context>/<component>.md` | The component's design prose, where it has any |

## Conventions

- **One scenario is one requirement**, tagged with exactly one id and one
  level (`@e2e` or `@contract`). A need tag (`@UN-nnn`) on a feature or a
  rule covers every scenario under it, and a scenario may carry its own.
  `@wip` marks planned work. No other tag is allowed.
- **Ids are handed out by `rdm-spec stamp`** from the ledger and never reused.
- **Every scenario belongs to one component**, by its file's location.
- **The spec never names its stack.** The stack lives in
  `dhf/harness/stack.md`; the spec check refuses the configured stack words.
- **How every task reports.** Standard output holds only the task's result,
  as EDN. The error stream holds one line per finding, `FILE:LINE: RULE:
  what is wrong`, then one line per warning. Exit 0 is a pass, 1 a finding,
  2 a task that cannot run.

## The results contract

A scenario is verified when a test run, in any stack, writes an Allure
result (`<uuid>-result.json` in the results directory) whose `spec` label is
its id with the status `passed`, and no result for it that did not pass.
Each element a result carries is Allure's own, filled from the Gherkin
scenario (`rdm-spec run` writes exactly these):

| Element | Filled from |
|---|---|
| `name` | the scenario's title |
| `fullName` | the feature file and the title, `FILE:TITLE` |
| `historyId` | the scenario's id; for an outline row, the id and the row's values joined by `|` |
| `status`, `stage` | `passed`, `failed` (a Then whose assertion did not hold), `broken` (anything else that went wrong) or `skipped`; `finished` |
| `statusDetails` | when it did not pass: its `message` and `trace` |
| `start`, `stop` | the scenario's start and stop, in milliseconds since the epoch |
| `steps` | one per Gherkin step, its keyword and text as its name, with its own status, start and stop |
| `parameters` | an outline row's values, named by the table's headings |
| label `feature` | the feature's name |
| label `story` | the rule's name |
| labels `epic`, `suite` | the bounded context's id |
| label `subSuite` | the component's id without its colon |
| labels `tag` | one per Gherkin tag, without its `@` |
| label `framework` | `gherkin` |
| label `spec` | the scenario's id, exactly one |
| labels `context`, `component` | the bounded context's id and the component's name |
| label `planned` | `true` for a scenario tagged `@wip` |
| `links` | one `issue` per need, its url `dhf/spec/needs.edn#UN-nnn` |
| `attachments` | evidence a step attached |

A status is decided by `status`, the `spec` label and an outline row's
`parameters` only; everything else is kept for the report. A result with no
`spec` label verifies nothing.

### The Allure mapping

`rdm-spec verify` holds every result to the configuration's `:allure`, which
says which element of the spec each Allure element carries. rdm-spec's
default, used when the configuration declares none:

```
{:epic :context :feature :feature :story :rule :name :scenario
 :step :gherkin-step :parameter :example-row
 :labels {\"spec\" :scenario-id \"component\" :component \"tag\" :gherkin-tags}
 :links {:need {:type \"issue\" :url \"dhf/spec/needs.edn#{id}\"}}
 :evidence {}}
```

`:evidence` is `{level [attachment-name ...]}`: a result of a scenario at
that level carries an attachment of each name, on the result or on any of
its steps.
")

(defn config-text [prefix]
  (str ";; The harness configuration: everything the harness must not know by heart.
;; Everything here is committed; nothing machine-local belongs here.
{;; Stamped scenario ids are @<id-prefix>-NNNN.
 :id-prefix " (pr-str prefix) "

 ;; Where the spec, the id ledger, the model, the events and the needs live.
 :spec-dir \"dhf/spec\"
 :ledger \"dhf/spec/ids.edn\"
 :model \"dhf/spec/model.edn\"
 :events \"dhf/spec/events.edn\"
 :needs \"dhf/spec/needs.edn\"
 :views-dir \"dhf/views\"
 :raw-dir \"dhf/raw\"
 :wiki-dir \"dhf/wiki\"
 ;; The words the spec and the wiki may never use: the stack (dhf/harness/stack.md).
 :stack-words []

 ;; Where the step definitions for \"rdm-spec run\" live.
 :steps-dir \"test/steps\"

 ;; The implementation: every path a regeneration rebuilds. None yet.
 :implementation []
 :code-layout {:context [] :component []}

 ;; The clean room is the cut minus what is denied. Every committed top-level
 ;; path, and every path directly under dhf/, is denied or kept.
 :clean-room {:deny {:implementation true
                     :measurements [\"dhf/cycles\"]
                     :generated [\"site\" \"site-src\" \"dhf/allure-results\"]}
              :keep [\".gitignore\" \".githooks\" \".github\" \"dhf/spec\" \"dhf/harness\"
                     \"dhf/raw\" \"dhf/wiki\" \"dhf/views\" \"dhf/decisions\"]}

 ;; Observe: the directories whose entries are compared before and after a
 ;; run (\"~\" is the home directory), and the temporary directory.
 :agency {:watch [\"/\" \"~\"] :tmp \"/tmp\"}

 ;; What verify proves, in order: the tests first. Set the test command.
 :verify [{:kind :tests :command nil}]

 :results-dir \"dhf/allure-results\"
 :cycles-dir \"dhf/cycles\"
 :site {:dir \"site\" :source \"site-src\" :builder [\"mkdocs\" \"build\" \"--strict\" \"-f\" \"site-src/mkdocs.yml\"]}
 :c4 {:view-element-limit 20 :description-max-chars 45}}
"))

(defn system-id [name]
  (let [s (-> (str/lower-case name) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (if (re-matches #"[a-z].*" s) s (str "system-" s))))

(defn skeleton
  "The skeleton's files: {path text}."
  [name prefix]
  {"dhf/spec/model.edn"
   (str ";; The C4 model: the one source every view is generated from.\n"
        "{:system {:id :" (system-id name) " :name " (pr-str name) " :description \"What the system does\"}}\n")
   "dhf/spec/events.edn" ";; The event catalogue: the events between bounded contexts, and their contracts.\n{}\n"
   "dhf/spec/needs.edn" ";; The user needs every scenario traces to, each citing the raw sources it rests on.\n{}\n"
   "dhf/spec/ids.edn" ";; The id ledger: the highest id ever issued. Only rdm-spec stamp writes it.\n{:issued 0}\n"
   "dhf/spec/glossary.md" "# Glossary\n\nThe vocabulary of the spec. When a term is resolved or changes, this file changes in the same commit.\n"
   "dhf/spec/README.md" spec-readme
   "dhf/harness/config.edn" (config-text prefix)
   "dhf/harness/stack.md" "# The stack\n\nHow this project is built, kept out of the spec on purpose.\n\n| What | Choice |\n|---|---|\n"
   "dhf/raw/README.md" (str "# Raw sources\n\nWhat the spec is distilled from. Every user need cites at least one source here.\n\n"
                            "## Rules\n\n- **Never edited.** A record changes only to add a newer pin; the old pin stays.\n"
                            "- **Cited as `<source>:<path>@<commit>`**, never copied in.\n"
                            "- **A source's implementation is fenced** (`sources.edn`, `:fenced`): never cited.\n"
                            "- **A source is retired, never deleted** (`:retired` and the reason).\n")
   "dhf/raw/sources.edn" ";; The sources index: {:id {:repository :record :pins [commit] :fenced [path]}}.\n{}\n"
   "dhf/wiki/index.md" (str "# Domain wiki\n\nThe domain, distilled from the raw sources (`dhf/raw/`).\n\n"
                            "## Rules\n\n- **Every claim footnotes the source it came from**, as `<source>:<path>@<commit>`.\n"
                            "- **No page names an implementation.**\n- **Every page is listed below.**\n\n## Pages\n")
   "dhf/decisions/README.md" "# Decisions\n\nOne record per decision, `adr-NNNN-<title>.md`: its context, the decision and its consequences.\n"
   hooks/hook-path hooks/pre-commit
   ".github/workflows/cut.yml" (str "# Every merge to the default branch is cut: tagged spec-N and pushed.\n"
                                    "name: cut\non:\n  push:\n    branches: [main]\npermissions:\n  contents: write\n"
                                    "jobs:\n  cut:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n"
                                    "        with:\n          fetch-depth: 0\n      - run: rdm-spec cut\n")
   ".gitignore" (str "# rdm-spec: what a run writes, never the record\n" (str/join "\n" gitignore-lines) "\n")})

(def next-steps
  ["cd into the project and declare a user need in dhf/spec/needs.edn, citing a source recorded in dhf/raw/sources.edn"
   "describe the system and its bounded contexts in dhf/spec/model.edn, then run rdm-spec views"
   "write a component's scenarios in dhf/spec/<context>/<component>.feature, then run rdm-spec stamp"
   "set the :tests check's command in dhf/harness/config.edn"
   "run rdm-spec check, as the pre-commit hook will"])

(defn write-skeleton!
  "Writes each file that does not exist yet (a symbolic link counts as
  existing). Returns {:wrote [..] :kept [..]}."
  [dir files]
  (reduce (fn [acc [p text]]
            (let [f (fs/path dir p)]
              (if (fs/exists? f {:nofollow-links true})
                (update acc :kept conj p)
                (do (fs/create-dirs (fs/parent f))
                    (spit (fs/file f) text)
                    (update acc :wrote conj p)))))
          {:wrote [] :kept []}
          (sort-by key files)))

(defn stage [{:keys [root args opts]}]
  (let [path (first args)
        prefix (or (:id-prefix opts) "spec")
        dir (fs/absolutize (fs/path root path))]
    (when-let [why (prefix-problem prefix)] (cmd/cannot-run! why))
    (when (fs/exists? dir {:nofollow-links true})
      (cmd/cannot-run! (str "the path " path " already exists: init writes only into a path that does not exist")))
    (fs/create-dirs dir)
    (let [{:keys [exit err]} (cmd/git (str dir) "init" "-q")]
      (when-not (= 0 exit) (cmd/cannot-run! (str "git init failed: " err))))
    (let [{:keys [wrote]} (write-skeleton! dir (skeleton (str (fs/file-name dir)) prefix))]
      (hooks/install! (str dir))
      {:result {:init {:path path :wrote wrote :next next-steps}}})))
