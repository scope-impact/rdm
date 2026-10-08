#!/usr/bin/env bash
#
# Spike: rdm-git, gitoxide exporting rdm:component/record-state (see ../README.md).
# gitoxide maps files into memory and reads file times through crates that do
# not support WASI; this copies those two crates and replaces their WASI part
# (patches/), then builds for wasm32-wasip2.
#
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-wasip2 > /dev/null
mkdir -p .work
for crate in memmap2-0.9.11:memmap2:src/stub.rs:memmap2-stub.rs filetime-0.2.29:filetime:src/wasm.rs:filetime-wasm.rs; do
    IFS=: read -r source name file patch <<< "$crate"
    if [ ! -d ".work/$name" ]; then
        found=$(ls -d "${CARGO_HOME:-$HOME/.cargo}"/registry/src/*/"$source" 2>/dev/null | head -1 || true)
        if [ -z "$found" ]; then   # not fetched yet: fetch it through a throwaway manifest
            tmp=$(mktemp -d) && (cd "$tmp" && cargo init -q --name fetch && cargo add -q "${source%-*}@=${source##*-}" && cargo fetch -q)
            rm -rf "$tmp"
            found=$(ls -d "${CARGO_HOME:-$HOME/.cargo}"/registry/src/*/"$source" | head -1)
        fi
        cp -r "$found" ".work/$name"
    fi
    cp "patches/$patch" ".work/$name/$file"
done
cargo build -q --release --locked --target wasm32-wasip2
ls -l target/wasm32-wasip2/release/rdm_git.wasm
