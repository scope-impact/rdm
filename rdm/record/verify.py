"""
Generate a verification data file from the SDD user needs and Allure results.

This turns executed-test evidence into a render-ready data file. `rdm render`
keys context by data-file basename, so writing ``verification.yml`` makes a
``verification`` variable available to a template (e.g. the traceability matrix
/ V&V record), which is how verification status becomes a generated DHF section.
"""

from __future__ import annotations

from pathlib import Path

import yaml

from rdm.record import allure
from rdm.record.sdd import registry_user_needs


def build_verification(dhf_dir: Path, allure_results_dir: Path) -> dict:
    """Reconcile SDD user needs against Allure results into a render-ready dict."""
    ids = registry_user_needs(dhf_dir)
    report = allure.reconcile(ids, allure_results_dir)

    needs = []
    for uid in sorted(report.by_user_need):
        verification = report.by_user_need[uid]
        needs.append(
            {
                "user_need": uid,
                "status": verification.status,
                "passed": verification.passed,
                "failed": verification.failed,
                "skipped": verification.skipped,
                "tests": sorted(verification.tests),
            }
        )

    return {
        "summary": {
            "verified": len(report.verified),
            "failed": len(report.failed),
            "untested": len(report.untested),
            "total": len(ids),
            "results_found": report.results_found,
        },
        "needs": needs,
        "orphans": report.orphan_ids,
    }


def write_verification_file(dhf_dir: Path, allure_results_dir: Path, output_path: Path) -> dict:
    """Write the verification data to a YAML file and return it."""
    data = build_verification(Path(dhf_dir), Path(allure_results_dir))
    out = Path(output_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    return data


def verify_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    output: Path | None = None,
) -> int:
    """Run the `rdm story verify` command."""
    dhf = Path(dhf_dir or "dhf")
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    if not allure_results_dir:
        print("Error: --allure-results <dir> is required")
        return 2
    results = Path(allure_results_dir)
    if not results.exists():
        print(f"Error: Allure results directory not found: {results}")
        return 2

    out = Path(output or "verification.yml")
    data = write_verification_file(dhf, results, out)
    summary = data["summary"]
    print(
        f"Wrote {out}: {summary['verified']} verified, {summary['failed']} failed, "
        f"{summary['untested']} untested of {summary['total']} user need(s) "
        f"({summary['results_found']} Allure result(s))"
    )
    return 0
