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

## What a real port would take

- A design input and its tagged test: the gates run as a component, and their result matches the command line
  (`compare.sh` is that test's shape).
- A WIT interface for record state (finding 1), answered by the host from git, in place of `git_facts.py`.
- Package data read through `importlib.resources` (finding 2), a small change to `rdm/`.
- CI that builds the component with pinned tools and runs the comparison.
