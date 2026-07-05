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
| `rdm pull CONFIG` | legacy: pull data from the configured project-management tool |

## Design controls & traceability — `rdm story …` (extra: `story-audit`)

| Command | What it does |
|---|---|
| `new-input --context C --text T --traces-to UN[,UN…]` | scaffold a traced design input: next free `DI-n`, frontmatter entry, failing stub test, checklist; `--list` shows contexts / taken ids / user needs |
| `design-gate` | design docs + review present, complete, approved (committed); warnings for DI↔tag mismatches |
| `verify --allure-results DIR -o FILE` | reconcile executed Allure results against declared design inputs → verification data for the matrix |
| `faithfulness [--stale] [--replay]` | every design input has a current, independent verdict (hash-pinned; test edits go `stale`); `--stale` lists only the worklist, `--replay` re-executes recorded killing probes and fails on survivors |
| `release-gate --allure-results DIR` | hard gate: approved + all inputs verified + all faithful + every user need addressed |
| `verdict DI-n --verdict V --reviewer R --rationale …` | record a faithfulness verdict (`faithful`/`partial`/`unfaithful`/`weak`; `--uncovered` for partial; `--probe` JSON per executed mutation, repeatable; `--hash-scope module\|function`, default module) |
| `dmr DOCS_DIR -o FILE` | generate device-master-record index data (id/title/path/revision per controlled document) from frontmatter |
| `evidence-bundle --allure-results DIR -o DIR` | write the retained release evidence set: verification data, rendered matrix, verdicts, manifest |
| `mutation-probe --file F --find A --replace B --test T` | prove a test catches a defect: apply a one-line mutation, run the test, report KILLED/SURVIVED, always restore |
| `trace UN-nnn \| DI-n` | the traceability slice for one need or input (forward + backward) |
| `audit [REPO]` | repo-wide traceability report + score; DHF-aware (design-input tag coverage) |
| `persona --vv-plan F --persona-results DIR` | reconcile formative AI-persona usability runs against the user-need registry (never gates) |

Common flags: `--dhf DIR` (default `dhf/`), `--faithfulness DIR` (default
`<dhf>/faithfulness`).

## Planning layer (optional, non-record)

| Command | What it does |
|---|---|
| `rdm story sync BACKLOG_DIR -o DB` | sync Backlog.md → DuckDB analytics (`--migrate-only` for schema only) |
| `rdm story backlog-validate [DIR]` | validate Backlog.md files (`-f` single file, `--strict`, `--verbose`) |
| `rdm story check-ids [FILES…]` | duplicate requirement-ID detection (**deprecated** legacy YAML path — prints a notice; functional, exit codes unchanged) |
| `rdm story validate` | validate legacy requirements YAML against the schema (**deprecated** — new projects use the DHF + gates) |
| `rdm pm sync` | bidirectional GitHub sync: tasks → issues, PRs → DuckDB (extra: `github`) |

Planning outputs are stamped as derived data — never cite them as evidence
([Plan vs. record](plan-vs-record.md)).
