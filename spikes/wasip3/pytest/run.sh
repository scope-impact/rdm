#!/usr/bin/env bash
#
# Spike: run RDM's tests inside the test component, Allure results to $ALLURE
# (default .work/allure-wasm). Build first: ./build.sh (one directory up).
#   spikes/wasip3/pytest/run.sh tests/acceptance/test_record.py ...
#
# What the host gives the component, besides the repository at /: RDM's
# package data (/2/rdm, copied by build.sh), a scratch /tmp, a /dev/null (pytest's logging opens
# it), the Allure directory, who runs it (USER) and the repository's commit
# and worktree state (git-snapshot.sh): the plugin labels runs with both.
# No network.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
WORK="$HERE/../.work"
ALLURE="${ALLURE:-$WORK/allure-wasm}"
RUN=$(mktemp -d -p "$WORK" run.XXXXXX)
mkdir -p "$RUN/tmp" "$RUN/dev" "$ALLURE" && : > "$RUN/dev/null"
cd "$ROOT"
"$HERE/../git-snapshot.sh" "$RUN/tmp/git-snapshot"
args=()   # paths in the repository are under / in the component
for arg in "$@"; do [ -e "$arg" ] && args+=("/${arg#/}") || args+=("$arg"); done
"$WORK/wasmtime" run -S tcp=n,udp=n,allow-ip-name-lookup=n \
    --dir "$ROOT::/" --dir "$WORK/rdm-data::/2/rdm" --dir "$RUN/tmp::/tmp" --dir "$RUN/dev::/dev" \
    --dir "$ALLURE::/allure" --env TMPDIR=/tmp --env HOME=/tmp --env USER="${USER:-$(id -un)}" --env RDM_GIT_SNAPSHOT=/tmp/git-snapshot \
    "$WORK/rdm-test.wasm" --basetemp=/tmp/pytest --alluredir=/allure "${args[@]}"
