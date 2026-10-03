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

from rdm.architecture.model import component_of, read_model
from rdm.evidence import allure
from rdm.evidence.unit_coverage import read_unit_coverage
from rdm.kernel.ids import relevant_orphans
from rdm.specification.sdd import design_inputs, registry_user_needs


def unit_coverage(dhf_dir: Path, report: Path) -> dict:
    """The unit tests' code coverage per C4 component with code (DI-76): the
    lines of its code the report says ran, of those it measured, and apart the
    components whose code the report does not measure. A file of no component
    is ignored. Raises ValueError when the report cannot be read."""
    model = read_model(dhf_dir)
    root = Path(dhf_dir).parent  # the project the model's code paths are relative to
    components = model.code_components
    totals: dict[str, list[int]] = {}
    for path, (executed, measured) in read_unit_coverage(report, root).items():
        component = component_of(path, components)
        if component is not None:
            total = totals.setdefault(component.alias, [0, 0])
            total[0] += executed
            total[1] += measured
    measured, unmeasured = [], []
    for component in sorted(components, key=lambda c: c.alias):
        executed, lines = totals.get(component.alias, (0, 0))
        entry = {"component": component.alias, "name": component.name, "context": component.context}
        if lines:
            measured.append(entry | {"executed": executed, "measured": lines, "percent": executed * 100 // lines})
        else:
            unmeasured.append(entry)
    return {"components": measured, "unmeasured": unmeasured}


def build_verification(dhf_dir: Path, allure_results_dir: Path, unit_coverage_report: Path | None = None) -> dict:
    """Reconcile design inputs against Allure results into a render-ready dict.

    Verification is anchored on design inputs (the `@allure.story("DI-…")` tag);
    each design-input row is grouped under the user need(s) it traces to. Given
    the unit tests' coverage report, the data also carries ``unit_coverage``
    (DI-76): unit-test evidence beside the design inputs, never acceptance
    evidence.
    """
    coverage = unit_coverage(dhf_dir, unit_coverage_report) if unit_coverage_report is not None else None
    inputs = design_inputs(dhf_dir)
    di_ids = {di["id"] for di in inputs}
    user_needs = registry_user_needs(dhf_dir)
    report = allure.reconcile(di_ids, allure_results_dir)

    def _row(di: dict) -> dict:
        verification = report.by_id[di["id"]]
        return {
            "design_input": di["id"],
            "text": di["text"],
            "status": verification.status,
            "passed": verification.passed,
            "failed": verification.failed,
            "skipped": verification.skipped,
            "tests": sorted(verification.tests),
            "outputs": sorted(verification.outputs),
            "traces_to": di["traces_to"],
        }

    rows = {di["id"]: _row(di) for di in inputs}

    groups = []
    for un in sorted(user_needs):
        members = [rows[di["id"]] for di in inputs if un in di["traces_to"]]
        if members:
            groups.append({"user_need": un, "design_inputs": members})
    unassigned = [
        rows[di["id"]] for di in inputs if not (set(di["traces_to"]) & user_needs)
    ]
    if unassigned:
        groups.append({"user_need": "(unassigned / constraint)", "design_inputs": unassigned})

    data = {
        "summary": {
            "verified": len(report.verified),
            "failed": len(report.failed),
            "untested": len(report.untested),
            "total": len(di_ids),
            "results_found": report.results_found,
        },
        "groups": groups,
        "orphans": relevant_orphans(report.orphan_ids, di_ids),  # as the gates report them
        "unreadable": report.unreadable,  # each could hold a failed run: the release gate blocks on them
    }
    if coverage is not None:
        data["unit_coverage"] = coverage
    return data


def write_verification_file(dhf_dir: Path, allure_results_dir: Path, output_path: Path,
                            unit_coverage_report: Path | None = None) -> dict:
    """Write the verification data to a YAML file and return it."""
    data = build_verification(Path(dhf_dir), Path(allure_results_dir), unit_coverage_report)
    out = Path(output_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    return data


def verify_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    output: Path | None = None,
    unit_coverage_report: Path | None = None,
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
    try:
        data = write_verification_file(dhf, results, out, unit_coverage_report)
    except ValueError as exc:  # an unreadable coverage report is an error, never no coverage
        print(f"Error: {exc}")
        return 2
    summary = data["summary"]
    print(
        f"Wrote {out}: {summary['verified']} verified, {summary['failed']} failed, "
        f"{summary['untested']} untested of {summary['total']} design input(s) "
        f"({summary['results_found']} Allure result(s))"
    )
    if "unit_coverage" in data:
        coverage = data["unit_coverage"]
        print(f"Unit-test code coverage: {len(coverage['components'])} component(s) measured, "
              f"{len(coverage['unmeasured'])} not measured")
    if data["unreadable"]:
        print(f"Error: {len(data['unreadable'])} result file(s) cannot be read, and could hold a failed run: "
              f"{', '.join(data['unreadable'])}")
        return 1
    return 0
