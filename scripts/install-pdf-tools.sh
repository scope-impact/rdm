#!/bin/sh
# Install what the docs build needs to publish the design history file as
# PDFs (docs/_hooks/dhf.py): Pandoc, Typst and the document template's fonts,
# the same versions as the RDM image (rdm/specification/init_files/Dockerfile).
#
#   scripts/install-pdf-tools.sh [PREFIX]   # default ~/.local: PREFIX/bin, PREFIX/share/fonts
set -eu

PANDOC_VERSION=3.12
TYPST_VERSION=0.15.1
JETBRAINS_MONO_VERSION=2.304
PREFIX=${1:-$HOME/.local}
BIN=$PREFIX/bin
FONTS=$PREFIX/share/fonts/rdm
mkdir -p "$BIN" "$FONTS"

curl -fsSL "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-linux-amd64.tar.gz" \
  | tar xzf - --strip-components=2 -C "$BIN" "pandoc-${PANDOC_VERSION}/bin/pandoc"
curl -fsSL "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-x86_64-unknown-linux-musl.tar.xz" \
  | tar xJf - --strip-components=1 -C "$BIN" "typst-x86_64-unknown-linux-musl/typst"

# Nunito Sans: Google Fonts' static files of version v19, one per weight and style.
curl -fsSL -o "$FONTS/NunitoSans-Regular.ttf" "https://fonts.gstatic.com/s/nunitosans/v19/pe1mMImSLYBIv1o4X1M8ce2xCx3yop4tQpF_MeTm0lfGWVpNn64CL7U8upHZIbMV51Q42ptCp5F5bxqqtQ1yiU4G1ilntA.ttf"
curl -fsSL -o "$FONTS/NunitoSans-SemiBold.ttf" "https://fonts.gstatic.com/s/nunitosans/v19/pe1mMImSLYBIv1o4X1M8ce2xCx3yop4tQpF_MeTm0lfGWVpNn64CL7U8upHZIbMV51Q42ptCp5F5bxqqtQ1yiU4GCC5ntA.ttf"
curl -fsSL -o "$FONTS/NunitoSans-Bold.ttf" "https://fonts.gstatic.com/s/nunitosans/v19/pe1mMImSLYBIv1o4X1M8ce2xCx3yop4tQpF_MeTm0lfGWVpNn64CL7U8upHZIbMV51Q42ptCp5F5bxqqtQ1yiU4GMS5ntA.ttf"
curl -fsSL -o "$FONTS/NunitoSans-Italic.ttf" "https://fonts.gstatic.com/s/nunitosans/v19/pe1kMImSLYBIv1o4X1M8cce4OdVisMz5nZRqy6cmmmU3t2FQWEAEOvV9wNvrwlNstMKW3Y6K5WMwXeVy3GboJ0kTHmqP91Ug.ttf"
curl -fsSL -o "$FONTS/NunitoSans-BoldItalic.ttf" "https://fonts.gstatic.com/s/nunitosans/v19/pe1kMImSLYBIv1o4X1M8cce4OdVisMz5nZRqy6cmmmU3t2FQWEAEOvV9wNvrwlNstMKW3Y6K5WMwXeVy3GboJ0kTHmpo8FUg.ttf"

tmp=$(mktemp -d)
curl -fsSL -o "$tmp/jbmono.zip" "https://github.com/JetBrains/JetBrainsMono/releases/download/v${JETBRAINS_MONO_VERSION}/JetBrainsMono-${JETBRAINS_MONO_VERSION}.zip"
unzip -q "$tmp/jbmono.zip" -d "$tmp/jbmono"
cp "$tmp"/jbmono/fonts/ttf/*.ttf "$FONTS/"
rm -rf "$tmp"
command -v fc-cache >/dev/null && fc-cache -f "$FONTS" >/dev/null || true

"$BIN/pandoc" --version | head -1
"$BIN/typst" --version
