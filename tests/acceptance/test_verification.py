"""Acceptance test for the verification context's DI-30 (see dhf/).

The release evidence bundle: the retained artifact set (verification data,
rendered matrix, manifest) produced from the record. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.publishing.bundle import evidence_bundle, evidence_bundle_command

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402


def _mini_release(tmp_path: Path) -> tuple[Path, Path]:
    """A minimal DHF with one verified design input, a matrix template, and a
    passing Allure result."""
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "design" / "core.md").write_text(
        "---\nid: SDS-C-001\nkind: design\ncontext: core\n"
        "design_inputs:\n  - id: DI-1\n"
        '    text: "a requirement"\n    traces_to: [UN-001]\n---\n\n# Core\n'
    )
    (dhf / "documents" / "vv_plan.md").write_text(
        "---\nuser_needs:\n  - {id: UN-001, text: 'a need'}\n---\n"
    )
    (dhf / "documents" / "traceability_matrix.md").write_text(
        "---\nid: TM-001\n---\n# Matrix\n"
        "{% if verification is defined %}"
        "total={{ verification.summary.total }} verified={{ verification.summary.verified }}\n"
        "{% for group in verification.groups %}{% for di in group.design_inputs %}"
        "row:{{ di.design_input }}:{{ di.status }}\n"
        "{% endfor %}{% endfor %}{% endif %}"
    )
    (dhf / "config.yml").write_text("md_extensions: []\n")
    results = tmp_path / "allure-results"
    results.mkdir()
    (results / "t1-result.json").write_text(json.dumps(
        {"name": "t1", "status": "passed",
         "labels": [{"name": "story", "value": "DI-1"}],
         "attachments": [{"name": "gate output", "source": "a1-attachment.txt", "type": "text/plain"},
                         {"name": "linked", "source": "linked-attachment.txt", "type": "text/plain"},
                         {"name": "lost", "source": "gone-attachment.txt", "type": "text/plain"}],
         "steps": [{"name": "step 1", "status": "passed",
                    "attachments": [{"name": "graph", "source": "a2-attachment.json", "type": "application/json"}]}]}
    ))
    (results / "a1-attachment.txt").write_text("Release gate PASSED\n")
    (results / "a2-attachment.json").write_text("{}")
    (results / "c1-container.json").write_text(json.dumps(
        {"children": ["t1"], "befores": [{"name": "fixture", "attachments": [
            {"name": "setup log", "source": "a3-attachment.txt", "type": "text/plain"}]}]}))
    (results / "a3-attachment.txt").write_text("set up\n")
    (results / "stray-attachment.txt").write_text("not referenced by any result\n")
    (results / "linked-attachment.txt").symlink_to(tmp_path / "dhf" / "config.yml")  # a file outside the results
    (results / "executor.json").write_text('{"name": "local", "type": "local"}')  # the plugin's (DI-65)
    (results / "environment.properties").write_text("python=CPython 3.13\n")
    return dhf, results


@allure.story("DI-30")
@allure.label("output", "rdm/publishing/bundle.py")
def test_evidence_bundle_writes_the_retained_release_set(tmp_path: Path) -> None:
    """DI-30: the bundle contains the verification data, the rendered matrix,
    the executed results with their attachments and containers, and a manifest
    that agrees with them."""
    dhf, results = _mini_release(tmp_path)
    out = tmp_path / "release-evidence"

    manifest = evidence_bundle(dhf, results, out)

    with verification_step("Verification data, from the executed results"):
        assert (out / "verification.yml").is_file()

    with verification_step("The matrix is RENDERED (data in, template markers out)"):
        matrix = (out / "traceability_matrix.md").read_text()
        assert "total=1 verified=1" in matrix
        assert "row:DI-1:verified" in matrix
        assert "{%" not in matrix

    with verification_step("The manifest describes exactly what was bundled"):
        on_disk = json.loads((out / "manifest.json").read_text())
        assert on_disk == manifest
        assert manifest["design_inputs"] == 1 and manifest["verified"] == 1
        assert "faithfulness_verdicts" not in manifest
    # The executed results ride along: every plain file of the results directory
    # (results, containers, attachments, the run's executor and environment), so
    # the report's digest over them can be checked from the bundle.
    bundled = {"allure-results/" + n for n in
               ("t1-result.json", "c1-container.json", "a1-attachment.txt", "a2-attachment.json", "a3-attachment.txt",
                "executor.json", "environment.properties", "stray-attachment.txt")}
    # The verification report (DI-64) is in it when Typst is available, and the manifest says which.
    report = {"verification_report.pdf"} if manifest["verification_report"] == "verification_report.pdf" else set()
    assert set(manifest["files"]) == {"verification.yml", "traceability_matrix.md"} | bundled | report
    assert (out / "allure-results" / "a1-attachment.txt").read_text() == "Release gate PASSED\n"
    with verification_step("a symbolic link in the results is never followed; a missing attachment is listed"):
        assert not (out / "allure-results" / "linked-attachment.txt").exists()
        assert manifest["missing_attachments"] == ["gone-attachment.txt", "linked-attachment.txt"]
    with verification_step("the report's digest over the results can be checked from the bundle"):
        from rdm.publishing.report import build_report, results_sha256
        assert results_sha256(out / "allure-results") == build_report(dhf, results)["results_sha256"]
    with verification_step("a bundle written over an earlier one replaces its results, never mixes them"):
        (results / "stray-attachment.txt").unlink()
        manifest = evidence_bundle(dhf, results, out)
        assert not (out / "allure-results" / "stray-attachment.txt").exists()
        assert "allure-results/stray-attachment.txt" not in manifest["files"]
    with verification_step("a result the report cannot lay out leaves the reason, not a crash"):
        (results / "t2-result.json").write_text(json.dumps(
            {"name": "t2", "status": "passed", "labels": [{"name": "story", "value": "DI-1"}],
             "statusDetails": "not a mapping", "steps": [None], "attachments": [None]}))
        manifest = evidence_bundle(dhf, results, out)
        assert (out / "manifest.json").is_file()
        assert manifest["verification_report"] in ("verification_report.pdf",) or \
            manifest["verification_report"].startswith("not rendered: ")
    with verification_step("an output that would hold the results, or sit inside them, is refused and the results "
                           "kept"):
        kept = sorted(p.name for p in results.iterdir())
        for out_dir in (results.parent, results, results / "bundle"):
            with pytest.raises(ValueError, match="results"):
                evidence_bundle(dhf, results, out_dir)
            assert sorted(p.name for p in results.iterdir()) == kept, out_dir
        assert evidence_bundle_command(dhf, results, results.parent) == 2
    with verification_step("an unreadable result is named in the manifest"):
        (results / "cut-result.json").write_text('{"status": "failed"')
        manifest = evidence_bundle(dhf, results, out)
        assert manifest["unreadable"] == ["cut-result.json"]
