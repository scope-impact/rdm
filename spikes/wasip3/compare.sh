#!/usr/bin/env bash
#
# Spike: run each command natively and as the component; compare exit codes,
# output and written files. Run from anywhere after build.sh.
#
# The native run is also the one that records the git answers the component
# replays (git_facts.py), so both see the same history. The component sees the
# repository at / and its output directory at /out, and has no network: the
# host turns off the sockets the bundled CPython still imports (README.md).
# RDM's package data (checklists, templates) is not in the component: the
# build keeps only the modules, under /2/rdm (the third -p of build.sh), so
# the host mounts the package there.
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work"
CMP="$WORK/cmp"

COMMANDS=(
    "story design-gate --dhf dhf"
    "story release-gate --dhf dhf --allure-results dhf/allure-results"
    "story verify --dhf dhf --allure-results dhf/allure-results -o OUT/verification.yml"
    "story trace --dhf dhf DI-1"
    "story dmr -o OUT/dmr.md dhf/documents"
    "gap --list"
    "story design-gate --dhf no-such-dhf"
    "story design-gate --no-such-option"
)

cd "$ROOT"
rm -rf "$CMP"
status=0
printf '%-32s %6s %6s %9s %9s  %s\n' command native wasm native_s wasm_s same
for i in "${!COMMANDS[@]}"; do
    command="${COMMANDS[$i]}"
    native="$CMP/$i/native" wasm="$CMP/$i/wasm"
    mkdir -p "$native" "$wasm"

    start=$(date +%s.%N)
    # shellcheck disable=SC2086 - the commands are word lists
    uv run python "$HERE/git_facts.py" record "$wasm/facts.json" -- ${command//OUT/$native} \
        > "$native/stdout" 2>&1
    native_code=$? native_s=$(echo "$(date +%s.%N) - $start" | bc)

    start=$(date +%s.%N)
    # shellcheck disable=SC2086
    "$WORK/wasmtime" run -S tcp=n,udp=n,allow-ip-name-lookup=n --dir "$ROOT::/" --dir "$wasm::/out" \
        --dir "$ROOT/rdm::/2/rdm" \
        --env RDM_GIT_FACTS=/out/facts.json "$WORK/rdm-core.wasm" ${command//OUT//out} \
        > "$wasm/stdout" 2>&1
    wasm_code=$? wasm_s=$(echo "$(date +%s.%N) - $start" | bc)

    # The same text but for where each run saw the repository and its output.
    sed -i -e "s|$native|OUT|g" -e "s|$ROOT/|/|g" -e "s|$ROOT|/|g" "$native/stdout"
    sed -i -e "s|/out|OUT|g" "$wasm/stdout"
    for file in "$native"/*; do
        name=$(basename "$file")
        [ "$name" = stdout ] || sed -i -e "s|$native|OUT|g" -e "s|$ROOT/|/|g" "$file" "$wasm/$name" 2>/dev/null
    done
    if [ "$native_code" = "$wasm_code" ] && diff -r -x facts.json -q "$native" "$wasm" > /dev/null; then
        same=yes
    else
        same=NO; status=1
    fi
    printf '%-32.32s %6s %6s %9.2f %9.2f  %s\n' "$command" "$native_code" "$wasm_code" "$native_s" "$wasm_s" "$same"
done
echo "Outputs: $CMP/<n>/{native,wasm}"
exit $status
