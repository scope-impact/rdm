# Spike: RDM's core as a WASI 0.3 component

**Question.** Can RDM's core, meaning the record reader, the gates and rendering, run as a
[WASI 0.3](https://wasi.dev/releases/wasi-p3) Component Model component, sandboxed, with the same results as the
command line?

**Answer.** Yes, unchanged: no line of `rdm/` was edited. Every command tried gives the same exit code, output and
files as the native run (table below). Three things have to come from the host: git answers, RDM's package data, and
turning sockets off.

This is a spike: throwaway code outside the product. It is not under design control, and nothing in RDM depends on
it. Making it part of RDM starts, as any change does, with a design review entry and a design input
(`dhf/AGENT_WORKFLOW.md`).

## Run it

```bash
spikes/wasip3/build.sh     # fetches pinned tools into .work/, builds .work/rdm-core.wasm (~10 s)
spikes/wasip3/compare.sh   # each command natively and as the component, compared
```

| Tool | Version | Why that one |
|---|---|---|
| componentize-py | 0.25.1 (2026-09-11) | Its WIT files are `wasi:*@0.3.0` |
| Wasmtime | 49.0.2 (2026-10-02) | WASI 0.3 on by default since 46 |
| wasm-tools | 1.261.0 | Prints the component's imports |
| WASI spec | 0.3.0 (spec is at 0.3.1; 0.3.2 due 2026-10-13) | What componentize-py targets |

## Results (`compare.sh`)

```
command                            native  wasm  native_s  wasm_s  same
story design-gate --dhf dhf             0     0      0.34    3.57  yes   (first run compiles; cached after)
story release-gate --dhf dhf ...        0     0      0.24    0.40  yes
story verify ... -o OUT/verification    0     0      0.18    0.35  yes   (file identical)
story trace --dhf dhf DI-1              0     0      0.15    0.32  yes
story dmr -o OUT/dmr.md dhf/documents   0     0      0.15    0.20  yes   (file identical)
gap --list                              0     0      0.16    0.18  yes
story design-gate --dhf no-such-dhf     2     2      0.14    0.17  yes   (a failing gate fails the same)
story design-gate --no-such-option      2     2      0.16    0.19  yes   (usage error keeps code 2)
```

The component is 28 MB.

## What the spike found

1. **Git is two questions.** The core asks git only through `rdm.kernel.git.git`, and across every command above it
   asked only `git ls-files -v -- <file>` and `git status --porcelain --ignored -- <file>`, both per controlled
   document. The host interface the core needs is therefore "the tracked and committed state of these paths". The
   spike stands it in with `git_facts.py`, which records the answers on the host and replays them in the component.
   An unanswered question gets `None`, as when git is missing. The gate then says "approval could not be verified"
   and never "approved": it fails safe.
2. **Package data is not bundled.** componentize-py snapshots modules at build time under `/<n>/` (one per `-p`) and
   keeps no data files. RDM reads its checklists and its `init`/`adopt` templates from beside its code (`__file__`), so
   `gap --list` printed nothing until the host mounted the package at `/2/rdm`. A real port would read data through
   `importlib.resources` from something bundled, or have the host supply it.
3. **WASI 0.2 sockets are still imported.** The bundled CPython's wasi-libc speaks WASI 0.2.9, so the component
   imports both 0.2.9 (`wasi:io`, `wasi:sockets/*` and others) and 0.3.0. The narrower world in `wit/gate.wit` drops
   sockets from RDM's side only. "No network" is therefore the host's choice (`-S tcp=n,udp=n,allow-ip-name-lookup=n`)
   and cannot be seen in the component's type. Removing it from the type would need a wasi-libc on 0.3, or composing in
   a component that refuses sockets.
4. **Lazy imports and codecs.** The build bundles only what is imported while it runs `app.py`, but RDM imports its
   subcommands lazily and Python loads codecs (`utf-8-sig`) lazily. `app.py` therefore imports every RDM module and
   those codecs up front. Without that, a subcommand or `release-gate` fails at run time.
5. **What stays native.** The graph extra (pyoxigraph has no wasm build), the pytest plugin and acceptance runs, and
   everything that starts a program: `c4 draw` (Structurizr, Graphviz), the PDF report (Typst), and `mutation-probe`.
   WASI 0.3.1 has no way to start a program. `app.py --skipped` lists the modules that could not be bundled.
6. **Exit codes survive.** `wasi:cli/exit.exit-with-code` (WASI 0.3.0) carries argparse's 2. `run` itself returns
   only success or failure.
7. **Native speed-ups dropped.** PyYAML and MarkupSafe ship compiled speed-ups that cannot be bundled. Both fall back
   to pure Python, with no difference in results. GitPython is not needed at all: the core never imports it.

## Allure: tests and their results inside WASI

RDM uses Allure in three ways. In the spike:

| Use | What it is | In WASI |
|---|---|---|
| Reading results (`verify`, `release-gate`, `trace`) | RDM's own JSON reader | **Same as the command line** (table above) |
| Writing results | pytest + allure-pytest + RDM's plugin, all pure Python | **Runs**: `pytest/` (`rdm-test.wasm`, 48 MB) |
| The HTML report | `allure-commandline` 2.46.1 (Java, `npx`) in `gates.yml` | Not tried; stays a CI step |

`pytest/run.sh <test files>` runs RDM's tests in the component, writing Allure results to `.work/allure-wasm`.
Run on the 21 acceptance tests that start no process and need no graph extra:

```
                      native     WASI
passed                    33       15
failed (WASI limits)       -        6   4 start a process; 1 uses capfd; 1 makes a link
skipped (no graph/typst)  -       12   in 4 files
Allure results            33       21
same status, labels, steps:  15 of the 15 that pass in both, commit and worktree labels included
verify, per design input:    14 the same; the 6 that differ are the 6 failures above
```

What it took, beyond the core component:
- **Lazy imports, again and more.** pytest reads `tomllib` and Jinja2 its `debug` module only when it needs them, and
  codecs (`unicode-escape`) and `importlib.resources._adapters` load on first use. `test_app.py` imports the whole
  standard library and every submodule of the bundled packages up front: 48 MB.
- **No file descriptor duplication.** pytest's default capture and its faulthandler plugin `dup` descriptors, which WASI
  cannot. Hence `--capture=sys` and `-p no:faulthandler`. Tests using `capfd` fail.
- **What the host supplies.** `/tmp`, a `/dev/null` (pytest's logging opens it), `--basetemp` (pytest's default checks
  `os.getuid`, which WASI lacks), `USER` (the plugin records who ran the tests), and the repository's commit and
  worktree state (`git_facts.py snapshot`), which the plugin labels each result with.
- **Entry points are not bundled.** The Allure plugin is named with `-p allure_pytest.plugin`.

What stays on the host: tests that start a process (10 of 39 acceptance files, plus 4 tests here), the graph extra's
tests, the PDF report's test, and the Allure HTML report.

## Components to build on (checked 2026-10-04)

For each thing that stays native (finding 5), an existing wasm build that could replace it. "Probed" means a small
throwaway build ran under Wasmtime 49 on **WASI 0.2**, not 0.3. Running a 0.2 helper beside this 0.3 component is
untested.

| Need | Best candidate | Kind | Status |
|---|---|---|---|
| Git reads (finding 1) | [gitoxide](https://github.com/GitoxideLabs/gitoxide) 0.88 (Rust), as its own wasip2 component: `git-rs/` | Separate Rust component, 3.2 MB | **Built and run here.** On a clone of RDM with one edited and one new file, HEAD, log, tags and the gate's two questions per file (tracked? changed?) matched `git`, with the network off, in 0.8 s. Two crates gitoxide uses don't support WASI, so `git-rs/build.sh` replaces their WASI part: `memmap2` (maps files into memory; on WASI it now reads them into memory) and `filetime` (file times; on WASI it panicked and now reads them through `std`). In-Python alternative: [dulwich](https://pypi.org/project/dulwich/) 1.2.17, probed with an `mmap` stub. |
| RDF, SPARQL, SHACL (graph extra) | [rdflib](https://pypi.org/project/rdflib/) 7.6 + pyshacl 0.40 + owlrl, all pure Python | Bundled | Probed: parse, SELECT, SHACL. Plugins load lazily, so import them up front (as in finding 4). Upgrade path: [Oxigraph](https://github.com/oxigraph/oxigraph)'s Rust core built for wasip2 with no RocksDB, wrapped in WIT (probed: 4.4 MB, query correct). |
| PDF report | [typst](https://github.com/typst/typst) 0.15.1 through [typst-as-lib](https://crates.io/crates/typst-as-lib), built for `wasm32-wasip2` | Separate Rust component | Probed: valid PDF in about 10 ms. 48 MB with fonts, smaller with fewer fonts. Needs a WIT wrapper such as `compile(source, files) -> pdf`. |
| Markdown to DOCX/Typst | Official [pandoc.wasm](https://github.com/pandoc/pandoc-wasm) (Pandoc 3.9) | wasip1 module; becomes a 0.2 command with the stock adapter | Probed: md → docx and md → typst. No Lua filters. For PDF, pair it with Typst above. |
| SPARQL endpoint, MCP | componentize-py's [`examples/http-p3`](https://github.com/bytecodealliance/componentize-py) (`wasi:http/service@0.3.0`) | Same toolchain | Not probed. Write the MCP JSON-RPC by hand: the `mcp` SDK needs pydantic-core, which is native. Alternative host: [Wassette](https://github.com/microsoft/wassette), which turns WIT exports into MCP tools (early). |
| Graphviz | [wasi-graphviz](https://github.com/pablormier/wasi-graphviz) 0.1.4 | wasip1 module with a C ABI | Rendered from the host. As a component its exports are lost, so it needs a WIT wrapper. Very new. |
| Structurizr | [structurizrx](https://github.com/pomali/structurizrx) (Apache-2.0), a Rust Structurizr, pinned at 46a5daa: `c4-rs/` | Separate Rust component, 1.2 MB, no patches | **Built and run here.** It parsed RDM's `dhf/c4/workspace.dsl` in 0.4 s with the network off. RDM's own reader (`read_model`) gets an **identical** model from its export: 62 elements, 122 relationships and 13 views. That needed one addition: the DSL identifiers (`structurizr.dsl.identifier`), which its export leaves out. They come from its parser's identifier register. Drawing uses its own layout engine, no Graphviz and no Java. 10 of 13 views were drawn: dynamic views are not rendered yet. Bounded-context groups are not drawn, long descriptions are cut short with an ellipsis, and DOT came out for one view only. The DSL is not deprecated, but the Java Structurizr CLI that `rdm c4 draw` runs today is [end of life](https://docs.structurizr.com/eol). Its replacement [`export`](https://docs.structurizr.com/export) does not list DOT. |
| Native wheels | None needed | n/a | PyYAML and MarkupSafe fall back to pure Python. [dicej/wasi-wheels](https://github.com/dicej/wasi-wheels) is unmaintained, and the WASIX index targets Wasmer, not WASI. |

Order to try them in:
1. gitoxide (`git-rs/`) in place of `git_facts.py`: a WIT interface `record-state(paths) -> list<file-state>` exported by a Rust component and imported by RDM's. Or dulwich, to stay in one Python component.
2. rdflib/pyshacl for the graph, if its speed is acceptable.
3. Typst and Pandoc as sibling components the host calls.

Packaging and composing: [wkg](https://github.com/bytecodealliance/wasm-pkg-tools) (OCI and registries) and
[wac](https://github.com/bytecodealliance/wac).

## What a real port would take

- A design input and its tagged test: the gates run as a component, and their result matches the command line
  (`compare.sh` is that test's shape).
- A WIT interface for record state (finding 1), answered by the host from git, in place of `git_facts.py`.
- Package data read through `importlib.resources` (finding 2), a small change to `rdm/`.
- CI that builds the component with pinned tools and runs the comparison.
