"""Tests for `rdm story verify` data generation and matrix rendering."""

from __future__ import annotations

import json
from pathlib import Path

import yaml

from rdm.record.verify import build_verification, write_verification_file


def _sdd(dhf: Path, user_needs: list[str]) -> None:
    docs = dhf / "documents"
    docs.mkdir(parents=True, exist_ok=True)
    needs = "[" + ", ".join(user_needs) + "]"
    (docs / "software_design_specification.md").write_text(
        f"---\nid: SDS-001\ntitle: SDD\nuser_needs: {needs}\n---\n\nbody\n"
    )


def _result(results: Path, name: str, status: str, *ids: str) -> None:
    results.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "story", "value": i} for i in ids]
    (results / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


def test_build_verification_classifies_each_need(tmp_path: Path) -> None:
    dhf = tmp_path / "dhf"
    _sdd(dhf, ["UN-001", "UN-002", "UN-003"])
    results = tmp_path / "allure-results"
    _result(results, "a", "passed", "UN-001")
    _result(results, "b", "failed", "UN-002")
    _result(results, "c", "passed", "UN-777")  # orphan

    data = build_verification(dhf, results)

    assert data["summary"] == {
        "verified": 1,
        "failed": 1,
        "untested": 1,
        "total": 3,
        "results_found": 3,
    }
    by_id = {n["user_need"]: n["status"] for n in data["needs"]}
    assert by_id == {"UN-001": "verified", "UN-002": "failed", "UN-003": "untested"}
    assert data["orphans"] == ["UN-777"]


def test_write_verification_file_is_loadable_yaml(tmp_path: Path) -> None:
    dhf = tmp_path / "dhf"
    _sdd(dhf, ["UN-001"])
    results = tmp_path / "allure-results"
    _result(results, "a", "passed", "UN-001")

    out = tmp_path / "verification.yml"
    write_verification_file(dhf, results, out)

    loaded = yaml.safe_load(out.read_text())
    assert loaded["needs"][0]["user_need"] == "UN-001"
    assert loaded["needs"][0]["status"] == "verified"


def test_matrix_template_renders_with_verification_context(tmp_path: Path) -> None:
    # The shipped template must render the matrix when given verification data.
    import jinja2

    from rdm.render import render_template_to_string

    template_dir = Path(__file__).resolve().parents[1] / "rdm" / "init_files" / "documents"
    verification = {
        "summary": {"verified": 1, "failed": 1, "untested": 0, "total": 2, "results_found": 2},
        "needs": [
            {"user_need": "UN-001", "status": "verified", "passed": 1, "failed": 0,
             "skipped": 0, "tests": ["test_a"]},
            {"user_need": "UN-002", "status": "failed", "passed": 0, "failed": 1,
             "skipped": 0, "tests": ["test_b"]},
        ],
        "orphans": ["UN-777"],
    }
    context = {"verification": verification, "device": {"name": "DEVICE"}}

    loaders = [jinja2.FileSystemLoader(str(template_dir))]
    output = render_template_to_string({}, "traceability_matrix.md", context, loaders=loaders)

    assert "UN-001" in output and "verified" in output
    assert "UN-002" in output and "failed" in output
    assert "UN-777" in output  # orphan section
    assert "TODO" not in output  # the unpopulated branch must not appear
