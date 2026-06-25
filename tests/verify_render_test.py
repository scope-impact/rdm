"""Tests for `rdm story verify` data generation and matrix rendering."""

from __future__ import annotations

from pathlib import Path

import yaml

from rdm.record.verify import build_verification, write_verification_file
from tests.util import write_allure_result as _result


def _dhf(dhf: Path, inputs: list[tuple[str, list[str]]], user_needs: list[str]) -> None:
    """Write a DHF with a design-input registry and a user-need registry.

    `inputs` is ``(DI-id, [user needs it traces_to])``; verification is anchored
    on the design inputs, grouped under the user needs they trace to.
    """
    docs = dhf / "documents"
    docs.mkdir(parents=True, exist_ok=True)
    rows = "\n".join(
        f"  - {{id: {di}, text: {di} requirement, traces_to: [{', '.join(traces)}]}}"
        for di, traces in inputs
    )
    (docs / "design_input.md").write_text(
        f"---\nid: DI-001\ndesign_inputs:\n{rows}\n---\n\nbody\n"
    )
    needs = "\n".join(f"  - {{id: {n}, text: {n}}}" for n in user_needs)
    (docs / "verification_and_validation_plan.md").write_text(
        f"---\nid: VVP-001\nuser_needs:\n{needs}\n---\n\nplan\n"
    )


def test_build_verification_classifies_each_design_input(tmp_path: Path) -> None:
    dhf = tmp_path / "dhf"
    _dhf(
        dhf,
        inputs=[("DI-1", ["UN-001"]), ("DI-2", ["UN-001"]), ("DI-3", ["UN-002"])],
        user_needs=["UN-001", "UN-002"],
    )
    results = tmp_path / "allure-results"
    _result(results, "a", "passed", "DI-1")
    _result(results, "b", "failed", "DI-2")
    _result(results, "c", "passed", "DI-777")  # orphan

    data = build_verification(dhf, results)

    assert data["summary"] == {
        "verified": 1,
        "failed": 1,
        "untested": 1,
        "total": 3,
        "results_found": 3,
    }
    by_id = {
        di["design_input"]: di["status"]
        for group in data["groups"]
        for di in group["design_inputs"]
    }
    assert by_id == {"DI-1": "verified", "DI-2": "failed", "DI-3": "untested"}
    # DI-1 and DI-2 group under UN-001; DI-3 under UN-002.
    groups = {g["user_need"]: [di["design_input"] for di in g["design_inputs"]] for g in data["groups"]}
    assert groups == {"UN-001": ["DI-1", "DI-2"], "UN-002": ["DI-3"]}
    assert data["orphans"] == ["DI-777"]


def test_write_verification_file_is_loadable_yaml(tmp_path: Path) -> None:
    dhf = tmp_path / "dhf"
    _dhf(dhf, inputs=[("DI-1", ["UN-001"])], user_needs=["UN-001"])
    results = tmp_path / "allure-results"
    _result(results, "a", "passed", "DI-1")

    out = tmp_path / "verification.yml"
    write_verification_file(dhf, results, out)

    loaded = yaml.safe_load(out.read_text())
    group = loaded["groups"][0]
    assert group["user_need"] == "UN-001"
    assert group["design_inputs"][0]["design_input"] == "DI-1"
    assert group["design_inputs"][0]["status"] == "verified"


def test_matrix_template_renders_with_verification_context(tmp_path: Path) -> None:
    # The shipped template must render the matrix when given verification data.
    import jinja2

    from rdm.render import render_template_to_string

    template_dir = Path(__file__).resolve().parents[1] / "rdm" / "init_files" / "documents"
    verification = {
        "summary": {"verified": 1, "failed": 1, "untested": 0, "total": 2, "results_found": 2},
        "groups": [
            {
                "user_need": "UN-001",
                "design_inputs": [
                    {"design_input": "DI-1", "status": "verified", "passed": 1, "failed": 0,
                     "skipped": 0, "tests": ["test_a"], "outputs": ["SDS-1"]},
                    {"design_input": "DI-2", "status": "failed", "passed": 0, "failed": 1,
                     "skipped": 0, "tests": ["test_b"], "outputs": []},
                ],
            },
        ],
        "orphans": ["DI-777"],
    }
    context = {"verification": verification, "device": {"name": "DEVICE"}}

    loaders = [jinja2.FileSystemLoader(str(template_dir))]
    output = render_template_to_string({}, "traceability_matrix.md", context, loaders=loaders)

    assert "UN-001" in output
    assert "DI-1" in output and "verified" in output
    assert "DI-2" in output and "failed" in output
    assert "DI-777" in output  # orphan section
    assert "TODO" not in output  # the unpopulated branch must not appear
