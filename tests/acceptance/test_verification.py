"""Acceptance test for the verification context's DI-30 (see dhf/).

The release evidence bundle: the retained artifact set (verification data,
rendered matrix, verdicts, manifest) produced from the record. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.record.bundle import evidence_bundle

allure = pytest.importorskip("allure")


def _mini_release(tmp_path: Path) -> tuple[Path, Path]:
    """A minimal DHF with one verified design input, one verdict, a matrix
    template, and a passing Allure result."""
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
    (dhf / "faithfulness").mkdir()
    (dhf / "faithfulness" / "DI-1-faithfulness.json").write_text(json.dumps(
        {"design_input": "DI-1", "verdict": "faithful", "reviewer": "r2",
         "rationale": "x", "test_hash": "sha256:irrelevant-here"}
    ))
    results = tmp_path / "allure-results"
    results.mkdir()
    (results / "t1-result.json").write_text(json.dumps(
        {"name": "t1", "status": "passed",
         "labels": [{"name": "story", "value": "DI-1"}]}
    ))
    return dhf, results


@allure.story("DI-30")
@allure.label("output", "rdm/record/bundle.py")
def test_evidence_bundle_writes_the_retained_release_set(tmp_path: Path) -> None:
    """DI-30: the bundle contains the verification data, the rendered matrix,
    the faithfulness verdicts, and a manifest that agrees with them."""
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

    # The verdicts travel with the bundle.
    assert (out / "faithfulness" / "DI-1-faithfulness.json").is_file()

    # The manifest describes exactly what was bundled.
    on_disk = json.loads((out / "manifest.json").read_text())
    assert on_disk == manifest
    assert manifest["design_inputs"] == 1 and manifest["verified"] == 1
    assert manifest["faithfulness_verdicts"] == 1
    assert set(manifest["files"]) == {
        "verification.yml", "traceability_matrix.md", "faithfulness/DI-1-faithfulness.json",
    }
