#!/usr/bin/env bash
# Runs the acceptance suites of Löyly's pi harness (harness-impl/NN-*/test.sh),
# unchanged, and writes each test as an Allure result to dhf/allure-results.
#
# Löyly knows nothing of RDM or Allure. bridge/user.clj goes on the JVM's boot
# class path, so Clojure loads it at start-up in every test JVM and it records
# what clojure.test reports. Needs the Clojure CLI and a JDK on PATH.
#
#   ./run-acceptance.sh [loyly-checkout]     # default: RDM's loyly submodule
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOYLY="$(cd "${1:-$HERE/../../loyly}" && pwd)"
RESULTS="$HERE/dhf/allure-results"
rm -rf "$RESULTS" && mkdir -p "$RESULTS"
export ALLURE_RESULTS_DIR="$RESULTS"
# The version this run is evidence for: Löyly's commit, and whether its
# checkout had uncommitted changes.
ALLURE_COMMIT="$(git -C "$LOYLY" rev-parse HEAD 2>/dev/null || true)"
ALLURE_WORKTREE="$([ -n "$(git -C "$LOYLY" status --porcelain 2>/dev/null)" ] && echo dirty || echo clean)"
export ALLURE_COMMIT ALLURE_WORKTREE
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Xbootclasspath/a:$HERE/bridge"
# The suites build into their own directories: run them in a copy, so the
# checkout (RDM's submodule) stays clean.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
tar -C "$LOYLY" --exclude=.git -cf - . | tar -C "$WORK" -xf -
# A repository of its own: the cycle runner's suite clones its source cut.
git -C "$WORK" init -q && git -C "$WORK" add -A \
  && git -C "$WORK" -c user.name=rdm -c user.email=rdm@localhost commit -qm "Löyly at $(git -C "$LOYLY" rev-parse --short HEAD 2>/dev/null || echo unknown)"
status=0
for suite in "$WORK"/harness-impl/[0-9][0-9]-*/; do
  name="$(basename "$suite")"
  echo "== $name"
  if ! (cd "$suite" && ./test.sh); then
    echo "== $name failed" >&2
    status=1
  fi
done
echo "$(ls "$RESULTS" | grep -c -- '-result.json$') Allure results in $RESULTS"
exit $status
