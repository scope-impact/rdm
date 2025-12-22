# ============================================================================
# Regulatory Documentation Build Image
# Pandoc + Typst | Minimal & Fast
# ============================================================================

FROM alpine:3.21 AS builder

# Versions
ARG PANDOC_VERSION=3.6.1
ARG TYPST_VERSION=0.12.0

# Install Pandoc
RUN wget -qO- "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-linux-amd64.tar.gz" \
    | tar xzf - --strip-components=1 -C /usr/local

# Install Typst
RUN wget -qO- "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-x86_64-unknown-linux-musl.tar.xz" \
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
    rm -rf /tmp/inter /tmp/inter.zip && \
    fc-cache -f

# Verify installations
RUN pandoc --version && typst --version

# Install RDM
RUN pip install --no-cache-dir --break-system-packages rdm

WORKDIR /dhf

CMD ["make"]
