# ============================================================================
# Regulatory Documentation Build Image
# Pandoc + Typst | Minimal & Fast
# ============================================================================

FROM alpine:3.21 AS builder

# Versions
ARG PANDOC_VERSION=3.6.1
ARG TYPST_VERSION=0.12.0

# Architecture detection (Docker provides TARGETARCH automatically)
ARG TARGETARCH

# Install Pandoc (supports amd64, arm64)
RUN PANDOC_ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "arm64" || echo "amd64") && \
    wget -qO- "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-linux-${PANDOC_ARCH}.tar.gz" \
    | tar xzf - --strip-components=1 -C /usr/local

# Install Typst (supports amd64, arm64)
RUN TYPST_ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64" || echo "x86_64") && \
    wget -qO- "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-${TYPST_ARCH}-unknown-linux-musl.tar.xz" \
    | tar xJf - --strip-components=1 -C /usr/local/bin

# ============================================================================
# Final image
# ============================================================================

FROM alpine:3.21

# Runtime dependencies
RUN apk add --no-cache \
    font-noto \
    font-noto-emoji \
    fontconfig \
    make \
    python3 \
    py3-pip \
    py3-jinja2 \
    py3-yaml

# Copy binaries from builder
COPY --from=builder /usr/local/bin/pandoc /usr/local/bin/
COPY --from=builder /usr/local/bin/typst /usr/local/bin/

# Install Inter font
RUN mkdir -p /usr/share/fonts/inter && \
    wget -qO /tmp/inter.zip "https://github.com/rsms/inter/releases/download/v4.0/Inter-4.0.zip" && \
    unzip -q /tmp/inter.zip -d /tmp/inter && \
    mv /tmp/inter/Inter*.ttf /usr/share/fonts/inter/ 2>/dev/null || \
    mv /tmp/inter/InterVariable*.ttf /usr/share/fonts/inter/ 2>/dev/null || true && \
    rm -rf /tmp/inter /tmp/inter.zip

# Install JetBrains Mono font (for code blocks)
RUN mkdir -p /usr/share/fonts/jetbrains-mono && \
    wget -qO /tmp/jbmono.zip "https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip" && \
    unzip -q /tmp/jbmono.zip -d /tmp/jbmono && \
    mv /tmp/jbmono/fonts/ttf/*.ttf /usr/share/fonts/jetbrains-mono/ && \
    rm -rf /tmp/jbmono /tmp/jbmono.zip && \
    fc-cache -f

# Verify installations
RUN pandoc --version && typst --version

# Install RDM from local source using uv
COPY . /tmp/rdm
RUN pip install --no-cache-dir --break-system-packages uv && \
    uv build --wheel --out-dir /tmp/rdm/dist /tmp/rdm && \
    pip install --no-cache-dir --break-system-packages /tmp/rdm/dist/*.whl && \
    pip uninstall -y uv && \
    rm -rf /tmp/rdm

WORKDIR /dhf

CMD ["make"]
