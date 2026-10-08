# Spike: the rdm command line as a WASI 0.3 component

**Question.** Can the `rdm` command line run as a [WASI 0.3](https://wasi.dev/releases/wasi-p3) Component Model
component, sandboxed, with the same results as the native one?

**Answer.** Yes, for everything that needs no other program, and unchanged: no line of `rdm/` was edited.
`rdm-wasm` is the command: it needs Wasmtime on the host, nothing else (no Python, no RDM, no git). Every command
compared gives the same exit code, output and files as the native run, in a clean repository and in one with an
uncommitted edit (table below). What needs another program (Structurizr, Graphviz, Typst, pytest) or the graph extra
says so with RDM's own message.

This is a spike: throwaway code outside the product. It is not under design control, and nothing in RDM depends on
it. Making it part of RDM starts, as any change does, with a design review entry and a design input
(`dhf/AGENT_WORKFLOW.md`).

## Run it

```bash
spikes/wasip3/build.sh                    # pinned tools into .work/; builds and composes the components (~30 s)
spikes/wasip3/rdm-wasm story design-gate --dhf dhf     # from a repository's root, like rdm
spikes/wasip3/compare.sh [REPO]           # each command natively and through rdm-wasm, compared
spikes/wasip3/pytest/run.sh FILES...      # RDM's tests inside a component, Allure results out
spikes/wasip3/c4-rs/check.sh              # rdm c4 draw on the example project, checked by RDM natively
```

| Tool | Version | Why that one |
|---|---|---|
| componentize-py | 0.25.1 (2026-09-11) | Its WIT files are `wasi:*@0.3.0` |
| Wasmtime | 49.0.2 (2026-10-02) | WASI 0.3 on by default since 46 |
| wasm-tools | 1.261.0 | Prints the component's imports |
| wkg | 0.16.1 | Fetches the WASI WIT the package names; `wkg.lock` pins it |
| wac | 0.12.0 | Composes the components |
| wit-bindgen (Rust) | 0.62.0 | rdm-c4's export |
| WASI spec | 0.3.0 (spec is at 0.3.1; 0.3.2 due 2026-10-13) | What componentize-py targets |

`rdm-wasm` is the whole interface between host and component:

```
host                                         components (rdm.wasm, 35 MB: rdm-core + rdm-git + rdm-c4)
-------------------------------------------  -----------------------------------------
the current directory (a repository's root)  /          read and written, as rdm does; rdm-git
                                                        reads .git there with gitoxide
.work/rdm-data (RDM's package data)          /2/rdm     where the bundled package looks
network                                      none (-S tcp=n,udp=n,allow-ip-name-lookup=n)
```

## Results (`compare.sh`)

```
command                            native  wasm  native_s  wasm_s  same
story design-gate --dhf dhf             0     0      0.34    4.22  yes   (first run compiles; cached after)
story release-gate --dhf dhf ...        0     0      0.24    0.93  yes
story verify ... -o OUT/verification    0     0      0.17    0.49  yes   (file identical)
story trace --dhf dhf DI-1              0     0      0.15    0.43  yes
story dmr -o OUT/dmr.md dhf/documents   0     0      0.13    0.34  yes   (file identical)
gap --list                              0     0      0.13    0.29  yes
story new-input --list --dhf dhf        0     0      0.25    0.61  yes
story design-gate --dhf no-such-dhf     2     2      0.13    0.32  yes   (a failing gate fails the same)
story design-gate --no-such-option      2     2      0.12    0.32  yes   (usage error keeps code 2)
init (empty repository)                 0     0         -       -  yes   (50 files, identical)
```

On a clone with one design document edited and one added, all ten match too: the design gate fails (1) and the
release gate and verify refuse (2), natively and in the component alike.

`graph ...`, `story evidence-report` and `story mutation-probe` stop with RDM's own message (the graph extra
missing, a tool not installed); they need programs or native code WASI cannot run. `c4 draw` runs: see below.

## Why it is big

`wasm-tools objdump`, after `stdlib-cli.txt` (below):

```
rdm.wasm 34.7 MB (12.4 MB gzipped)
  rdm-core 30.4 MB
     5.9 MB  CPython, compiled to wasm (code)
    ~23.8 MB memory snapshot (data): 12.3 MB of it is a bare Python after start-up (hello world is 19.7 MB)
              and the rest what app.py imports: RDM, Jinja2, PyYAML, MarkupSafe, the standard library it uses
     0.7 MB  componentize-py runtime, libc, glue
  rdm-git   3.2 MB  gitoxide (Rust)
  rdm-c4    1.1 MB  structurizrx (Rust)
```

componentize-py ships no Python source: it runs CPython at build time, imports what the app imports, and saves the
interpreter's whole memory into the component. Every module imported up front is size. Importing the whole standard
library (to catch lazy imports, finding 4) made the CLI 46 MB; `stdlib-cli.sh` records the 180 standard-library
modules RDM's commands load natively into `stdlib-cli.txt`, and `app.py` bundles those and every codec: 46.2 MB to
34.7 MB, with `compare.sh` and `c4-rs/check.sh` unchanged. The cost: a command that loads an unlisted module fails
at run time, so the list is rerun when RDM changes. The test component still bundles all of it.

Smaller still would mean less Python: the Rust components are a tenth the size because they ship compiled code only.

## The WIT package and the composed CLI

`wit/` is one package, `rdm:component@0.1.0`, laid out as wasmCloud's docs suggest. Its WASI dependencies are
fetched by `wkg` from the `wasi.dev` registry into `wit/deps/` (gitignored), with `wkg.lock` (committed) pinning
each by digest.

```
wit/imports.wit        world imports    what any RDM component may be given: WASI 0.3, no sockets
wit/c4.wit             interface c4     draw(dsl) -> result<drawing, string>
wit/record-state.wit   interface record-state   the record's git state: head(), files(paths)
wit/world.wit          world core       the CLI: imports + record-state + c4, exports wasi:cli/run
                       world tests      RDM's tests: imports + record-state, exports wasi:cli/run
                       world draw       rdm-c4: exports c4
                       world git        rdm-git: exports record-state
```

`build.sh` builds the helpers and plugs them into the CLI and the test runner:

```
rdm-git.wasm  (Rust, gitoxide, world git, 3.2 MB)     --export record-state--+
                                                                             +--> rdm.wasm (35 MB) --> wasi:cli/run
rdm-c4.wasm   (Rust, structurizrx, world draw, 1.1 MB) --export c4-----------+    (rdm-wasm)
rdm-core.wasm (componentize-py, world core)  imports record-state and c4 ----+

rdm-git.wasm --export record-state--> rdm-test.wasm (world tests) --> rdm-tests.wasm (pytest/run.sh)
```

No `rdm:component` import is left after `wac plug`: the host provides WASI only.

`c4_component.py` puts the `c4` import where RDM's `draw()` runs Structurizr's CLI and Graphviz. Everything else
is RDM's own code: the stamps, the files written, the removal of images of views the workspace no longer has. So
`rdm c4 draw` runs in the sandbox with no Java and no Graphviz. `c4-rs/check.sh` draws the example project's
workspace and checks the result with RDM natively:

```
rdm c4 draw (example project)   Drew 5 view(s), 0.3 s
drawn files from the current workspace (the design gate's check)   true
model RDM reads, against the Structurizr export the example commits
  elements 20 = 20   relationships 25 = 25   views 5 = 5           identical
```

On RDM's own workspace it stops: `Structurizr exported no DOT for view D_specification_commit`. structurizrx does
not draw dynamic views yet, and RDM refuses rather than writing a partial set: nothing is written. (The message
still names Structurizr; it is RDM's.)

Running a WASI 0.2 component (rdm-c4, from Rust's `wasm32-wasip2`) composed with a WASI 0.3 one (rdm-core) in one
Wasmtime works. Both see the host's preopened directories, so rdm-c4 reads the workspace at the path rdm-core gives.

What wasmCloud's guidance changed, and what it cost:
- **Package layout and `wkg`.** One file per interface, an `imports` world, WASI from a registry pinned by
  `wkg.lock`. This replaced copying componentize-py's vendored WIT.
- **A shared `types` interface is an import.** wasmCloud's docs suggest a `types.wit`. An interface holding only
  type aliases still becomes an import of every component that `use`s it, and nothing provides it: not Wasmtime,
  not `wac`. So `repo-path` is defined in each interface that needs it.
- **Name clash.** A function and a record may not share a name in one interface (`head`), which `wkg fetch` reported.
  The record became `head-state`.
- **Capabilities.** The core's world asks for no sockets, but the bundled CPython still imports WASI 0.2 sockets
  (finding 3). WIT has no read-only filesystem import yet, so read-only is still the host's to enforce.

## What the spike found

1. **Git is `record-state`, from gitoxide.** Outside the graph extra, RDM asks git few questions: per controlled
   document, `status --porcelain --ignored` and `ls-files -v` (on a file or a directory); for a run, `rev-parse HEAD`,
   `remote get-url origin` and whether the tree is dirty; during a merge, `MERGE_HEAD` and where a commit holds staged
   content. `rdm-git` (gitoxide, `git-rs/`) answers `head()` and `files(paths)` from the mounted `.git`, reading the
   status once per run. `git_component.py` turns each answer back into git's words, through `git_snapshot.py`, so
   RDM's code is unchanged. The one question it cannot answer (the merge's `log --find-object`) gets `None`, as when
   git is missing, and the gate then says "approval could not be verified", never "approved": it fails safe.
   `RDM_GIT_TRACE=1` prints each question and answer. Run with no git and no Python on the `PATH`, `rdm-wasm`
   passes this repository's gate (0.9 s) and fails a clone with an edited design review, as native RDM does.
   Before this, the spike took a snapshot with git on the host (`git-snapshot.sh`); a first version of it matched
   only exact file paths and failed the architecture views, which the gate asks about as a directory: `compare.sh`
   caught it.
2. **Package data is not bundled.** componentize-py snapshots modules at build time under `/<n>/` (one per `-p`) and
   keeps no data files. RDM reads its checklists and its `init`/`adopt` templates from beside its code (`__file__`),
   so `build.sh` copies them to `.work/rdm-data`, shipped beside the component, and `rdm-wasm` mounts them at
   `/2/rdm`. A real port would read data through `importlib.resources` from something bundled.
3. **WASI 0.2 sockets are still imported.** The bundled CPython's wasi-libc speaks WASI 0.2.9, so the component
   imports both 0.2.9 (`wasi:io`, `wasi:sockets/*` and others) and 0.3.0. The world in `wit/imports.wit` drops
   sockets from RDM's side only. "No network" is therefore the host's choice and cannot be seen in the component's
   type. Removing it from the type would need a wasi-libc on 0.3, or composing in a component that refuses sockets.
4. **Lazy imports.** The build bundles only what is imported while it runs the app, but RDM imports its subcommands
   lazily and Python loads codecs, `tomllib` and `importlib.resources`' adapters on first use (`init` failed on the
   last). `bundle.py` imports the whole standard library and every submodule of the bundled packages up front: 28 MB
   became 42 MB.
5. **What stays native.** The graph extra (pyoxigraph has no wasm build), and everything that starts a program: `c4
   draw` (Structurizr, Graphviz), the PDF report (Typst), `mutation-probe` (pytest). WASI 0.3.1 has no way to start
   a program. `rdm-wasm --skipped` lists the modules that could not be bundled.
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
  worktree state, which the plugin labels each result with: from rdm-git, plugged into `rdm-tests.wasm`.
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
| Structurizr | **Wired in: `rdm c4 draw` runs in the component** (above). [structurizrx](https://github.com/pomali/structurizrx) (Apache-2.0), a Rust Structurizr, pinned at 46a5daa: `c4-rs/` | Separate Rust component, 1.2 MB, no patches | **Built and run here.** It parsed RDM's `dhf/c4/workspace.dsl` in 0.4 s with the network off. RDM's own reader (`read_model`) gets an **identical** model from its export: 62 elements, 122 relationships and 13 views. That needed one addition: the DSL identifiers (`structurizr.dsl.identifier`), which its export leaves out. They come from its parser's identifier register. Drawing uses its own layout engine, no Graphviz and no Java. 10 of 13 views were drawn: dynamic views are not rendered yet. Bounded-context groups are not drawn, long descriptions are cut short with an ellipsis, and DOT came out for one view only. The DSL is not deprecated, but the Java Structurizr CLI that `rdm c4 draw` runs today is [end of life](https://docs.structurizr.com/eol). Its replacement [`export`](https://docs.structurizr.com/export) does not list DOT. |
| Native wheels | None needed | n/a | PyYAML and MarkupSafe fall back to pure Python. [dicej/wasi-wheels](https://github.com/dicej/wasi-wheels) is unmaintained, and the WASIX index targets Wasmer, not WASI. |

Order to try them in:
1. ~~gitoxide behind `record-state`~~: done (finding 1).
2. rdflib/pyshacl for the graph, if its speed is acceptable.
3. Typst and Pandoc as sibling components the host calls.

Packaging and composing: [wkg](https://github.com/bytecodealliance/wasm-pkg-tools) (OCI and registries) and
[wac](https://github.com/bytecodealliance/wac).

## What a real port would take

- A design input and its tagged test: the gates run as a component, and their result matches the command line
  (`compare.sh` is that test's shape).
- `record-state` and `c4` called by RDM's code, in place of `git()` and the Structurizr CLI, rather than swapped in.
- Package data read through `importlib.resources` (finding 2), a small change to `rdm/`.
- CI that builds the component with pinned tools and runs the comparison.
