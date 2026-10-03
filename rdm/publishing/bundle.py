"""
Release evidence bundle (DI-30): the retained artifact set for a release.

Writes, to an output directory: the verification data (declared design inputs
reconciled against executed Allure results), the rendered traceability matrix,
the executed Allure results themselves with the attachments and containers
they reference, the verification report as a PDF (DI-64), and a manifest
describing the bundle —
the DHR-shaped set a team attaches to a release tag so the evidence outlives
CI artifact retention.
"""

from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

from rdm.publishing.report import REPORT_PDF, ReportUnavailable, write_report
from rdm.specification.sdd import MATRIX_DOC, find_dhf_doc
from rdm.release.verify import write_verification_file


UNIT_COVERAGE_KEPT = ("unit-coverage.xml", "unit-coverage.lcov", "unit-coverage.info", "unit-coverage.txt")


def _attachment_sources(node) -> set[str]:
    """Every attachment ``source`` in an Allure result or container, at any depth."""
    found: set[str] = set()
    if isinstance(node, dict):
        for item in node.get("attachments") or []:
            if isinstance(item, dict) and item.get("source"):
                found.add(str(item["source"]))
        for value in node.values():
            found |= _attachment_sources(value)
    elif isinstance(node, list):
        for value in node:
            found |= _attachment_sources(value)
    return found


def plain_files(results_dir: Path) -> list[Path]:
    """The plain files of a results directory: never a symbolic link, which
    could name any file on the machine."""
    return sorted(p for p in Path(results_dir).iterdir() if p.is_file() and not p.is_symlink()) \
        if Path(results_dir).is_dir() else []


def copy_results(results_dir: Path, dest: Path) -> tuple[list[str], list[str]]:
    """Copy every plain file of the results directory (results, containers,
    attachments, the run's executor and environment), replacing what an
    earlier bundle left; return the names copied, and each attachment a
    result or container names that is not among them."""
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    copied = []
    for source in plain_files(results_dir):
        shutil.copy2(source, dest / source.name)
        copied.append(source.name)
    named: set[str] = set()
    for name in copied:
        if name.endswith(("-result.json", "-container.json")):
            try:
                named |= _attachment_sources(json.loads((dest / name).read_text(encoding="utf-8-sig")))
            except (OSError, ValueError):
                continue  # an unreadable file is still kept as-is
    return copied, sorted(named - set(copied))


def evidence_bundle(dhf_dir: Path, allure_results_dir: Path, out_dir: Path,
                    unit_coverage_report: Path | None = None) -> dict:
    """Produce the bundle; returns the manifest that was written. Raises
    ``ValueError``, writing nothing, when the bundle's copy of the results
    would overlap the results directory (replacing it would delete them), when
    the record has no traceability matrix template to render, or when the unit
    tests' coverage report, if given, cannot be read (DI-30, DI-76)."""
    results, kept = Path(allure_results_dir).resolve(), (Path(out_dir) / "allure-results").resolve()
    if results == kept or results.is_relative_to(kept) or kept.is_relative_to(results):
        raise ValueError(f"the bundle's copy of the results ({kept}) would overlap the results directory "
                         f"({results}): write the bundle elsewhere")
    template = find_dhf_doc(dhf_dir, MATRIX_DOC)
    if template is None:
        raise ValueError(f"the record has no {MATRIX_DOC} template to render the traceability matrix from: "
                         "a bundle without the matrix is incomplete")
    if unit_coverage_report is not None:
        from rdm.evidence.unit_coverage import read_unit_coverage

        read_unit_coverage(Path(unit_coverage_report), Path(dhf_dir).parent)  # refused before anything is written
    out_dir.mkdir(parents=True, exist_ok=True)
    for earlier in ("verification.yml", MATRIX_DOC, REPORT_PDF, "manifest.json", *UNIT_COVERAGE_KEPT):
        (out_dir / earlier).unlink(missing_ok=True)  # an earlier bundle's

    # 1. Verification data: design inputs x executed results, and the unit
    # tests' code coverage when its report is given (as verify takes it).
    verification_path = out_dir / "verification.yml"
    data = write_verification_file(dhf_dir, allure_results_dir, verification_path, unit_coverage_report)
    kept_coverage = None
    if unit_coverage_report is not None:  # the report itself, kept with the evidence
        kept_coverage = "unit-coverage" + (Path(unit_coverage_report).suffix or ".txt")
        shutil.copyfile(unit_coverage_report, out_dir / kept_coverage)

    # 2. The rendered traceability matrix (generated, never hand-edited).
    import jinja2
    import yaml

    from rdm.kernel.util import load_yaml
    from rdm.publishing.render import render_template_to_file

    matrix_path = out_dir / MATRIX_DOC
    config_file = dhf_dir / "config.yml"
    config = load_yaml(config_file) if config_file.exists() else {}
    context = {"verification": yaml.safe_load(verification_path.read_text())}
    with matrix_path.open("w", encoding="utf-8") as handle:
        render_template_to_file(config, template.name, context, handle,
                                loaders=[jinja2.FileSystemLoader(str(template.parent))])

    # 3. The executed results themselves: every result and container, and each
    # attachment they name (on the test, its steps, or a fixture) -- the
    # evidence behind each verdict, kept past CI artifact retention.
    copied, missing = copy_results(Path(allure_results_dir), out_dir / "allure-results")

    # 4. The verification report: the runs behind each verdict, as a PDF (DI-64).
    try:
        write_report(dhf_dir, Path(allure_results_dir), out_dir / REPORT_PDF, verification=data)
        report = REPORT_PDF
    except ReportUnavailable as error:
        report = f"not rendered: {error}"
    except Exception as error:  # a result the layout cannot set: the bundle keeps the reason, not a crash
        report = f"not rendered: {type(error).__name__}: {error}"

    # 5. The manifest describing what this bundle contains.
    summary = data["summary"]
    manifest = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dhf": str(dhf_dir),
        "design_inputs": summary["total"],
        "verified": summary["verified"],
        "failed": summary["failed"],
        "untested": summary["untested"],
        "verification_report": report,
        "missing_attachments": missing,
        "unreadable": data["unreadable"],
        "unit_coverage_report": kept_coverage,
        "files": sorted(
            [name for name in ("verification.yml", MATRIX_DOC, REPORT_PDF, kept_coverage or "")
             if name and (out_dir / name).is_file()]
            + [f"allure-results/{name}" for name in copied]
        ),
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def evidence_bundle_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    output: Path | None = None,
    unit_coverage_report: Path | None = None,
) -> int:
    """Run `rdm story evidence-bundle --dhf … --allure-results … -o <dir>`."""
    dhf = Path(dhf_dir or "dhf").resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    if not allure_results_dir or not Path(allure_results_dir).exists():
        print("Error: --allure-results <dir> is required (run the acceptance suite first)")
        return 2
    out = Path(output or "release-evidence")
    try:
        manifest = evidence_bundle(dhf, Path(allure_results_dir), out, unit_coverage_report)
    except ValueError as error:
        print(f"Error: {error}")
        return 2
    print(f"Wrote release evidence bundle to {out}:")
    print(f"  design inputs : {manifest['verified']}/{manifest['design_inputs']} verified "
          f"({manifest['failed']} failed, {manifest['untested']} untested)")
    print(f"  report        : {manifest['verification_report']}")
    print(f"  files         : {len(manifest['files'])} + manifest.json")
    if manifest["unreadable"]:
        print(f"  unreadable    : {', '.join(manifest['unreadable'])} (could hold a failed run)")
    return 0
