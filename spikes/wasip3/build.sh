#!/usr/bin/env bash
#
# Spike: build the rdm command line as WASI components and compose them (see README.md):
#   rdm-core.wasm   the rdm CLI (componentize-py), world rdm:component/core: imports c4
#   rdm-c4.wasm     structurizrx (Rust), world rdm:component/draw: exports c4
#   rdm.wasm        the two composed with wac: what rdm-wasm runs
#   rdm-test.wasm   RDM's tests under pytest, world rdm:component/tests
# Everything it fetches lands in .work/ (gitignored); nothing is installed.
#
set -euo pipefail

COMPONENTIZE_PY=0.25.1   # targets WASI 0.3.0
WASMTIME=49.0.2
WASM_TOOLS=1.261.0
WKG=0.16.1               # fetches the WASI WIT the package names, pinned by wkg.lock
WAC=0.12.0               # composes the components
PYTHON=3.12              # componentize-py bundles its own CPython; this only runs the tool

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work"
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

# The WIT package (../wit) and its WASI dependencies, as wkg.lock pins them, into wit/deps (gitignored).
(cd "$HERE" && "$WORK/wkg" fetch)
WIT="$HERE/wit"

# RDM's runtime dependencies, as pure Python: the compiled speed-ups of PyYAML
# and MarkupSafe are for the host, and both fall back without them.
rm -rf pkgs
uv pip install -q --target pkgs --python-version "$PYTHON" \
    "$(cd "$ROOT" && uv export -q --no-hashes --no-dev --no-emit-project | grep -i '^jinja2==')" \
    "$(cd "$ROOT" && uv export -q --no-hashes --no-dev --no-emit-project | grep -i '^pyyaml==')" \
    "$(cd "$ROOT" && uv export -q --no-hashes --no-dev --no-emit-project | grep -i '^markupsafe==')"
find pkgs -name '*.so' -delete

# RDM's package data (checklists, init/adopt templates): the build keeps modules only, so it ships beside the
# component and rdm-wasm mounts it where the bundled package thinks it lives.
rm -rf rdm-data && (cd "$ROOT/rdm" && find . -type f ! -name '*.py' ! -path '*/__pycache__/*' -print0 \
    | xargs -0 -I{} install -D -m 644 {} "$WORK/rdm-data/{}")

venv/bin/componentize-py -d "$WIT" -w rdm:component/core componentize app \
    -p "$HERE" -p pkgs -p "$ROOT" -o rdm-core.wasm
ls -l rdm-core.wasm

# rdm-c4, then the CLI composed with it: rdm-core's c4 import plugged by rdm-c4's export.
rustup target add wasm32-wasip2 > /dev/null
(cd "$HERE/c4-rs" && cargo build -q --release --locked --target wasm32-wasip2)
cp "$HERE/c4-rs/target/wasm32-wasip2/release/rdm_c4.wasm" rdm-c4.wasm
./wac plug rdm-core.wasm --plug rdm-c4.wasm -o rdm.wasm
ls -l rdm-c4.wasm rdm.wasm

# The test component: pytest and allure-pytest too (pytest/test_app.py, run with pytest/run.sh).
rm -rf pkgs-test
uv pip install -q --target pkgs-test --python-version "$PYTHON" \
    "$(cd "$ROOT" && uv export -q --no-hashes --all-extras --no-emit-project | grep -i '^pytest==')" \
    "$(cd "$ROOT" && uv export -q --no-hashes --all-extras --no-emit-project | grep -i '^allure-pytest==')" \
    "$(cd "$ROOT" && uv export -q --no-hashes --all-extras --no-emit-project | grep -i '^mock==')" \
    $(cd "$ROOT" && uv export -q --no-hashes --no-dev --no-emit-project | grep -iE '^(jinja2|pyyaml|markupsafe)==')
find pkgs-test -name '*.so' -delete
venv/bin/componentize-py -d "$WIT" -w rdm:component/tests componentize test_app \
    -p "$HERE/pytest" -p pkgs-test -p "$ROOT" -p "$HERE" -o rdm-test.wasm   # RDM third: /2/rdm, as run.sh mounts
ls -l rdm-test.wasm
echo "rdm.wasm imports and exports (from the component's own type):"
./wasm-tools component wit rdm.wasm | grep -E '^\s*(import|export) ' | sort
