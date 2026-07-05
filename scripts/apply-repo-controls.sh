#!/usr/bin/env bash
#
# Apply (or audit) the document-control configuration on a GitHub repository:
# the controlled-documents ruleset AND the repository merge settings.
#
#   ./setup.sh [owner/repo]           apply: push github/rulesets/*.json and
#                                     github/settings.json to the repository
#   ./setup.sh --check [owner/repo]   audit: compare the LIVE configuration
#                                     against the checked-in JSON; exit 1 on drift
#
# Requires: gh (authenticated with admin access to the repo) and jq.
# The checked-in JSON stays the source of truth — this script only pushes it
# to, or diffs it against, the service provider. Run --check periodically (or
# from CI on a schedule) as the "configuration has not drifted" audit.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RULESET_FILE="$HERE/../.github/rulesets/controlled-record.json"
SETTINGS_FILE="$HERE/../.github/rulesets/repo-settings.json"

MODE=apply
if [ "${1:-}" = "--check" ]; then
    MODE=check
    shift
fi

for tool in gh jq; do
    command -v "$tool" >/dev/null 2>&1 || { echo "error: '$tool' is required" >&2; exit 2; }
done

REPO="${1:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
NAME="$(jq -r .name "$RULESET_FILE")"

# The fields we control, in a stable shape (rules ordered by type).
normalize() {
    jq -S '{name, target, enforcement, conditions, rules: (.rules | sort_by(.type))}'
}

existing_id="$(gh api "repos/$REPO/rulesets" --jq "map(select(.name == \"$NAME\")) | (first // {}) | .id // empty")"

if [ "$MODE" = "check" ]; then
    drift=0

    if [ -z "$existing_id" ]; then
        echo "DRIFT: ruleset '$NAME' does not exist on $REPO (run ./setup.sh to apply)" >&2
        drift=1
    else
        live="$(gh api "repos/$REPO/rulesets/$existing_id" | normalize)"
        want="$(normalize < "$RULESET_FILE")"
        # Subset check: the live ruleset must contain everything we declare
        # (the API echoes extra server-side fields; those are not drift).
        if echo "$live" | jq -e --argjson want "$want" 'contains($want)' >/dev/null; then
            echo "OK: live ruleset '$NAME' on $REPO matches $RULESET_FILE"
        else
            echo "DRIFT: live ruleset '$NAME' on $REPO differs from $RULESET_FILE" >&2
            diff <(echo "$want") <(echo "$live") >&2 || true
            drift=1
        fi
    fi

    # Repository merge settings (DI-6): the live repo must contain every
    # key/value declared in settings.json.
    if gh api "repos/$REPO" | jq -e --argjson want "$(cat "$SETTINGS_FILE")" 'contains($want)' >/dev/null; then
        echo "OK: live repository settings on $REPO match $SETTINGS_FILE"
    else
        echo "DRIFT: live repository settings on $REPO differ from $SETTINGS_FILE" >&2
        diff <(jq -S . "$SETTINGS_FILE") <(gh api "repos/$REPO" | jq -S "with_entries(select(.key as \$k | $(jq 'keys' "$SETTINGS_FILE") | index(\$k)))") >&2 || true
        drift=1
    fi

    exit "$drift"
fi

if [ -n "$existing_id" ]; then
    gh api --method PUT "repos/$REPO/rulesets/$existing_id" --input "$RULESET_FILE" >/dev/null
    echo "Updated ruleset '$NAME' (id $existing_id) on $REPO from $RULESET_FILE"
else
    gh api --method POST "repos/$REPO/rulesets" --input "$RULESET_FILE" >/dev/null
    echo "Created ruleset '$NAME' on $REPO from $RULESET_FILE"
fi
gh api --method PATCH "repos/$REPO" --input "$SETTINGS_FILE" >/dev/null
echo "Applied repository merge settings from $SETTINGS_FILE"
echo "Verify: ./setup.sh --check $REPO"
