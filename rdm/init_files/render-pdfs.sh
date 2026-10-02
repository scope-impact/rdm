#!/usr/bin/env bash
#
# Render every controlled document to PDF in the RDM image, with each Mermaid
# diagram drawn by Mermaid's official image (DI-70). RDM's PDF action runs this
# script; run it by hand from the repository root:
#
#   dhf/render-pdfs.sh [dhf-directory] [make-target]     # defaults: dhf pdfs
#
# Three steps on shared files: render the Markdown, writing each undrawn
# diagram to <dhf>/tmp/mermaid/<hash>.mmd; draw each with Mermaid's image;
# render again, when every diagram is its image or the render fails.
# Requires Docker. RDM_IMAGE and MERMAID_IMAGE override the images.
set -euo pipefail

DHF="${1:-dhf}"
TARGET="${2:-pdfs}"
RDM_IMAGE="${RDM_IMAGE:-ghcr.io/scope-impact/rdm:latest}"
# Pinned by version and digest: the renderer is part of the controlled toolchain.
MERMAID_IMAGE="${MERMAID_IMAGE:-ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:12.0.0@sha256:fa995339034aae7e5cd4f61482248b7f5c51be355b1a6f6eda11a2bbf8401f5f}"

make_in_rdm() {
    docker run --rm -v "$PWD:/project" -w "/project/$DHF" "$@"
}

# 1. The Markdown, leaving each undrawn diagram for step 2.
make_in_rdm -e RDM_MERMAID_COLLECT=1 --entrypoint make "$RDM_IMAGE" -B all

# 2. Draw each diagram not drawn yet. As root: the files belong to whoever ran
#    step 1, and Chrome runs without its sandbox (the image's own config).
if compgen -G "$DHF/tmp/mermaid/*.mmd" > /dev/null; then
    echo "Drawing Mermaid diagrams with $MERMAID_IMAGE"
    docker run --rm -u 0 -v "$PWD/$DHF/tmp/mermaid:/data" --entrypoint sh "$MERMAID_IMAGE" -c '
        for f in *.mmd; do
            [ -f "${f%.mmd}.svg" ] && continue
            /home/mermaidcli/node_modules/.bin/mmdc -p /puppeteer-config.json -c config.json -b white -q \
                -i "$f" -o "${f%.mmd}.svg" || { echo "Mermaid diagram $f could not be drawn" >&2; exit 1; }
        done'
fi

# 3. The documents, every diagram now its image.
make_in_rdm --entrypoint make "$RDM_IMAGE" -B "$TARGET"
