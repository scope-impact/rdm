//! The skeleton init writes (spec-0086; the glossary's "Skeleton"), but for
//! the pre-commit hook, which project/hooks writes, and the id prefix's rule
//! (spec-0089, spec-0223). Each file holds what its row of spec-0086's table
//! says (ED-09: written from the table, the results contract named without a
//! path).

use alloc::format;
use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;

/// Refuses an id prefix that cannot start a tag: a letter first, then
/// letters, digits and single hyphens; UN is the need tags'.
pub fn check_prefix(prefix: &str) -> Result<(), String> {
    if prefix == "UN" {
        return Err("the prefix UN is taken by need tags (@UN-nnn); choose another".into());
    }
    let b = prefix.as_bytes();
    let ok = !b.is_empty()
        && b[0].is_ascii_alphabetic()
        && b.iter().all(|c| c.is_ascii_alphanumeric() || *c == b'-')
        && !prefix.ends_with('-')
        && !prefix.contains("--");
    if ok {
        Ok(())
    } else {
        Err(format!(
            "the id prefix {prefix:?} cannot start a tag: a prefix starts with a letter and holds letters, digits and single hyphens"
        ))
    }
}

/// The lines init writes to .gitignore, which adopt names when it keeps one.
pub const IGNORE_LINES: &[&str] = &["dhf/allure-results/", "dhf/cycles/*/results/", "dhf/cycles/*/build/", "site-src/", "site/"];

/// What init names as the next steps.
pub fn next_steps(path: &str) -> Vec<String> {
    vec![
        format!("cd {path}"),
        "write the first feature file under dhf/spec/, and the needs its scenarios trace to in dhf/spec/needs.edn".into(),
        "rdm-spec stamp".into(),
        "rdm-spec check".into(),
        "set the :tests check's :command in dhf/harness/config.edn".into(),
        "git add -A && git commit -m \"Start the spec\"".into(),
    ]
}

/// Every file of the skeleton for the id prefix, path and content, in
/// spec-0086's order. The same for init and adopt (spec-0079): the system is
/// named "System" until the engineer names it.
pub fn files(prefix: &str) -> Vec<(&'static str, String)> {
    vec![
        (
            "dhf/spec/model.edn",
            ";; The C4 model: the one source every view of the architecture is generated
;; from. Name the system, then add people, external systems, containers with
;; their bounded contexts and components, relationships and views as the
;; design is decided.
{:system {:id :system
          :name \"System\"
          :description \"Describe the system in a few words\"}}
"
            .into(),
        ),
        (
            "dhf/spec/events.edn",
            ";; The event catalogue: the domain events that pass between bounded contexts,
;; each with its description, its one publisher, the keys it carries and their
;; shape. None yet.
{}
"
            .into(),
        ),
        (
            "dhf/spec/needs.edn",
            ";; The user needs: what a user requires, independent of any solution, each
;; citing the raw sources it rests on (dhf/raw/README.md). Every scenario
;; traces to at least one, by a @UN-nnn tag. None yet:
;; {\"UN-001\" {:text \"...\" :sources [\"source:path@commit\"]}}
{}
"
            .into(),
        ),
        (
            "dhf/spec/ids.edn",
            ";; The id ledger: the highest scenario id ever issued. Only rdm-spec stamp
;; writes it, so an id is never issued twice. Never edit by hand.
{:issued 0}
"
            .into(),
        ),
        (
            "dhf/spec/glossary.md",
            "# Glossary

The vocabulary of this project's spec: each term in bold, then what it
means, and what to avoid saying instead. A scenario uses these words and no
others for the same things. No term yet.
"
            .into(),
        ),
        ("dhf/spec/README.md", readme(prefix)),
        ("dhf/harness/config.edn", config(prefix)),
        (
            "dhf/harness/stack.md",
            "# The stack

What this project is built with, kept out of the spec on purpose: the spec
says what the project does, this says what it is made of. One row per
choice; the spec check refuses a word listed in the configuration's
`:stack-words` anywhere in the spec or the wiki.

| What | Choice |
|---|---|
"
            .into(),
        ),
        (
            "dhf/raw/README.md",
            "# Raw sources

What this project's spec is distilled from. Every user need cites at least
one source here (`dhf/spec/needs.edn`, `:sources`), so a rule that rests on
nothing is visible as one.

## Rules

- **Never edited.** A record changes only to add a newer pin, and the old
  pin stays listed: a citation made against it must still resolve.
- **One record per source:** a `.md` file here naming the source, the
  commit or version it is pinned to, and what it is evidence for, indexed for
  machines in `sources.edn`.
- **Cited, never copied in:** a citation names `<source>:<path>@<commit>`.
- **A source's implementation is fenced:** its code paths are listed in
  `sources.edn` under `:fenced` and never cited.
- **Where sources conflict, say so** in the need that follows one, and why.
"
            .into(),
        ),
        (
            "dhf/raw/sources.edn",
            ";; The sources index: for each raw source, its repository, every pin ever
;; cited (a pin is never dropped) and its fenced paths. None yet:
;; {:name {:repository \"https://...\" :record \"name.md\" :pins [\"abc1234\"] :fenced []}}
{}
"
            .into(),
        ),
        (
            "dhf/wiki/index.md",
            "# Domain wiki

The domain distilled from the raw sources (`dhf/raw/`). The wiki sits
between the sources and the spec: a need or a scenario restates what a page
says, and a page restates what a source says.

## Rules

- **Every claim footnotes the source it came from**, as
  `<source>:<path>@<commit>`.
- **Where a page and a source disagree, the source wins** and the page is
  the bug.
- **No page names an implementation**: the configuration's stack words are
  refused.
- **Every page is listed below**, and nothing is listed that is not a page.

## Pages

No page yet.
"
            .into(),
        ),
        (
            "dhf/decisions/README.md",
            "# Decisions

How decisions are recorded: one file per decision,
`adr-NNNN-<slug>.md`, numbered in order and never renumbered. Each says its
status (proposed, accepted, superseded by another), its context, the
decision, and its consequences. A decision is changed by a new one that
supersedes it, never by editing an accepted one. Why a scenario exists
belongs here or in its slice's decisions, never in the feature file.
"
            .into(),
        ),
        (
            ".github/workflows/cut.yml",
            "# Cuts the spec: on every push to the default branch, rdm-spec cut tags
# HEAD spec-N when it is origin/HEAD and pushes the tag. A regeneration reads
# a cut and nothing else.
name: cut
on:
  push:
permissions:
  contents: write
jobs:
  cut:
    if: github.ref_name == github.event.repository.default_branch
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - run: rdm-spec cut
"
            .into(),
        ),
        (".gitignore", {
            let mut s = String::from("# What rdm-spec's runs write, never the record\n");
            for l in IGNORE_LINES {
                s.push_str(l);
                s.push('\n');
            }
            s
        }),
    ]
}

fn config(prefix: &str) -> String {
    format!(
        ";; The harness configuration: everything rdm-spec must not know by heart.
;; Every path is relative to the project's root, the folder holding this file.
{{;; Stamped scenario ids are @<id-prefix>-NNNN.
 :id-prefix {}

 ;; The spec, its id ledger, its C4 model, its event catalogue, its needs.
 :spec-dir \"dhf/spec\"
 :ledger \"dhf/spec/ids.edn\"
 :model \"dhf/spec/model.edn\"
 :events \"dhf/spec/events.edn\"
 :needs \"dhf/spec/needs.edn\"
 ;; The generated views of the model.
 :views-dir \"dhf/views\"
 ;; The raw sources and the domain wiki.
 :raw-dir \"dhf/raw\"
 :wiki-dir \"dhf/wiki\"
 ;; The words neither the spec nor the wiki may use: the stack's
 ;; (dhf/harness/stack.md).
 :stack-words []

 ;; Where results are written when a task is given neither --results nor
 ;; RESULTS_DIR, and where each cycle's record lives.
 :results-dir \"dhf/allure-results\"
 :cycles-dir \"dhf/cycles\"
 ;; The site publish writes.
 :site {{:dir \"site\" :source \"site-src\"}}

 ;; The implementation: every path a regeneration rebuilds. None yet.
 :implementation []

 ;; The clean room is the cut minus what is denied: the implementation, the
 ;; measurements and the generated output. Every committed top-level path,
 ;; and every path directly under dhf/, is denied or kept.
 :clean-room {{:deny {{:implementation true
                     :measurements [\"dhf/cycles\"]
                     :generated [\"site\" \"site-src\" \"dhf/allure-results\"]}}
              :keep [\".gitignore\" \".githooks\" \".github\" \"dhf/spec\" \"dhf/harness\"
                     \"dhf/raw\" \"dhf/wiki\" \"dhf/views\" \"dhf/decisions\"]}}

 ;; What a run is observed for: the directories whose entries are compared
 ;; before and after it (\"~\" is the home directory), and the temporary one.
 :agency {{:watch [\"/\" \"~\"] :tmp \"/tmp\"}}

 ;; What verify proves about a build, in order. The tests check comes first;
 ;; set its :command to the command that runs the tests and writes Allure
 ;; results into RESULTS_DIR.
 :verify [{{:kind :tests}}]}}
",
        rdm_kernel::edn::quote(prefix)
    )
}

fn readme(prefix: &str) -> String {
    format!(
        "# The spec

The source of truth for what this project does. The code is built from it,
never the other way round.

| Path | What it is |
|---|---|
| `model.edn` | The C4 model: the system, its containers, bounded contexts and components, and every view |
| `events.edn` | The event catalogue: the events between bounded contexts and their contracts |
| `needs.edn` | The user needs every scenario traces to |
| `ids.edn` | The id ledger; only `rdm-spec stamp` writes it |
| `glossary.md` | The vocabulary |
| `<context>/<component>.feature` | One component's scenarios, named by the component's name in the model |

## Conventions

- **One scenario is one requirement**, tagged with exactly one id
  (`@{prefix}-NNNN`, handed out by `rdm-spec stamp` and never reused) and one
  level: `@e2e` (through the product, as its user drives it) or `@contract`
  (a file format or an interface others build on). A need tag (`@UN-nnn`) on
  a feature, a rule or a scenario covers every scenario under it. `@wip`
  marks planned work. No other tag is allowed.
- **Where tags go:** an id and a level on a scenario or scenario outline
  only; a need tag on a feature, a rule or a scenario; `@wip` on a scenario
  only.
- **The spec says what is true, never how it came to be.** Feature files
  hold Gherkin only; why a rule exists is a decision (`dhf/decisions/`).
- **The spec never names its stack.** Tools and languages are in
  `dhf/harness/stack.md`; the spec check refuses the configured stack words.
- **A Then that says nothing changed is paired with the outcome** the
  command must have, so a command that never ran cannot pass it.

## The results contract

A scenario is verified by test results, whatever stack ran the tests: one
Allure result per scenario, and one per row of a scenario outline, each a
`<uuid>-result.json` file in the results directory. Each Allure element of a
result is filled from the Gherkin scenario:

| Element | Filled from |
|---|---|
| `name` | the scenario's title |
| `fullName` | the feature file's path, `:`, and the title |
| `historyId` | the scenario's id; for an outline row, the id and the row's values joined by `\\|` |
| `status`, `stage` | `passed`, `failed` (a Then whose assertion did not hold), `broken` (anything else that went wrong) or `skipped`; `finished` |
| `statusDetails` | why it did not pass: a message and a trace |
| `start`, `stop` | the scenario's start and stop, in milliseconds since the epoch |
| `steps` | one per Gherkin step, in order, its keyword and text as its name, with its own status, start and stop |
| `parameters` | an outline row's values, named by the table's headings |
| label `feature` | the feature's name |
| label `story` | the rule's name |
| labels `epic` and `suite` | the bounded context's id |
| label `subSuite` | the component's id without its colon |
| labels `tag` | one per Gherkin tag without its `@`: the feature's, the rule's and the scenario's, in that order |
| label `framework` | `gherkin` |
| label `spec` | the scenario's id, exactly one |
| labels `context` and `component` | the bounded context's id and the component's name |
| label `planned` | `true` for a scenario tagged `@wip` |
| `links` | one `issue` per need, its url `<needs>#UN-nnn`, `<needs>` the configuration's `:needs` |
| `attachments` | evidence |

A scenario is verified when a result labelled with its id passed and no
result labelled with its id did not pass; an outline, when every row has a
passing result. A result with no `spec` label verifies nothing.

rdm-spec's default Allure mapping, which a configuration's `:allure` may
replace:

```
{{:epic :context :feature :feature :story :rule :name :scenario
 :step :gherkin-step :parameter :example-row
 :labels {{\"spec\" :scenario-id \"component\" :component \"tag\" :gherkin-tags}}
 :links {{:need {{:type \"issue\" :url \"N#{{id}}\"}}}}
 :evidence {{}}}}
```

`N` is the configuration's `:needs`. `:evidence` is `{{level [attachment-name
...]}}`: a result of a scenario at that level carries an attachment of each
name, on the result or on any of its steps.
"
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use rdm_kernel::edn::{self, Edn};

    #[test]
    fn a_prefix_starts_with_a_letter_and_holds_letters_digits_and_single_hyphens() {
        for ok in ["spec", "sauna", "a-b1", "X9", "abc-def-g"] {
            assert!(check_prefix(ok).is_ok(), "{ok}");
        }
        for bad in ["9lives", "-a", "a-", "a--b", "a_b", "", "a b", "ä"] {
            let why = check_prefix(bad).unwrap_err();
            assert!(why.contains("starts with a letter") && why.contains("letters, digits and single hyphens"), "{bad}: {why}");
        }
    }

    #[test]
    fn the_prefix_un_is_the_need_tags() {
        let why = check_prefix("UN").unwrap_err();
        assert!(why.contains("UN") && why.contains("need tags"), "{why}");
    }

    #[test]
    fn the_skeleton_is_spec_0086s_table_but_the_hook() {
        let paths: Vec<&str> = files("spec").iter().map(|f| f.0).collect();
        assert_eq!(
            paths,
            [
                "dhf/spec/model.edn", "dhf/spec/events.edn", "dhf/spec/needs.edn", "dhf/spec/ids.edn", "dhf/spec/glossary.md",
                "dhf/spec/README.md", "dhf/harness/config.edn", "dhf/harness/stack.md", "dhf/raw/README.md", "dhf/raw/sources.edn",
                "dhf/wiki/index.md", "dhf/decisions/README.md", ".github/workflows/cut.yml", ".gitignore"
            ]
        );
    }

    #[test]
    fn every_edn_file_reads_and_the_configuration_carries_the_prefix() {
        for (path, content) in files("sauna") {
            if path.ends_with(".edn") {
                assert!(edn::read(&content).is_ok(), "{path}");
            }
        }
        let config = files("sauna").into_iter().find(|f| f.0 == "dhf/harness/config.edn").unwrap().1;
        let c = edn::read(&config).unwrap();
        assert_eq!(c.get("id-prefix"), Some(&Edn::str("sauna")));
        assert_eq!(c.get("verify").unwrap().items()[0].get("kind"), Some(&Edn::kw("tests")));
        let model = files("spec").into_iter().find(|f| f.0 == "dhf/spec/model.edn").unwrap().1;
        assert_eq!(edn::read(&model).unwrap().get("system").unwrap().get("name"), Some(&Edn::str("System")));
    }

    #[test]
    fn the_ignore_file_holds_the_ignore_lines() {
        let ignore = files("spec").into_iter().find(|f| f.0 == ".gitignore").unwrap().1;
        let lines: Vec<&str> = ignore.lines().filter(|l| !l.is_empty() && !l.starts_with('#')).collect();
        assert_eq!(lines, IGNORE_LINES);
        assert_eq!(IGNORE_LINES.len(), 5);
    }

    #[test]
    fn the_next_steps_start_in_the_new_project() {
        let next = next_steps("sauna");
        assert!(next[0].contains("cd sauna"));
        assert!(next.iter().any(|s| s.contains("rdm-spec check")));
    }
}
