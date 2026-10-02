"""Tests for `rdm story verify` data generation and matrix rendering."""

from __future__ import annotations

from pathlib import Path










def test_matrix_template_renders_with_verification_context(tmp_path: Path) -> None:
    # The shipped template must render the matrix when given verification data.
    import jinja2

    from rdm.publishing.render import render_template_to_string

    template_dir = Path(__file__).resolve().parents[1] / "rdm" / "specification" / "init_files" / "documents"
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
