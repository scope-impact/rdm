"""
Generate a verification data file from the spec's requirements and Allure results.

This turns executed-test evidence into a render-ready data file. `rdm render`
keys context by data-file basename, so writing ``verification.yml`` makes a
``verification`` variable available to a template (e.g. the traceability matrix
/ V&V record), which is how verification status becomes a generated DHF section.
"""

from __future__ import annotations

from pathlib import Path

import yaml

from rdm.evidence import allure
from rdm.kernel.ids import relevant_orphans
from rdm.specification.sdd import load_spec, requirements, user_needs


def build_verification(dhf_dir: Path, allure_results_dir: Path) -> dict:
    """Reconcile the spec's requirements against Allure results into a
    render-ready dict: each requirement's row, grouped under the user needs
    it addresses, in spec order."""
    spec = load_spec(dhf_dir)
    reqs = requirements(dhf_dir, spec)
    needs = user_needs(dhf_dir, spec)
    ids = {r["id"] for r in reqs}
    report = allure.reconcile(ids, allure_results_dir)

    def _row(req: dict) -> dict:
        verification = report.by_id[req["id"]]
        return {
            "requirement": req["id"],
            "text": req["text"],
            "rule": req["rule"],
            "source": req["source"],
            "context": req["context"],
            "status": verification.status,
            "passed": verification.passed,
            "failed": verification.failed,
            "skipped": verification.skipped,
            "tests": sorted(set(verification.tests)),
            "outputs": sorted(verification.outputs),
            "traces_to": req["traces_to"],
        }

    rows = {r["id"]: _row(r) for r in reqs}
    groups = []
    for need in needs:
        members = [rows[r["id"]] for r in reqs if need.id in r["traces_to"]]
        if members:
            groups.append({"user_need": need.id, "text": need.text, "actors": need.actors,
                           "surfaces": need.surfaces, "requirements": members})
    unassigned = [rows[r["id"]] for r in reqs if not r["traces_to"]]
    if unassigned:
        groups.append({"user_need": "(no user need)", "text": "", "actors": [], "surfaces": [],
                       "requirements": unassigned})

    return {
        "summary": {
            "verified": len(report.verified),
            "failed": len(report.failed),
            "untested": len(report.untested),
            "total": len(ids),
            "results_found": report.results_found,
        },
        "groups": groups,
        "orphans": relevant_orphans(report.orphan_ids, ids),  # as the gates report them
        "unreadable": report.unreadable,  # each could hold a failed run: the release gate blocks on them
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
        f"{summary['untested']} untested of {summary['total']} requirement(s) "
        f"({summary['results_found']} Allure result(s))"
    )
    if data["unreadable"]:
        print(f"Error: {len(data['unreadable'])} result file(s) cannot be read, and could hold a failed run: "
              f"{', '.join(data['unreadable'])}")
        return 1
    return 0
