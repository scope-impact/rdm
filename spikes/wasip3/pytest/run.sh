#!/usr/bin/env bash
#
# Spike: run RDM's tests inside the test component, Allure results to $ALLURE
# (default .work/allure-wasm). Build first: ./build.sh (one directory up).
#   spikes/wasip3/pytest/run.sh tests/acceptance/test_record.py ...
#
# What the host gives the component, besides the repository at /: RDM's
# package data (/2/rdm, copied by build.sh), a scratch /tmp, a /dev/null (pytest's logging opens
# it), the Allure directory and who runs it (USER; the plugin records it). The
# run's commit and worktree labels come from rdm-git, composed in by build.sh.
# No network.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
WORK="$HERE/../.work"
ALLURE="${ALLURE:-$WORK/allure-wasm}"
RUN=$(mktemp -d -p "$WORK" run.XXXXXX)
mkdir -p "$RUN/tmp" "$RUN/dev" "$ALLURE" && : > "$RUN/dev/null"
cd "$ROOT"
REPO="/$(basename "$ROOT")"
args=()   # paths in the repository are under $REPO in the component
for arg in "$@"; do [ -e "$arg" ] && args+=("$REPO/${arg#/}") || args+=("$arg"); done
"$WORK/wasmtime" run -S tcp=n,udp=n,allow-ip-name-lookup=n \
    --dir "$ROOT::$REPO" --env RDM_REPO="$REPO" --dir "$WORK/rdm-data::/2/rdm" --dir "$RUN/tmp::/tmp" --dir "$RUN/dev::/dev" \
    --dir "$ALLURE::/allure" --env TMPDIR=/tmp --env HOME=/tmp --env USER="${USER:-$(id -un)}" \
    "$WORK/rdm-tests.wasm" --basetemp=/tmp/pytest --alluredir=/allure "${args[@]}"
