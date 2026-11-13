#!/usr/bin/env bash
# Push a suite's Allure results to GHCR as an OCI artifact, with what RDM
# derived from them, so the evidence outlives Actions artifacts (90 days) and
# is addressed by digest.
#
#   scripts/push-allure-oci.sh NAME DIR
#
# DIR holds allure-results/ (the executed results), verification.yml
# (rdm story verify) and the rendered documents (*.md). Pushed to
# ghcr.io/<owner>/<repo>/NAME, tagged with the commit and, on main, `main`.
# Pull with: oras pull ghcr.io/scope-impact/rdm/NAME:main
set -euo pipefail

name="$1"
dir="$2"
repository="$(echo "$GITHUB_REPOSITORY" | tr '[:upper:]' '[:lower:]')"
ref="ghcr.io/$repository/$name"
tags="$GITHUB_SHA"
[ "$GITHUB_REF" = refs/heads/main ] && tags="$tags,main"

cd "$dir"
files=(allure-results/:application/vnd.qameta.allure.results.v1.tar+gzip
       verification.yml:application/yaml)
for md in *.md; do files+=("$md:text/markdown"); done

echo "$GITHUB_TOKEN" | oras login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
oras push "$ref:$tags" \
  --artifact-type application/vnd.scope-impact.allure-evidence.v1 \
  --annotation "org.opencontainers.image.source=https://github.com/$GITHUB_REPOSITORY" \
  --annotation "org.opencontainers.image.revision=$GITHUB_SHA" \
  --annotation "org.opencontainers.image.created=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "${files[@]}"
echo "pushed $ref:$tags"
