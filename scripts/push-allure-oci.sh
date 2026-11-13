#!/usr/bin/env bash
# Push a suite's Allure results to GHCR as an OCI artifact, with what RDM
# derived from them, so the evidence outlives Actions artifacts (90 days) and
# is addressed by digest.
#
#   scripts/push-allure-oci.sh NAME DIR
#
# DIR holds allure-results/ (the executed results) and, when they could be
# made, verification.yml (rdm story verify) and the rendered documents (*.md).
# A failed run is pushed too: whatever exists is pushed, and TEST_OUTCOME
# (the job's status: success or failure) is recorded as an annotation, so a
# consumer sees a failed run's evidence for what it is. Only a run with no
# results at all pushes nothing.
#
# Pushed to ghcr.io/<owner>/<repo>/NAME, tagged with the commit and, on main,
# `main`. Pull with: oras pull ghcr.io/scope-impact/rdm/NAME:main
set -euo pipefail
shopt -s nullglob

name="$1"
dir="$2"
outcome="${TEST_OUTCOME:-unknown}"
repository="$(echo "$GITHUB_REPOSITORY" | tr '[:upper:]' '[:lower:]')"
ref="ghcr.io/$repository/$name"
tags="$GITHUB_SHA"
[ "$GITHUB_REF" = refs/heads/main ] && tags="$tags,main"

if [ ! -d "$dir/allure-results" ] || [ -z "$(ls -A "$dir/allure-results")" ]; then
  echo "::warning::$name: no Allure results, nothing to push (outcome: $outcome)"
  exit 0
fi

cd "$dir"
files=(allure-results/:application/vnd.qameta.allure.results.v1.tar+gzip)
[ -f verification.yml ] && files+=(verification.yml:application/yaml)
for md in *.md; do files+=("$md:text/markdown"); done

echo "$GITHUB_TOKEN" | oras login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
oras push "$ref:$tags" \
  --artifact-type application/vnd.scope-impact.allure-evidence.v1 \
  --annotation "org.opencontainers.image.source=https://github.com/$GITHUB_REPOSITORY" \
  --annotation "org.opencontainers.image.revision=$GITHUB_SHA" \
  --annotation "org.opencontainers.image.created=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --annotation "fi.scopeimpact.test.outcome=$outcome" \
  "${files[@]}"
echo "pushed $ref:$tags (outcome: $outcome; ${#files[@]} file(s))"
