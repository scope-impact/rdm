# CLI reference

`rdm --version` · `rdm <command> --help` for full flag details.

## Core

| Command | What it does |
|---|---|
| `rdm init [-o DIR]` | scaffold a **new** documentation project (templates, Makefile, render config; default `-o dhf`) into a directory that does not exist yet, and print the next steps |
| `rdm adopt [TARGET]` | bring an **existing** repository under RDM: DHF skeleton and render config, CI workflow, `.gitignore` — skips (never overwrites) existing files |
| `rdm render TEMPLATE CONFIG [DATA…]` | render a Jinja2 Markdown template with the data files (each file's stem becomes a template variable) → stdout |
| `rdm gap [-l] [-c] [-v] CHECKLIST [FILES…]` | report the gaps: the clauses of a checklist no document references with `[[KEY]]`; `-l` list built-ins, `-c` coverage table (several checklists), `-v` name the gaps; exit 0 none / 3 gaps / 2 nothing could be checked |
| `rdm collect [FILES…]` | extract `RDOC name … ENDRDOC` snippets from source files into YAML → stdout |

## Traceability and audit — `rdm story …`

| Command | What it does |
|---|---|
| `verify --allure-results DIR -o FILE` | reconcile executed Allure results against the spec's requirements → verification data for the matrix, grouped by user need ([gates](../use/gates.md)) |
| `release-gate --allure-results DIR` | hard gate: the spec read whole (no unstamped scenario, no id twice) + every requirement verified by a passing tagged run + every user need addressed + every risk evaluated, its risk controls verified and its residual acceptable ([risk register](../use/risk-register.md)) |
| `dmr DOCS_DIR -o FILE` | generate device-master-record index data (id/title/path/revision per controlled document) from frontmatter |
| `evidence-bundle --allure-results DIR -o DIR` | write the retained release evidence set: verification data, rendered matrix, Allure results, verification report, manifest; refuses a record with no matrix template |
| `evidence-report --allure-results DIR -o FILE` | render the verification report (PDF): every run behind each requirement, with its steps, labels, links and attachments |

Common flag: `--dhf DIR` (default `dhf/`), on every command above except `dmr`.
The `rdm graph` commands that read the record (`build`, `query`, `validate`, `explorer-file`, `mcp`) take it too.

## The record as a graph — `rdm graph …` (extra: `graph`)

| Command | What it does |
|---|---|
| `build [--allure-results DIR] [--checklist NAME\|FILE]… [-o FILE] [--store DIR] [--project NAME]` | project the record (and any checklists, as data) into RDF named graphs; sorted N-Quads to a file or stdout, and/or a rebuilt Oxigraph store |
| `validate [--allure-results DIR] [--checklist NAME\|FILE]… [--shapes FILE]…` | check the graph against the SHACL gate shapes (plus your own); exit 1 on a violation |
| `query 'SPARQL' [--store DIR] [--format tsv\|csv\|json]` | SELECT / ASK / CONSTRUCT over the store, or over an in-memory projection of `--dhf` |
| `explorer-file -o FILE [--store DIR] [--exclude CLASS]… [--endpoint URL]` | write the whole record as an AWS Graph Explorer graph file (*Load graph from file*) |
| `serve [--store DIR] [--bind HOST:PORT]` | read-only SPARQL 1.1 endpoint (union default graph, CORS) for AWS Graph Explorer and other SPARQL clients |
| `mcp [--allure-results DIR] [--checklist NAME\|FILE]…` | serve the record to agents as a read-only MCP server over stdio: `schema`, `query`, `trace`, `validate`, each from a fresh projection |

See [The record as a graph](../use/graph.md).

## Planning

RDM ships no planning tooling: tasks and issues live in their own tools
([Plan vs. record](../about/plan-vs-record.md)).
