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
                "user_need": "Alarm", "text": "A nurse is alerted.", "actors": ["Nurse"], "surfaces": ["Alarm"],
                "requirements": [
                    {"requirement": "item-01-001", "text": "the device alarms", "status": "verified", "passed": 1,
                     "failed": 0, "skipped": 0, "tests": ["test_a"], "outputs": ["SDS-1"]},
                    {"requirement": "item-01-002", "text": "the device logs", "status": "failed", "passed": 0,
                     "failed": 1, "skipped": 0, "tests": ["test_b"], "outputs": []},
                ],
            },
        ],
        "orphans": ["item-01-777"],
    }
    context = {"verification": verification, "device": {"name": "DEVICE"}}

    loaders = [jinja2.FileSystemLoader(str(template_dir))]
    output = render_template_to_string({}, "traceability_matrix.md", context, loaders=loaders)

    assert "Alarm" in output and "A nurse is alerted." in output and "Nurse" in output
    assert "item-01-001" in output and "the device alarms" in output and "verified" in output
    assert "item-01-002" in output and "failed" in output
    assert "item-01-777" in output  # orphan section
    assert "TODO" not in output  # the unpopulated branch must not appear
