# ============================================================================
# Regulatory Documentation Build Image
# RDM (with the graph extra) + rdm-typst (PDF) + Pandoc (Word), on Ubuntu LTS
# ============================================================================

# Pinned to an LTS release, never `latest`: the base changes only when we
# choose. Ubuntu (glibc) also gets the ordinary Linux wheels.
ARG UBUNTU_VERSION=26.04

FROM ubuntu:${UBUNTU_VERSION} AS builder

ARG PANDOC_VERSION=3.12
ARG RUST_VERSION=1.97.0
# Docker provides TARGETARCH (amd64 or arm64).
ARG TARGETARCH

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl build-essential git \
    && rm -rf /var/lib/apt/lists/*

# Pandoc, for the Word copies (make docs).
RUN PANDOC_ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "arm64" || echo "amd64") && \
    curl -fsSL "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-linux-${PANDOC_ARCH}.tar.gz" \
    | tar xzf - --strip-components=2 -C /usr/local/bin "pandoc-${PANDOC_VERSION}/bin/pandoc"

# rdm-typst, RDM's typeset provider (DI-88), for the PDFs (make pdfs, rdm typeset): Typst with the
# template's fonts built in. From providers/typst next to this Dockerfile when there is one (RDM's
# own CI), else from the RDM release this project was laid down with, from git.
ARG RDM_REPOSITORY=https://github.com/scope-impact/rdm
ARG RDM_TAG=v{rdm_version}
COPY Dockerfile providers* /tmp/providers/
RUN curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain "${RUST_VERSION}" && \
    . "$HOME/.cargo/env" && \
    if [ -d /tmp/providers/typst ]; then cargo install --locked --path /tmp/providers/typst --root /usr/local; \
    else cargo install --locked --git "${RDM_REPOSITORY}" --tag "${RDM_TAG}" rdm-typst --root /usr/local; fi && \
    rdm-typst 2>&1 | grep -q usage

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

COPY --from=builder /usr/local/bin/pandoc /usr/local/bin/rdm-typst /usr/local/bin/
RUN fc-cache -f && pandoc --version

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
