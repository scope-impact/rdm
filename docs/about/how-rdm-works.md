# How it works

## One idea

**RDM audits a record; it does not author it.** The spec is Gherkin,
written by you or by a factory that also regenerates code from it. RDM's
contract with it is small: a requirement is a scenario tagged with an id, and
a test run claims it with an Allure `tag` label of that id. The rest of the
record —
risks, checklists, controlled documents, validation records — is Markdown in
git. Test results and history are recorded by the tools that make them.
Everything RDM produces — gate verdicts, the graph, the rendered documents,
the traceability matrix, the release evidence bundle — is derived from them,
never edited by hand or fed back in.

The record changes only through review: the spec in its own
process, the rest by a pull request that someone other than the author
approves; that review is the approval, and the merge records it. An agent
that wants to change the record opens a pull request, the same as a person.

| Written (the record) | Derived (by RDM) |
| --- | --- |
| the spec: Gherkin feature files, each scenario tagged with an id | gate verdicts (release gate, gap analysis) |
| the risk policy and risk register | the RDF graph, its SHACL validation, the agent server's answers |
| checklists, and `[[KEY]]` references in documents | the traceability matrix and verification data |
| validation records of the user needs | rendered regulatory documents (PDF / DOCX) |
| acceptance tests tagged `@allure.tag("<id>")` | the evidence bundle kept with a release |
| — | test results (Allure), recorded by running the tests |
| — | approval and change history, recorded by git and the forge |

The two rows without a written side are facts a tool records: what ran and
what passed, and who changed what, when. RDM reads them; nobody writes them.

## The data model

Every entity is declared **once**, in one place, with one id. Every link is
either **written** or **derived** by RDM; a fact that can be derived is never
also written, because two copies of one fact drift. The words are the
[glossary](../reference/glossary.md)'s.

```mermaid
flowchart LR
    UN["User need<br>a Feature"]
    R["Requirement<br>a tagged scenario"]
    CTX["Bounded context"]
    DOC["Controlled document"]
    RISK["Risk"]
    CL["Checklist clause"]
    RUN["Test run"]
    C["Commit"]
    SRC["Source file"]

    R -- "under ✎" --> UN
    R -- "owned by" --> CTX
    RISK -- "controls ✎" --> R
    RUN -- "claims ✎ (tag)" --> R
    RUN -- "tested at" --> C
    RUN -- "exercises output ✎" --> SRC
    DOC -- "references ✎" --> CL
    DOC -- "references ✎" --> DOC
    DOC -- "latest change" --> C
```

✎ marks a written link; every other link is derived.

| Entity | Declared in | Id | Written there |
| --- | --- | --- | --- |
| **Bounded context** | a directory of feature files below the spec's root (`spec/alarms/`) | the directory name | its feature files |
| **Requirement** | a scenario tagged with an id | the id tag (`REQ-12`, `item-02-001`) | its title, its `Rule:`, its other tags |
| **User need** | a `Feature` (or what a needs adapter reads, see [adapters](../use/spec-and-tests.md#adapters-loylys-layout)) | the Feature's title | its description: the need's text |
| **Controlled document** | any Markdown file in the DHF with a frontmatter `id` | its `id` | `title`, `revision`, `references:` (documents it relies on), checklist references (`[[KEY]]`) |
| **Risk** | a `kind: risk` document, `risks:` | its `id` | hazard, situation, harm, category, scores, `controls:` (requirement ids), residual, acceptance |
| **Risk policy** | a document's `risk_policy:` | — | severities, probabilities, levels, acceptability |
| **Checklist, clause** | a checklist file (text or RDF) | the clause key, e.g. `62304:5.2.2` | each clause's description; includes |
| **Test run** | an Allure result, written by running the tests | its uuid | its `tag` labels, the requirements it claims; optionally `output` labels |
| **Commit** | git | its sha | nothing — it is recorded |

Derived, never declared:

| Fact | Derived from |
| --- | --- |
| a requirement's context | the directory its feature file sits in |
| the user need a requirement addresses | the Feature it sits under |
| the commit a run tested | the `commit` label `rdm.pytest_plugin` writes at run time |
| a document's latest commit, and the commit that landed it | git history |
| whether a requirement is verified | the results of the runs that claim it |
| a risk's level and residual decision | its scores against the risk policy, and whether its controls are verified |
| the traceability matrix | requirements, user needs and test results |

How a change reaches the record: [Changing the record](../use/changing-the-record.md);
how the spec is laid out: [The spec and its tests](../use/spec-and-tests.md).

## An audited repository

```mermaid
flowchart TD
    subgraph repo["your product's repository"]
        subgraph spec["the spec — Gherkin, by you or a factory"]
            PLAN["spec/**/*.feature<br>tagged Gherkin scenarios"]
        end
        subgraph record["the rest of the record — controlled"]
            RISKS["dhf/ risk policy, register"]
            DOCS["dhf/ documents, validation records"]
            TESTS["tests/acceptance<br>@allure.tag(id)"]
        end
        subgraph enforce["enforcement — on by default"]
            CI["CI workflow<br>the gates on every push"]
        end
        subgraph plan["planning — never evidence"]
            PM["issues, task boards"]
        end
    end
    PM -. "only path in: a reviewed commit" .-> record
    style plan stroke-dasharray: 5 5
```

`rdm adopt` lays down the record skeleton and the CI workflow without
touching existing files ([get started](../install/getting-started.md)). Planning tools stay
outside the record: [Plan vs. record](plan-vs-record.md).

## What RDM depends on

1. a git repository;
2. a spec as Gherkin feature files (`spec/` or `features/`, or `spec:` in
   `dhf/config.yml`) and a design history file (`dhf/`);
3. Allure results from running the acceptance tests.

Nothing about how the work was planned, which tracker is used, or who (or
what) wrote the change. How RDM's own code is laid out, one package per
bounded context, is its [system architecture](../dhf/documents/architecture.md).
