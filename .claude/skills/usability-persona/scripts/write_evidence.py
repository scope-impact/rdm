#!/usr/bin/env python3
"""Write a usability-persona run as a *-persona.json evidence record.

Emits the exact schema `rdm story persona` ingests, so the persona run is
recorded deterministically rather than hand-authored.

    python write_evidence.py --results-dir persona-results \
        --persona icu-nurse --user-need UN-001 --outcome success \
        --issues '[{"severity":"difficulty","step":4,"note":"..."}]'
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Mirror rdm.record.persona._FAILURE_OUTCOMES.
FAILURE_OUTCOMES = {"failure", "failed", "blocked", "abandoned"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", required=True)
    parser.add_argument("--persona", required=True)
    parser.add_argument("--user-need", required=True)
    parser.add_argument("--outcome", required=True, help="success, or failed/blocked/abandoned")
    parser.add_argument("--issues", default="[]", help='JSON list of {severity, step, note}')
    args = parser.parse_args()

    try:
        issues = json.loads(args.issues)
    except json.JSONDecodeError as exc:
        print(f"--issues is not valid JSON: {exc}", file=sys.stderr)
        return 2
    if not isinstance(issues, list):
        print("--issues must be a JSON list", file=sys.stderr)
        return 2

    record = {
        "persona": args.persona,
        "user_need": args.user_need,
        "outcome": args.outcome,
        "usability_issues": issues,
    }

    results_dir = Path(args.results_dir)
    results_dir.mkdir(parents=True, exist_ok=True)
    out = results_dir / f"{args.persona}-{args.user_need}-persona.json"
    out.write_text(json.dumps(record, indent=2), encoding="utf-8")

    if args.outcome.lower() in FAILURE_OUTCOMES:
        status = "FAILED journey"
    elif issues:
        status = f"{len(issues)} usability issue(s)"
    else:
        status = "clean (NOT 'validated' -- formative only)"
    print(f"wrote {out} ({status})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
