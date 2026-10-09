#!/usr/bin/env bash
#
# DI-87: run each command natively (uv run rdm) and through the component
# (scripts/rdm-wasm); compare exit codes, output and written files. Exits 1 on
# any difference. Run after scripts/build-component.sh.
#
#   compare-component.sh            in this repository
#   compare-component.sh DIR        in another repository (e.g. a clone with edits)
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "${1:-$ROOT}" && pwd)"
CMP="$REPO/.rdm-wasm-compare"   # inside the repository: the component writes only there

COMMANDS=(
    "story design-gate --dhf dhf"
    "story release-gate --dhf dhf --allure-results dhf/allure-results"
    "story verify --dhf dhf --allure-results dhf/allure-results -o OUT/verification.yml"
    "story trace --dhf dhf DI-1"
    "story dmr -o OUT/dmr.md dhf/documents"
    "gap --list"
    "story new-input --list --dhf dhf"
    "graph build --dhf dhf --allure-results dhf/allure-results -o OUT/record.nq"
    "graph build --dhf dhf --infer -o OUT/inferred.nq"
    "graph validate --dhf dhf --allure-results dhf/allure-results"
    "graph validate --dhf dhf"
    "graph explorer-file --dhf dhf -o OUT/explorer.json"
    "story evidence-report --dhf dhf --allure-results dhf/allure-results -o OUT/report.pdf"
    "story design-gate --dhf no-such-dhf"
    "story design-gate --no-such-option"
)

cd "$REPO"
mkdir -p "$CMP"
status=0
printf '%-34s %6s %6s %8s %8s  %s\n' command native wasm native_s wasm_s same
for i in "${!COMMANDS[@]}"; do
    command="${COMMANDS[$i]}"
    native="$CMP/$i/native" wasm="$CMP/$i/wasm"
    rm -rf "${CMP:?}/$i" && mkdir -p "$native" "$wasm"

    start=$(date +%s.%N)
    # shellcheck disable=SC2086 - the commands are word lists
    uv run --project "$ROOT" --quiet rdm \
        ${command//OUT/.rdm-wasm-compare/$i/native} > "$native/stdout" 2>&1
    native_code=$? native_s=$(echo "$(date +%s.%N) - $start" | bc)

    start=$(date +%s.%N)
    # shellcheck disable=SC2086
    "$HERE/rdm-wasm" ${command//OUT/.rdm-wasm-compare/$i/wasm} > "$wasm/stdout" 2>&1
    wasm_code=$? wasm_s=$(echo "$(date +%s.%N) - $start" | bc)

    # The same text but for where each run saw the repository and its output.
    # Natively the repository is at $REPO; in the component, at /<its name>.
    sed -i -e "s|$(dirname "$REPO")/|/|g" -e "s|\.rdm-wasm-compare/$i/native|OUT|g" "$native"/*
    sed -i -e "s|\.rdm-wasm-compare/$i/wasm|OUT|g" "$wasm"/*
    sed -i -E 's/"timestamp": ?"[^"]*"/"timestamp":"T"/' "$native"/* "$wasm"/*   # explorer-file stamps the time
    if [ "$native_code" = "$wasm_code" ] && diff -r -q "$native" "$wasm" > /dev/null; then
        same=yes
    else
        same=NO; status=1
    fi
    printf '%-34.34s %6s %6s %8.2f %8.2f  %s\n' "$command" "$native_code" "$wasm_code" "$native_s" "$wasm_s" "$same"
done
# SPARQL through rdm graph query: SELECT in each format, ASK, CONSTRUCT.
QUERIES=(
    "SELECT ?i ?text WHERE { ?i a rdm:DesignInput ; rdm:text ?text } ORDER BY ?i"
    "SELECT ?need (COUNT(?i) AS ?n) WHERE { ?i rdm:tracesTo ?need } GROUP BY ?need ORDER BY ?need"
    "ASK { ?x a rdm:DesignInput }"
    "CONSTRUCT { ?i rdm:tracesTo ?need } WHERE { ?i rdm:tracesTo ?need }"
)
for i in "${!QUERIES[@]}"; do
    for format in tsv csv json; do
        uv run --project "$ROOT" --quiet rdm graph query --dhf dhf --format "$format" "${QUERIES[$i]}" > "$CMP/q$i.native" 2>&1
        native_code=$?
        "$HERE/rdm-wasm" graph query --dhf dhf --format "$format" "${QUERIES[$i]}" > "$CMP/q$i.wasm" 2>&1
        wasm_code=$?
        # The same rows, and for SELECT the same header; CONSTRUCT's order is not defined, so it is not compared.
        header=yes
        case "${QUERIES[$i]}" in SELECT*) head -1 "$CMP/q$i.native" | cmp -s - <(head -1 "$CMP/q$i.wasm") || header=no ;; esac
        if [ "$native_code" = "$wasm_code" ] && cmp -s <(sort "$CMP/q$i.native") <(sort "$CMP/q$i.wasm") \
            && [ $header = yes ]; then same=yes; else same=NO; status=1; fi
        printf '%-34.34s %6s %6s %8s %8s  %s\n' "query $format: ${QUERIES[$i]}" "$native_code" "$wasm_code" - - "$same"
    done
done
# rdm init, each into an empty repository: the files it lays down must be the same.
for side in native wasm; do rm -rf "${CMP:?}/init-$side" && mkdir -p "$CMP/init-$side" && git -C "$CMP/init-$side" init -q; done
(cd "$CMP/init-native" && uv run --project "$ROOT" --quiet rdm init > ../init-native.out 2>&1); native_code=$?
(cd "$CMP/init-wasm" && "$HERE/rdm-wasm" init > ../init-wasm.out 2>&1); wasm_code=$?
if [ "$native_code" = "$wasm_code" ] && diff -r -q -x .git "$CMP/init-native" "$CMP/init-wasm" > /dev/null; then same=yes; else same=NO; status=1; fi
printf '%-34s %6s %6s %8s %8s  %s\n' "init (empty repository)" "$native_code" "$wasm_code" - - "$same ($(find "$CMP/init-wasm" -type f -not -path '*/.git/*' | wc -l) files)"
echo "Outputs: $CMP/<n>/{native,wasm} (untracked; delete when done)"
exit $status
