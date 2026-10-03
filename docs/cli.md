# CLI reference

`rdm --version` · `rdm <command> --help` for full flag details.

## Core

| Command | What it does |
|---|---|
| `rdm init [-o DIR]` | scaffold a **new** documentation project (templates, Makefile, render config; default `-o dhf`) into a directory that does not exist yet, and print the next steps |
| `rdm adopt [TARGET]` | bring an **existing** repository under design controls: DHF skeleton and render config, runbook, design-gate hooks, session bootstrap, CI workflow, `.gitignore` — skips (never overwrites) existing files |
| `rdm render TEMPLATE CONFIG [DATA…]` | render a Jinja2 Markdown template with the data files (each file's stem becomes a template variable) → stdout |
| `rdm gap [-l] [-c] [-v] CHECKLIST [FILES…]` | report the gaps: the clauses of a checklist no document references with `[[KEY]]`; `-l` list built-ins, `-c` coverage table (several checklists), `-v` name the gaps; exit 0 none / 3 gaps / 2 nothing could be checked |
| `rdm collect [FILES…]` | extract `RDOC name … ENDRDOC` snippets from source files into YAML → stdout |
| `rdm translate FORMAT IN OUT` | convert test-runner XML (`auto`, `gtest`, `qttest`, `xunit`) into a YAML data file |
| `rdm hooks [DEST] [--with-issue-hooks]` | install the design-gate hooks (pre-commit, and pre-merge-commit for merges) into `DEST` or `.git/hooks`; the issue-reference hooks only with the flag |

## Design controls & traceability — `rdm story …`

| Command | What it does |
|---|---|
| `new-input --context C --text T --traces-to UN[,UN…] [--test-file F]` | scaffold a traced design input: next free `DI-n`, frontmatter entry, failing stub test (in `F` if given), checklist; `--list` shows contexts / taken ids / user needs |
| `design-gate [--allure-results DIR]` | design docs + review present, complete, approved (committed); every user-need and design-input id declared once; the architecture's views drawn from the current workspace; warnings for DI↔tag mismatches; with results, also checks the user needs against them |
| `verify --allure-results DIR -o FILE [--unit-coverage REPORT]` | reconcile executed Allure results against declared design inputs → verification data for the matrix; with the unit tests' coverage report (Cobertura XML or LCOV), each component's unit lines run of those measured, beside the inputs, never inside them ([gates](gates.md)) |
| `release-gate --allure-results DIR` | hard gate: approved + every design input verified by a passing tagged test + every user need addressed + every risk evaluated, its risk controls verified and its residual acceptable ([risk register](risk.md)) |
| `dmr DOCS_DIR -o FILE` | generate device-master-record index data (id/title/path/revision per controlled document) from frontmatter |
| `evidence-bundle --allure-results DIR -o DIR [--unit-coverage REPORT]` | write the retained release evidence set: verification data, rendered matrix, Allure results, verification report, manifest; refuses a record with no matrix template; with a coverage report, carries it as `verify` does and keeps the report |
| `evidence-report --allure-results DIR -o FILE` | render the verification report (PDF): every run behind each design input, with its steps, labels, links and attachments |
| `mutation-probe --file F --find A --replace B --test T` | reviewer tool: break one line on purpose, run one test, report KILLED (caught) / SURVIVED (missed), always restore; never gates |
| `trace UN-nnn \| DI-n [--allure-results DIR]` | the traceability slice for one need or input (forward + backward); with results, each input's verification status |
| `persona --vv-plan F --persona-results DIR` | reconcile formative AI-persona usability runs against the user-need registry (never gates) |

Common flag: `--dhf DIR` (default `dhf/`).

## The architecture — `rdm c4 …`

| Command | What it does |
| --- | --- |
| `draw [--dhf DIR]` | export the architecture workspace (`<dhf>/c4/workspace.dsl`): its model to `c4/workspace.json`, each view drawn by Graphviz to `c4/views/<view>.svg`, all stamped with the workspace's hash (needs Java, Structurizr's CLI and Graphviz; the design gate fails on a stale one) |

## The record as a graph — `rdm graph …` (extra: `graph`)

| Command | What it does |
|---|---|
| `build [--allure-results DIR] [--checklist NAME\|FILE]… [-o FILE] [--store DIR] [--project NAME] [--infer] [--unit-coverage REPORT]` | project the record (and any checklists, as data) into RDF named graphs; sorted N-Quads to a file or stdout, and/or a rebuilt Oxigraph store; `--infer` adds what the vocabulary's rules derive, in a separate graph; `--unit-coverage` adds each component's unit lines run and measured (also on `query`, `validate` and `explorer-file`; an unreadable report exits 2) |
| `validate [--allure-results DIR] [--checklist NAME\|FILE]… [--shapes FILE]…` | check the graph against the SHACL gate shapes (plus your own); exit 1 on a violation |
| `query 'SPARQL' [--store DIR] [--format tsv\|csv\|json] [--infer]` | SELECT / ASK / CONSTRUCT over the store, or over an in-memory projection of `--dhf` |
| `explorer-file -o FILE [--store DIR] [--exclude CLASS]… [--endpoint URL]` | write the whole record as an AWS Graph Explorer graph file (*Load graph from file*); `--unit-coverage` applies to a fresh projection only and is refused with `--store` |
| `serve [--store DIR] [--bind HOST:PORT]` | read-only SPARQL 1.1 endpoint (union default graph, CORS) for AWS Graph Explorer and other SPARQL clients |
| `mcp [--allure-results DIR] [--checklist NAME\|FILE]…` | serve the record to agents as a read-only MCP server over stdio: `schema`, `query`, `trace`, `validate`, each from a fresh projection |

See [The record as a graph](graph.md).

## Planning

RDM ships no planning tooling: tasks and issues live in their own tools
([Plan vs. record](plan-vs-record.md)).
