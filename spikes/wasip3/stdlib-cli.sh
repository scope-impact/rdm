#!/usr/bin/env bash
#
# Spike: record the standard-library modules the rdm commands load natively, into
# stdlib-cli.txt, which app.py bundles instead of the whole standard library (README:
# "Why it is big"). Rerun when RDM changes: a module a command loads that is not listed
# fails in the component at run time (compare.sh would show it).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/sitecustomize.py" <<'PY'
import atexit, os, sys
atexit.register(lambda: open(os.environ["RDM_IMPORTS_OUT"], "a").write("\n".join(sys.modules) + "\n"))
PY
export PYTHONPATH="$TMP" RDM_IMPORTS_OUT="$TMP/seen"
cd "$ROOT"
for command in "story design-gate --dhf dhf" "story release-gate --dhf dhf --allure-results dhf/allure-results" \
    "story verify --dhf dhf --allure-results dhf/allure-results -o $TMP/v.yml" "story trace --dhf dhf DI-1" \
    "story dmr -o $TMP/d.md dhf/documents" "gap --list" "story new-input --list --dhf dhf" "c4 draw --help"; do
    # shellcheck disable=SC2086 - the commands are word lists
    uv run --quiet rdm $command > /dev/null 2>&1 || true
done
mkdir "$TMP/init" && (cd "$TMP/init" && git init -q && uv run --project "$ROOT" --quiet rdm init > /dev/null 2>&1)
uv run --quiet python -c '
import sys
seen = {line.strip() for line in open(sys.argv[1]) if line.strip()}
print("\n".join(sorted(m for m in seen if m.split(".")[0] in sys.stdlib_module_names)))' "$TMP/seen" > "$HERE/stdlib-cli.txt"
wc -l < "$HERE/stdlib-cli.txt"
