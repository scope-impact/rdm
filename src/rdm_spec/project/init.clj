(ns rdm-spec.project.init
  "Component Init (project context): writes a new project's skeleton into a
  path that does not exist yet, a git repository with no commit, green
  before anything is written in it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.project.hooks :as hooks]))

(def gitignore-lines ["dhf/allure-results/" "dhf/cycles/*/results/" "dhf/cycles/*/build/" "site-src/" "site/"])

(defn system-id [name]
  (let [s (-> (str/lower-case (str name)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (keyword (if (or (str/blank? s) (not (re-find #"^[a-z]" s))) (str "system-" s) s))))

(defn config-text [prefix]
  (str ";; The harness configuration: everything rdm-spec must not know by heart.\n"
       ";; Every path is relative to the project's root, the folder holding this file.\n"
       "{;; Stamped scenario ids are @<id-prefix>-NNNN.\n"
       " :id-prefix " (pr-str prefix) "\n\n"
       " ;; The spec, its id ledger, its C4 model, its event catalogue and its needs.\n"
       " :spec-dir \"dhf/spec\"\n"
       " :ledger \"dhf/spec/ids.edn\"\n"
       " :model \"dhf/spec/model.edn\"\n"
       " :events \"dhf/spec/events.edn\"\n"
       " :needs \"dhf/spec/needs.edn\"\n"
       " ;; Generated views, the raw sources, the domain wiki.\n"
       " :views-dir \"dhf/views\"\n"
       " :raw-dir \"dhf/raw\"\n"
       " :wiki-dir \"dhf/wiki\"\n"
       " ;; Words neither the spec nor the wiki may use: the stack, kept in dhf/harness/stack.md.\n"
       " :stack-words []\n"
       " ;; Where step definitions for \"rdm-spec run\" live.\n"
       " :steps-dir \"test/steps\"\n"
       " ;; Where test runs write Allure results, and where cycles are recorded.\n"
       " :results-dir \"dhf/allure-results\"\n"
       " :cycles-dir \"dhf/cycles\"\n\n"
       " ;; The implementation: every path a regeneration rebuilds. None yet.\n"
       " :implementation []\n"
       " :code-layout {:context [] :component []}\n\n"
       " ;; The clean room is the cut minus what is denied; every committed\n"
       " ;; top-level path, and every path directly under dhf/, is denied or kept.\n"
       " :clean-room {:deny {:implementation true\n"
       "                     :measurements [\"dhf/cycles\"]\n"
       "                     :generated [\"site\" \"site-src\" \"dhf/allure-results\"]}\n"
       "              :keep [\".gitignore\" \".githooks\" \".github\" \"dhf/spec\" \"dhf/harness\"\n"
       "                     \"dhf/raw\" \"dhf/wiki\" \"dhf/views\" \"dhf/decisions\"]}\n\n"
       " ;; Observe: the directories compared before and after a run, and the temporary directory.\n"
       " :agency {:watch [\"/\" \"~\"] :tmp \"/tmp\"}\n\n"
       " ;; What verify proves, in order: the tests first. Set their command.\n"
       " :verify [{:kind :tests :command []}]\n\n"
       " ;; The site.\n"
       " :site {:dir \"site\" :source \"site-src\" :builder [\"mkdocs\" \"build\" \"--strict\" \"-f\" \"site-src/mkdocs.yml\"]}}\n"))

(def readme-text
  "# The spec

The source of truth for what this project does. The code is built from it,
never the other way round.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: the system, its people, containers, bounded contexts and components, and every view |
| `events.edn` | The event catalogue: the events between bounded contexts and their contracts |
| `needs.edn` | The user needs every scenario traces to, each citing its sources |
| `ids.edn` | The id ledger; only `rdm-spec stamp` writes it |
| `glossary.md` | The vocabulary |
| `<context>/<component>.feature` | One component's scenarios, at the path the model's `:spec` names |

## Conventions

- **One scenario is one requirement**, tagged with exactly one id (`@<prefix>-NNNN`,
  handed out by `rdm-spec stamp`) and one level (`@e2e` or `@contract`). A need
  tag (`@UN-nnn`) on a feature, a rule or a scenario traces it to a need.
  `@wip` marks planned work. No other tag is allowed.
- **Ids are never reused**, not even after their scenario is deleted.
- **The spec never names its stack**: tools and languages are in
  `dhf/harness/stack.md`, and the configuration's `:stack-words` are refused here.
- **Every task reports the same way:** its result as EDN on standard output;
  one line per finding on the error stream, `FILE:LINE: RULE: what`, then one
  per warning; exit 0 a pass, 1 a finding, 2 a task that cannot run.

## The results contract

A scenario is verified when a test run, in any stack, writes an Allure result
naming it that passed, and none naming it that did not. A result is one
`<uuid>-result.json` file in a results directory, in Allure's own model, filled
from the Gherkin scenario:

| Element | Filled from |
|---|---|
| `name` | the scenario's title |
| `fullName` | the feature file's path and the title, `FILE:TITLE` |
| `historyId` | the scenario's id; for an outline row, the id and the row's values joined by `|` |
| `status` | `passed`, `failed` (a Then whose assertion did not hold), `broken` (anything else that went wrong) or `skipped` |
| `stage` | `finished` |
| `statusDetails` | when it did not pass: its `message` and `trace` |
| `start`, `stop` | milliseconds since the epoch |
| `steps` | one per Gherkin step, in order, named by its keyword and text, each with its status, start and stop |
| `parameters` | an outline row's values, `{name value}` named by the table's headings |
| `labels` | `feature`; `story` (the rule); `epic` and `suite` (the bounded context's id); `subSuite` (the component's id without its colon); a `tag` per Gherkin tag without its `@`; `framework` `gherkin`; and rdm-spec's own `spec` (the scenario id, exactly one), `context`, `component`, and `planned` `true` for a scenario tagged `@wip` |
| `links` | one `issue` per need the scenario traces to, its url `<needs>#UN-nnn` |
| `attachments` | evidence a step attached, a file beside the result |

A status is decided by `status`, the `spec` label and an outline row's
`parameters` only; everything else is kept for the report. A result with no
`spec` label verifies nothing.

### The Allure mapping

Which element of the spec each element of a result carries. A configuration's
`:allure` may replace rdm-spec's default, which is:

```
{:epic :context :feature :feature :story :rule :name :scenario
 :step :gherkin-step :parameter :example-row
 :labels {\"spec\" :scenario-id \"component\" :component \"tag\" :gherkin-tags}
 :links {:need {:type \"issue\" :url \"N#{id}\"}}
 :evidence {}}
```

`N` is the configuration's `:needs`. `:evidence` is `{level [attachment-name ...]}`:
a result of a scenario at that level carries an attachment of each name.
")

(def raw-readme
  "# Raw sources

What the spec is distilled from. Every user need cites at least one source
here, as `<source>:<path>@<commit>`.

## Rules

- **Never edited.** A record changes only to add a newer pin; an old pin stays,
  so a citation made against it still resolves.
- **Cited, never copied in.** `sources.edn` indexes each source: its repository,
  its record (a `.md` beside it), every pin ever cited, and its fenced paths.
- **A source's implementation is fenced**: never cited.
- **A source is retired, never deleted**: `:retired` says why, and nothing may cite it.
")

(def wiki-index
  "# Domain wiki

The domain, distilled from the raw sources (`dhf/raw/`). The spec rests on it.

## Rules

- **Every claim footnotes the source it came from**, as `<source>:<path>@<commit>`.
- **Where a page and a source disagree, the source wins.**
- **No page names an implementation**: the configured stack words are refused.
- **Every page is listed below**, and nothing is listed that is not a page.

## Pages
")

(def decisions-readme
  "# Decisions

Each decision is one Markdown file, `adr-NNNN-<title>.md`: its status, its
context, the decision and its consequences. A decision is never edited away:
a later one supersedes it and says so.
")

(def stack-text
  "# The stack

How this project is built, kept out of the spec on purpose: the spec says what
it does, this says what it is made of. The configuration's `:stack-words`
holds the words the spec and the wiki may not use.

| What | Choice |
|---|---|
")

(def cut-workflow
  "# Cuts the spec: every push to the default branch is tagged spec-N by rdm-spec.
name: cut
on:
  push:
    branches: [main, master]
permissions:
  contents: write
jobs:
  cut:
    if: github.ref == format('refs/heads/{0}', github.event.repository.default_branch)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - run: rdm-spec cut
")

(defn skeleton
  "The skeleton's files, path to text, for a project named NAME with PREFIX."
  [name prefix]
  (array-map
   "dhf/spec/model.edn" (str ";; The C4 model: the one source every view is generated from.\n"
                             (pr-str {:system {:id (system-id name) :name (str name)
                                               :description "Describe the system in a line"}})
                             "\n")
   "dhf/spec/events.edn" ";; The event catalogue: the events between this project's bounded contexts.\n{}\n"
   "dhf/spec/needs.edn" ";; The user needs every scenario traces to, each citing its sources.\n{}\n"
   "dhf/spec/ids.edn" ";; The id ledger: the highest id ever issued. Only rdm-spec stamp writes it.\n{:issued 0}\n"
   "dhf/spec/glossary.md" "# Glossary\n\nThe spec's vocabulary, one term per paragraph.\n"
   "dhf/spec/README.md" readme-text
   "dhf/harness/config.edn" (config-text prefix)
   "dhf/harness/stack.md" stack-text
   "dhf/raw/README.md" raw-readme
   "dhf/raw/sources.edn" ";; The sources index: each source's repository, record, pins and fenced paths.\n{}\n"
   "dhf/wiki/index.md" wiki-index
   "dhf/decisions/README.md" decisions-readme
   hooks/hook-path hooks/hook-text
   ".github/workflows/cut.yml" cut-workflow
   ".gitignore" (str "# rdm-spec: what a run writes, never the record\n" (str/join "\n" gitignore-lines) "\n")))

(defn valid-prefix! [p]
  (when-not (re-matches #"[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*" p)
    (project/cannot-run! (str "the id prefix " p " is refused: a prefix starts with a letter and holds letters, digits and single hyphens")))
  (when (= "UN" (str/upper-case p))
    (project/cannot-run! (str "the prefix " p " is refused: the prefix UN is taken by need tags"))))

(def next-steps
  ["write the user needs in dhf/spec/needs.edn, each citing a source recorded in dhf/raw/"
   "write the model in dhf/spec/model.edn and each component's scenarios at its :spec path"
   "rdm-spec stamp, rdm-spec views, rdm-spec check"
   "commit: the pre-commit hook runs rdm-spec check"])

(defn init-task [{:keys [args opts]}]
  (let [path (first args)
        prefix (get opts "--id-prefix" "spec")
        _ (valid-prefix! prefix)
        dir (project/abs path)]
    (when (or (fs/exists? dir) (fs/sym-link? dir))
      (project/cannot-run! (str "init writes into a path that does not exist, and " path " exists")))
    (let [files (skeleton (fs/file-name dir) prefix)]
      (fs/create-dirs dir)
      (project/git! dir "init" "-q")
      (doseq [[rel text] files]
        (let [f (project/abs dir rel)] (fs/create-dirs (fs/parent f)) (spit f text)))
      (hooks/install! dir)
      (project/report {:init {:path path :wrote (vec (sort (keys files))) :next next-steps}}))))
