#!/usr/bin/env bash
#
# Spike: `rdm c4 draw` through rdm.wasm (rdm-core composed with rdm-c4; ../build.sh),
# on a copy of the example project, checked by RDM natively: every drawn file is
# from the current workspace (the design gate's freshness check), and RDM reads
# the same model as from the Structurizr export the example commits.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
COPY=$(mktemp -d)
trap 'rm -rf "$COPY"' EXIT
cp -r "$ROOT/examples/github-document-control/dhf" "$COPY/dhf"
cp "$COPY/dhf/c4/workspace.json" "$COPY/structurizr.json"
(cd "$COPY" && "$HERE/../rdm-wasm" c4 draw --dhf dhf)
cd "$ROOT" && uv run --quiet python - "$COPY" <<'PY'
import shutil, sys, tempfile
from pathlib import Path
from rdm.architecture.model import read_model, stale
copy = Path(sys.argv[1])
same = not stale(copy / "dhf")
print("drawn files from the current workspace:", same)
with tempfile.TemporaryDirectory() as tmp:
    (Path(tmp) / "c4").mkdir()
    shutil.copy(copy / "structurizr.json", Path(tmp) / "c4" / "workspace.json")
    theirs, ours = read_model(Path(tmp)), read_model(copy / "dhf")
for field in ("elements", "relationships", "views"):
    a, b = ({repr(x) for x in (v.values() if isinstance(v, dict) else v)} for v in (getattr(theirs, field), getattr(ours, field)))
    same &= a == b
    print(f"{field}: Structurizr {len(a)}, rdm-c4 {len(b)}, identical: {a == b}")
sys.exit(0 if same else 1)
PY
