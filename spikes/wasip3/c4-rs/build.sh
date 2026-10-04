#!/usr/bin/env bash
# Spike: build rdm-c4 for wasm32-wasip2, run it on RDM's workspace with no network,
# and check RDM reads the same model from its export as from Structurizr's (../README.md).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
WASMTIME="$HERE/../.work/wasmtime"   # from ../build.sh
OUT="$HERE/target/out"
cd "$HERE"
rustup target add wasm32-wasip2 > /dev/null
cargo build -q --release --target wasm32-wasip2
ls -l target/wasm32-wasip2/release/rdm-c4.wasm
rm -rf "$OUT" && mkdir -p "$OUT/c4"
"$WASMTIME" run -S tcp=n,udp=n,allow-ip-name-lookup=n --dir "$ROOT/dhf/c4::/c4" --dir "$OUT/c4::/out" \
    target/wasm32-wasip2/release/rdm-c4.wasm /c4/workspace.dsl /out
cd "$ROOT" && uv run python - "$OUT" <<'PY'
import sys
from pathlib import Path
from rdm.architecture.model import read_model
ours, theirs = read_model(Path("dhf")), read_model(Path(sys.argv[1]))
same = True
for field in ("elements", "relationships", "views"):
    a, b = getattr(ours, field), getattr(theirs, field)
    a, b = ({repr(x) for x in (v.values() if isinstance(v, dict) else v)} for v in (a, b))
    same &= a == b
    print(f"{field}: Structurizr {len(a)}, structurizrx {len(b)}, identical: {a == b}")
sys.exit(0 if same else 1)
PY
