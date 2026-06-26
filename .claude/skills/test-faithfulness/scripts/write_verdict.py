#!/usr/bin/env python3
"""Write a faithfulness verdict as a <dhf>/faithfulness/<DI>-faithfulness.json record.

Computes the test_hash the same way `rdm story faithfulness` / the release gate do
(design-input text + its verifying test source), so the verdict is *current* the
moment it is written and goes stale automatically if the test later changes.

    python write_verdict.py --dhf dhf --design-input DI-1 --verdict faithful \
        --reviewer "claude (independent of author)" \
        --rationale "exercises the real ingest path; asserts the grouped shape, not a tautology"
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from rdm.record.allure import find_tests_dir
from rdm.record.faithfulness import FAITHFUL, PARTIAL, UNFAITHFUL, current_hashes
from rdm.record.sdd import design_inputs

VERDICTS = {FAITHFUL, PARTIAL, UNFAITHFUL, "weak"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dhf", required=True)
    parser.add_argument("--design-input", required=True, help="e.g. DI-1")
    parser.add_argument("--verdict", required=True, choices=sorted(VERDICTS))
    parser.add_argument("--reviewer", required=True, help="who reviewed (must be independent of the test author)")
    parser.add_argument("--rationale", required=True, help="why the test does / does not verify the input")
    parser.add_argument("--reviewed-tests", default="", help="comma-separated test names examined")
    parser.add_argument(
        "--uncovered",
        default="",
        help="semicolon-separated requirement clauses the test does NOT cover "
        "(non-empty downgrades the verdict to partial at the gate)",
    )
    args = parser.parse_args()

    dhf = Path(args.dhf)
    inputs = design_inputs(dhf)
    ids = {di["id"] for di in inputs}
    if args.design_input not in ids:
        print(f"{args.design_input} is not a declared design input ({', '.join(sorted(ids))})", file=sys.stderr)
        return 2

    # Pin to the CURRENT hash so the verdict is valid for exactly the test it reviewed.
    test_hash = current_hashes(inputs, find_tests_dir(dhf))[args.design_input]
    reviewed = [t.strip() for t in args.reviewed_tests.split(",") if t.strip()]
    uncovered = [c.strip() for c in args.uncovered.split(";") if c.strip()]

    record = {
        "design_input": args.design_input,
        "verdict": args.verdict,
        "reviewer": args.reviewer,
        "rationale": args.rationale,
        "test_hash": test_hash,
        "reviewed_tests": reviewed,
        "uncovered_clauses": uncovered,
    }

    out_dir = dhf / "faithfulness"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{args.design_input}-faithfulness.json"
    out.write_text(json.dumps(record, indent=2), encoding="utf-8")
    print(f"wrote {out} ({args.verdict}, pinned {test_hash[:19]}…)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
