# ============================================================================
# Regulatory Documentation Build Image
# RDM (with the graph extra) + Pandoc + Typst, on Ubuntu LTS
# ============================================================================

# Pinned to an LTS release, never `latest`: the base changes only when we
# choose. Ubuntu (glibc) also gets the ordinary Linux wheels.
ARG UBUNTU_VERSION=26.04

FROM ubuntu:${UBUNTU_VERSION} AS builder

ARG PANDOC_VERSION=3.12
ARG TYPST_VERSION=0.15.1
ARG INTER_VERSION=4.1
ARG JETBRAINS_MONO_VERSION=2.304
# Docker provides TARGETARCH (amd64 or arm64).
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN PANDOC_ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "arm64" || echo "amd64") && \
    curl -fsSL "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-linux-${PANDOC_ARCH}.tar.gz" \
    | tar xzf - --strip-components=2 -C /usr/local/bin "pandoc-${PANDOC_VERSION}/bin/pandoc"

RUN TYPST_ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64" || echo "x86_64") && \
    curl -fsSL "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-${TYPST_ARCH}-unknown-linux-musl.tar.xz" \
    | tar xJf - --strip-components=1 -C /usr/local/bin "typst-${TYPST_ARCH}-unknown-linux-musl/typst"

RUN mkdir -p /fonts/inter /fonts/jetbrains-mono && \
    curl -fsSL -o /tmp/inter.zip "https://github.com/rsms/inter/releases/download/v${INTER_VERSION}/Inter-${INTER_VERSION}.zip" && \
    unzip -q /tmp/inter.zip -d /tmp/inter && find /tmp/inter -name "Inter*.ttf" -exec cp {} /fonts/inter/ \; && \
    curl -fsSL -o /tmp/jbmono.zip "https://github.com/JetBrains/JetBrainsMono/releases/download/v${JETBRAINS_MONO_VERSION}/JetBrainsMono-${JETBRAINS_MONO_VERSION}.zip" && \
    unzip -q /tmp/jbmono.zip -d /tmp/jbmono && cp /tmp/jbmono/fonts/ttf/*.ttf /fonts/jetbrains-mono/

# ============================================================================
# Final image
# ============================================================================

FROM ubuntu:${UBUNTU_VERSION}

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        fontconfig \
        fonts-noto-core \
        fonts-noto-color-emoji \
        git \
        make \
        python3 \
        python3-venv \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /usr/local/bin/pandoc /usr/local/bin/typst /usr/local/bin/
COPY --from=builder /fonts/ /usr/share/fonts/truetype/
RUN fc-cache -f && pandoc --version && typst --version

# RDM, from the wheel built next to this Dockerfile, with the graph extra
# (build, query, validate, serve, mcp), in its own virtualenv: Ubuntu's
# system Python does not take pip installs.
COPY dist/*.whl /tmp/
RUN python3 -m venv /opt/rdm && \
    /opt/rdm/bin/pip install --no-cache-dir "$(ls /tmp/*.whl)[graph]" && \
    rm -f /tmp/*.whl
ENV PATH="/opt/rdm/bin:${PATH}"

# The project is mounted, so its owner differs from the container's user;
# rdm reads git history (approvals, commits) from it.
RUN git config --system --add safe.directory '*' && rdm --version

WORKDIR /dhf
EXPOSE 7878

CMD ["make"]
