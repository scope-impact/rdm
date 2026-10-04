#!/usr/bin/env bash
#
# Spike: build RDM's core as a WASI 0.3 component (see README.md).
# Everything it fetches lands in .work/ (gitignored); nothing is installed.
#
set -euo pipefail

COMPONENTIZE_PY=0.25.1   # targets WASI 0.3.0
WASMTIME=49.0.2
WASM_TOOLS=1.261.0
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

# The WASI 0.3.0 WIT, as componentize-py's own release vendors it, beside our world.
if [ ! -d wasi-wit ]; then
    git clone -q --depth 1 --branch "v$COMPONENTIZE_PY" --filter=blob:none --sparse \
        https://github.com/bytecodealliance/componentize-py cpy
    git -C cpy sparse-checkout set wit/deps
    mv cpy/wit/deps wasi-wit && rm -rf cpy
fi
rm -rf wit && cp -r "$HERE/wit" wit && cp -r wasi-wit wit/deps

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

venv/bin/componentize-py -d wit -w rdm:spike/gate componentize app \
    -p "$HERE" -p pkgs -p "$ROOT" -o rdm-core.wasm
ls -l rdm-core.wasm

# The test component: pytest and allure-pytest too (pytest/test_app.py, run with pytest/run.sh).
rm -rf pkgs-test
uv pip install -q --target pkgs-test --python-version "$PYTHON" \
    "$(cd "$ROOT" && uv export -q --no-hashes --all-extras --no-emit-project | grep -i '^pytest==')" \
    "$(cd "$ROOT" && uv export -q --no-hashes --all-extras --no-emit-project | grep -i '^allure-pytest==')" \
    "$(cd "$ROOT" && uv export -q --no-hashes --all-extras --no-emit-project | grep -i '^mock==')" \
    $(cd "$ROOT" && uv export -q --no-hashes --no-dev --no-emit-project | grep -iE '^(jinja2|pyyaml|markupsafe)==')
find pkgs-test -name '*.so' -delete
venv/bin/componentize-py -d wit -w rdm:spike/gate componentize test_app \
    -p "$HERE/pytest" -p pkgs-test -p "$ROOT" -p "$HERE" -o rdm-test.wasm   # RDM third: /2/rdm, as run.sh mounts
ls -l rdm-test.wasm
echo "Imports (from the component's own type):"
./wasm-tools component wit rdm-core.wasm | grep -E '^\s*import' | sort
