#!/usr/bin/env bash
# Install babashka, which runs the harness (harness/, `bb harness`), pinned to a
# version and its checksum: a downloaded binary that does not match is refused.
# Usage: scripts/install-babashka.sh [DIR]   (default: ~/.local/bin)
set -euo pipefail
VERSION=1.12.209
SHA256=6f34a2c65266b10413ea4a49c1243a0126d4dcd7f64d14bc3ce781f134fe4c9a
DIR="${1:-$HOME/.local/bin}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -sSfL -o "$WORK/bb.tgz" \
  "https://github.com/babashka/babashka/releases/download/v$VERSION/babashka-$VERSION-linux-amd64-static.tar.gz"
echo "$SHA256  $WORK/bb.tgz" | sha256sum -c --quiet -
tar -xzf "$WORK/bb.tgz" -C "$WORK"
mkdir -p "$DIR"
install -m 0755 "$WORK/bb" "$DIR/bb"
"$DIR/bb" --version
