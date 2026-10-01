# CLI reference

`rdm --version` · `rdm <command> --help` for full flag details.

## Core

| Command | What it does |
|---|---|
| `rdm init [-o DIR]` | scaffold a **new** documentation project (templates, Makefile, render config; default `-o dhf`) |
| `rdm adopt [TARGET]` | bring an **existing** repository under design controls: DHF skeleton, runbook, pre-commit gate, session bootstrap, CI workflow — skips (never overwrites) existing files |
| `rdm render TEMPLATE CONFIG [DATA…]` | render a Jinja2 Markdown template with the data files (each file's stem becomes a template variable) → stdout |
| `rdm gap [-l] [-c] [-v] CHECKLIST [FILES…]` | audit documents for required `[[KEY]]` references; `-l` list built-ins, `-c` coverage table, `-v` name missing items; exit 0 covered / 3 gaps |
| `rdm collect [FILES…]` | extract `RDOC name … ENDRDOC` snippets from source files into YAML → stdout |
| `rdm translate FORMAT IN OUT` | convert test-runner XML (`auto`, `gtest`, `qttest`, `xunit`) into a YAML data file |
| `rdm hooks [DEST] [--with-issue-hooks]` | install the design-gate pre-commit hook into `DEST` or `.git/hooks`; the issue-reference hooks only with the flag |

## Design controls & traceability — `rdm story …`

| Command | What it does |
|---|---|
| `new-input --context C --text T --traces-to UN[,UN…]` | scaffold a traced design input: next free `DI-n`, frontmatter entry, failing stub test, checklist; `--list` shows contexts / taken ids / user needs |
| `design-gate` | design docs + review present, complete, approved (committed); every user-need and design-input id declared once; warnings for DI↔tag mismatches |
| `verify --allure-results DIR -o FILE` | reconcile executed Allure results against declared design inputs → verification data for the matrix |
| `release-gate --allure-results DIR` | hard gate: approved + every design input verified by a passing tagged test + every user need addressed + every risk evaluated, controlled and acceptable ([risk register](risk.md)) |
| `dmr DOCS_DIR -o FILE` | generate device-master-record index data (id/title/path/revision per controlled document) from frontmatter |
| `evidence-bundle --allure-results DIR -o DIR` | write the retained release evidence set: verification data, rendered matrix, manifest |
| `mutation-probe --file F --find A --replace B --test T` | reviewer tool: break one line on purpose, run one test, report KILLED (caught) / SURVIVED (missed), always restore; never gates |
| `trace UN-nnn \| DI-n` | the traceability slice for one need or input (forward + backward) |
| `persona --vv-plan F --persona-results DIR` | reconcile formative AI-persona usability runs against the user-need registry (never gates) |

Common flag: `--dhf DIR` (default `dhf/`).

## The record as a graph — `rdm graph …` (extra: `graph`)

| Command | What it does |
|---|---|
| `build [--allure-results DIR] [--checklist NAME\|FILE]… [-o FILE] [--store DIR] [--project NAME]` | project the record (and any checklists, as data) into RDF named graphs; sorted N-Quads to a file or stdout, and/or a rebuilt Oxigraph store |
| `validate [--allure-results DIR] [--checklist NAME\|FILE]… [--shapes FILE]…` | check the graph against the SHACL gate shapes (plus your own); exit 1 on a violation |
| `query 'SPARQL' [--store DIR] [--format tsv\|csv\|json]` | SELECT / ASK / CONSTRUCT over the store, or over an in-memory projection of `--dhf` |
| `explorer-file -o FILE [--store DIR] [--exclude CLASS]… [--endpoint URL]` | write the whole record as an AWS Graph Explorer graph file (*Load graph from file*) |
| `serve [--store DIR] [--bind HOST:PORT]` | SPARQL 1.1 endpoint (union default graph, CORS) for AWS Graph Explorer and other SPARQL clients |
| `mcp [--allure-results DIR] [--checklist NAME\|FILE]…` | serve the record to agents as a read-only MCP server over stdio: `schema`, `query`, `trace`, `validate`, each from a fresh projection |

See [The record as a graph](graph.md).

## Planning

RDM ships no planning tooling: tasks and issues live in their own tools
([Plan vs. record](plan-vs-record.md)).
