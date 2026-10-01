"""Acceptance test for the verification context's DI-30 (see dhf/).

The release evidence bundle: the retained artifact set (verification data,
rendered matrix, manifest) produced from the record. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.record.bundle import evidence_bundle

allure = pytest.importorskip("allure")


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
         "attachments": [{"name": "gate output", "source": "a1-attachment.txt", "type": "text/plain"}],
         "steps": [{"name": "clause 1", "status": "passed",
                    "attachments": [{"name": "graph", "source": "a2-attachment.json", "type": "application/json"}]}]}
    ))
    (results / "a1-attachment.txt").write_text("Release gate PASSED\n")
    (results / "a2-attachment.json").write_text("{}")
    (results / "c1-container.json").write_text(json.dumps(
        {"children": ["t1"], "befores": [{"name": "fixture", "attachments": [
            {"name": "setup log", "source": "a3-attachment.txt", "type": "text/plain"}]}]}))
    (results / "a3-attachment.txt").write_text("set up\n")
    (results / "stray-attachment.txt").write_text("not referenced by any result\n")
    return dhf, results


@allure.story("DI-30")
@allure.label("output", "rdm/record/bundle.py")
def test_evidence_bundle_writes_the_retained_release_set(tmp_path: Path) -> None:
    """DI-30: the bundle contains the verification data, the rendered matrix,
    the executed results with their attachments and containers, and a manifest
    that agrees with them."""
    dhf, results = _mini_release(tmp_path)
    out = tmp_path / "release-evidence"

    manifest = evidence_bundle(dhf, results, out)

    # Verification data, from the executed results.
    assert (out / "verification.yml").is_file()

    # The matrix is RENDERED (data in, template markers out).
    matrix = (out / "traceability_matrix.md").read_text()
    assert "total=1 verified=1" in matrix
    assert "row:DI-1:verified" in matrix
    assert "{%" not in matrix

    # The manifest describes exactly what was bundled.
    on_disk = json.loads((out / "manifest.json").read_text())
    assert on_disk == manifest
    assert manifest["design_inputs"] == 1 and manifest["verified"] == 1
    assert "faithfulness_verdicts" not in manifest
    # The executed results ride along: results, containers, and every attachment
    # they reference (on the test, its steps, or a fixture) -- nothing else.
    bundled = {"allure-results/" + n for n in
               ("t1-result.json", "c1-container.json", "a1-attachment.txt", "a2-attachment.json", "a3-attachment.txt")}
    assert set(manifest["files"]) == {"verification.yml", "traceability_matrix.md"} | bundled
    assert (out / "allure-results" / "a1-attachment.txt").read_text() == "Release gate PASSED\n"
    assert not (out / "allure-results" / "stray-attachment.txt").exists()
