# Base image with Pandoc + Typst
# Use --build-arg BASE_IMAGE=rdm:latest for local builds
ARG BASE_IMAGE=ghcr.io/scope-impact/rdm:latest
FROM ${BASE_IMAGE}

# Add DuckDB for template queries
RUN apk add --no-cache g++ cmake make python3-dev && \
    pip install --break-system-packages duckdb && \
    apk del g++ cmake make python3-dev
