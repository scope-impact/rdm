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

# The fonts of the document template: Nunito Sans (text; Google Fonts' static
# files of version v19, one per weight and style) and JetBrains Mono (headings
# and code).
RUN mkdir -p /fonts/nunito-sans /fonts/jetbrains-mono && \
    curl -fsSL -o /fonts/nunito-sans/NunitoSans-Regular.ttf "https://fonts.gstatic.com/s/nunitosans/v19/pe1mMImSLYBIv1o4X1M8ce2xCx3yop4tQpF_MeTm0lfGWVpNn64CL7U8upHZIbMV51Q42ptCp5F5bxqqtQ1yiU4G1ilntA.ttf" && \
    curl -fsSL -o /fonts/nunito-sans/NunitoSans-SemiBold.ttf "https://fonts.gstatic.com/s/nunitosans/v19/pe1mMImSLYBIv1o4X1M8ce2xCx3yop4tQpF_MeTm0lfGWVpNn64CL7U8upHZIbMV51Q42ptCp5F5bxqqtQ1yiU4GCC5ntA.ttf" && \
    curl -fsSL -o /fonts/nunito-sans/NunitoSans-Bold.ttf "https://fonts.gstatic.com/s/nunitosans/v19/pe1mMImSLYBIv1o4X1M8ce2xCx3yop4tQpF_MeTm0lfGWVpNn64CL7U8upHZIbMV51Q42ptCp5F5bxqqtQ1yiU4GMS5ntA.ttf" && \
    curl -fsSL -o /fonts/nunito-sans/NunitoSans-Italic.ttf "https://fonts.gstatic.com/s/nunitosans/v19/pe1kMImSLYBIv1o4X1M8cce4OdVisMz5nZRqy6cmmmU3t2FQWEAEOvV9wNvrwlNstMKW3Y6K5WMwXeVy3GboJ0kTHmqP91Ug.ttf" && \
    curl -fsSL -o /fonts/nunito-sans/NunitoSans-BoldItalic.ttf "https://fonts.gstatic.com/s/nunitosans/v19/pe1kMImSLYBIv1o4X1M8cce4OdVisMz5nZRqy6cmmmU3t2FQWEAEOvV9wNvrwlNstMKW3Y6K5WMwXeVy3GboJ0kTHmpo8FUg.ttf" && \
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

# RDM, with the graph extra (build, query, validate, serve, mcp), in its own
# virtualenv (Ubuntu's system Python does not take pip installs): from a
# wheel in dist/ next to this Dockerfile when there is one, else the RDM
# release this project was laid down with, from git. The Dockerfile is copied
# too only so the COPY never matches nothing when there is no dist/.
ARG RDM_SPEC="rdm[graph] @ git+https://github.com/scope-impact/rdm@v{rdm_version}"
COPY Dockerfile dist* /tmp/rdm-build/
RUN python3 -m venv /opt/rdm && \
    wheel="$(ls /tmp/rdm-build/*.whl 2>/dev/null | head -n 1)" && \
    if [ -n "$wheel" ]; then /opt/rdm/bin/pip install --no-cache-dir "${wheel}[graph]"; \
    else /opt/rdm/bin/pip install --no-cache-dir "$RDM_SPEC"; fi && \
    rm -rf /tmp/rdm-build
ENV PATH="/opt/rdm/bin:${PATH}"

# The project is mounted, so its owner differs from the container's user;
# rdm reads git history (approvals, commits) from it.
RUN git config --system --add safe.directory '*' && rdm --version

WORKDIR /dhf
EXPOSE 7878

CMD ["make"]
