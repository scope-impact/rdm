#!/usr/bin/env bash
#
# Build rdm.wasm (DI-87): RDM's command line as one WASI 0.3 component, composed
# from the core and its two providers:
#   rdm-core.wasm  the rdm command line (componentize-py), world core: imports record-state and c4
#   rdm-git.wasm   the record-state provider (Rust, gitoxide), world git
#   rdm-c4.wasm    the c4 provider (Rust, structurizrx), world draw
#   rdm-typst.wasm the typeset provider (Rust, Typst's crates), world typeset
#   rdm.wasm       rdm-core with both plugged in (wac): what scripts/rdm-wasm runs
# Everything it fetches lands in build/component/ (gitignored); nothing is installed.
# Every tool is pinned (RISK-TOOL-010).
set -euo pipefail

COMPONENTIZE_PY=0.25.1   # targets WASI 0.3.0
WASMTIME=49.0.2
WASM_TOOLS=1.261.0
WKG=0.16.1               # fetches the WASI WIT the package names, pinned by wkg.lock
WAC=0.12.0               # composes the components
PYTHON=3.12              # componentize-py bundles its own CPython; this only runs the tool

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/component"
mkdir -p "$WORK"
cd "$WORK"

if [ ! -x venv/bin/componentize-py ]; then
    uv venv -q -p "$PYTHON" venv
    uv pip install -q -p venv "componentize-py==$COMPONENTIZE_PY"
fi
if [ ! -x wasmtime ]; then
    curl -fsSL "https://github.com/bytecodealliance/wasmtime/releases/download/v$WASMTIME/wasmtime-v$WASMTIME-x86_64-linux.tar.xz" | tar xJ
    mv "wasmtime-v$WASMTIME-x86_64-linux/wasmtime" . && rm -r "wasmtime-v$WASMTIME-x86_64-linux"
fi
if [ ! -x wasm-tools ]; then
    curl -fsSL "https://github.com/bytecodealliance/wasm-tools/releases/download/v$WASM_TOOLS/wasm-tools-$WASM_TOOLS-x86_64-linux.tar.gz" | tar xz
    mv "wasm-tools-$WASM_TOOLS-x86_64-linux/wasm-tools" . && rm -r "wasm-tools-$WASM_TOOLS-x86_64-linux"
fi
if [ ! -x wkg ]; then
    curl -fsSL -o wkg "https://github.com/bytecodealliance/wasm-pkg-tools/releases/download/v$WKG/wkg-x86_64-unknown-linux-gnu"
    chmod +x wkg
fi
if [ ! -x wac ]; then
    curl -fsSL -o wac "https://github.com/bytecodealliance/wac/releases/download/v$WAC/wac-cli-x86_64-unknown-linux-musl"
    chmod +x wac
fi

# The WIT package (wit/) and its WASI dependencies, as wkg.lock pins them, into wit/deps (gitignored).
(cd "$ROOT" && "$WORK/wkg" fetch)

# RDM's runtime dependencies as pure Python, at the versions uv.lock pins. The compiled speed-ups of PyYAML
# and MarkupSafe are for the host; both fall back without them.
(cd "$ROOT" && uv export -q --no-hashes --all-extras --no-emit-project | sed 's/ ;.*//' | grep '==') > constraints.txt
rm -rf pkgs
uv pip install -q --target pkgs --python-version "$PYTHON" -c constraints.txt jinja2 pyyaml rdflib pyshacl \
    markdown-it-py mdit-py-plugins
find pkgs -name '*.so' -delete

# RDM's shipped files (templates, checklists, shapes, vocabulary): componentize-py bundles modules only, so they
# ship beside the component and scripts/rdm-wasm mounts them, read-only, where the bundled package finds them.
rm -rf rdm-data && (cd "$ROOT/rdm" && find . -type f ! -name '*.py' ! -path '*/__pycache__/*' -print0 \
    | xargs -0 -I{} install -D -m 644 {} "$WORK/rdm-data/{}")

venv/bin/componentize-py -d "$ROOT/wit" -w rdm:component/core componentize rdm.component.app \
    -p pkgs -p "$ROOT" -o rdm-core.wasm   # RDM second: /1/rdm, as rdm-wasm mounts
ls -l rdm-core.wasm

# The providers, then the command line composed with them: its imports plugged by their exports.
"$ROOT/providers/record-state/build.sh" > /dev/null
cp "$ROOT/providers/record-state/target/wasm32-wasip2/release/rdm_git.wasm" rdm-git.wasm
rustup target add wasm32-wasip2 > /dev/null
(cd "$ROOT/providers/c4" && cargo build -q --release --locked --lib --features component --target wasm32-wasip2)
cp "$ROOT/providers/c4/target/wasm32-wasip2/release/rdm_c4.wasm" rdm-c4.wasm
(cd "$ROOT/providers/typst" && cargo build -q --release --locked --lib --features component --target wasm32-wasip2)
cp "$ROOT/providers/typst/target/wasm32-wasip2/release/rdm_typst.wasm" rdm-typst.wasm
./wac plug rdm-core.wasm --plug rdm-git.wasm --plug rdm-c4.wasm --plug rdm-typst.wasm -o rdm.wasm
ls -l rdm-git.wasm rdm-c4.wasm rdm-typst.wasm rdm.wasm
echo "rdm.wasm imports and exports (from the component's own type):"
./wasm-tools component wit rdm.wasm | grep -E '^\s*(import|export) ' | sort
