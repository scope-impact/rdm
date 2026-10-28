#!/bin/sh
# The builder's tests of the WebAssembly build (dhf/harness/stack.md,
# "Builder's tests"; dhf/harness/config.edn, :verify). From the sources only
# (wasm/target and wasm/build are not committed), it:
#   1. builds the tools a WASI 0.3 component needs newer than the installed
#      ones (wac-cli 0.12.0 and wasm-tools 1.262.0, into wasm/target/tools);
#   2. builds every component for wasm32-wasip2 and checks each targets its
#      world in dhf/wit;
#   3. composes the slice (dhf/wit/slice-01.wac), then the command
#      (dhf/wit/rdm-spec.wac), into wasm/build/rdm-spec.wasm, and builds the
#      native host into wasm/build/;
#   4. runs the unit tests (cargo test, natively);
#   5. runs the scenarios of every component wasm/components.edn names,
#      driving this build's rdm-spec first on PATH (ED-11), and writes one
#      Allure result per scenario and outline row to $RESULTS_DIR.
# It exits 0 when every step passed and every scenario passed but the pending
# ones (wasm/components.edn, :pending).
set -eu
self=$(readlink -f "$0")
here=$(dirname "$self")
root=$(dirname "$here")
cd "$root"
if [ -z "${RESULTS_DIR:-}" ]; then
  echo "cannot-run: RESULTS_DIR must name the directory the results are written to" >&2
  exit 2
fi
mkdir -p "$RESULTS_DIR"
target="$here/target"
tools="$target/tools"
build="$here/build"

say() { echo "test.sh: $*" >&2; }

say "1. tools"
if [ ! -x "$tools/bin/wac" ]; then
  CARGO_TARGET_DIR="$target/tools-build" cargo install --quiet --locked --root "$tools" wac-cli@0.12.0
fi
if [ ! -x "$tools/bin/wasm-tools" ]; then
  CARGO_TARGET_DIR="$target/tools-build" cargo install --quiet --locked --root "$tools" wasm-tools@1.262.0 \
    --no-default-features --features component,wit-component,validate,print,parse
fi
wac="$tools/bin/wac"
wt="$tools/bin/wasm-tools"
export CARGO_TARGET_DIR="$target"

say "2. components"
"$wt" component wit dhf/wit >/dev/null
cargo build --quiet --release --locked --manifest-path "$here/Cargo.toml" --target wasm32-wasip2 \
  -p kernel-command -p project-init -p project-adopt -p project-hooks -p not-built
rm -rf "$build"
mkdir -p "$build/deps/rdm/spec"
"$wt" component wit dhf/wit --wasm -o "$build/deps/rdm/spec/0.1.0.wasm"
for c in kernel-command project-init project-adopt project-hooks not-built; do
  file=$(echo "$c" | tr - _)
  cp "$target/wasm32-wasip2/release/$file.wasm" "$build/deps/rdm/$c.wasm"
done
for c in kernel-command project-init project-adopt project-hooks; do
  "$wt" component targets dhf/wit "$build/deps/rdm/$c.wasm" --world "$c-component"
done
"$wt" component targets dhf/wit "$build/deps/rdm/not-built.wasm" --world not-built

say "3. composition and host"
"$wac" compose dhf/wit/slice-01.wac --deps-dir "$build/deps" -o "$build/deps/rdm/start-a-project.wasm"
"$wt" component targets dhf/wit "$build/deps/rdm/start-a-project.wasm" --world start-a-project
"$wac" compose dhf/wit/rdm-spec.wac --deps-dir "$build/deps" -o "$build/rdm-spec.wasm"
"$wt" component targets dhf/wit "$build/rdm-spec.wasm" --world rdm-spec
cargo build --quiet --release --locked --manifest-path "$here/Cargo.toml" -p rdm-spec-host -p scenarios
cp "$target/release/rdm-spec-host" "$build/rdm-spec-host"

say "4. unit tests"
if ! out=$(cargo test --release --locked --manifest-path "$here/Cargo.toml" \
  -p rdm-kernel -p project-init -p project-adopt -p project-hooks -p rdm-spec-host -p scenarios 2>&1); then
  echo "$out" >&2
  say "unit tests failed"
  exit 1
fi
echo "$out" | grep -E "^test result" | awk '{p+=$4; f+=$6} END {print "test.sh: unit tests: " p " passed, " f " failed"}' >&2

say "5. scenarios"
path="$target/path"
mkdir -p "$path"
printf '#!/bin/sh\nexec sh "%s/rdm-spec" "$@"\n' "$here" > "$path/rdm-spec"
chmod +x "$path/rdm-spec"
PATH="$path:$PATH" "$target/release/scenarios" --results "$RESULTS_DIR"
