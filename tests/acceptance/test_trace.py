"""Acceptance test for the trace-query design input (see dhf/).

The test ("live BDD") that verifies DI-18, tagged `@allure.story`, over the
real `build_trace` — the read-only traceability audit query.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.release.gate import build_trace, story_trace_command
from tests.util import write_allure_result as _allure_result
from tests.util import write_design_doc

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402


def _dhf(tmp_path: Path) -> Path:
    """A small DHF: one user need refined by a design input owned by 'core' and
    realised by 'edge'."""
    docs = tmp_path / "dhf" / "documents"
    docs.mkdir(parents=True)
    (docs / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\nuser_needs:\n  - {id: UN-001, text: a need}\n---\n\nplan\n"
    )
    write_design_doc(docs / "design", "core", design_inputs=(("DI-1", ["UN-001"]),))
    write_design_doc(docs / "design", "edge", realises=("DI-1",))
    return tmp_path / "dhf"


@allure.story("DI-18")
@allure.label("output", "rdm/release/gate.py")
def test_trace_user_need_and_design_input(tmp_path: Path, capsys) -> None:
    """DI-18: trace forward (need → inputs) and backward (input → need/owner/realisers)."""
    dhf = _dhf(tmp_path)

    with verification_step("Forward: the user need lists the design inputs that refine it"):
        fwd = build_trace(dhf, "UN-001")
        assert fwd["kind"] == "user_need"
        assert [di["design_input"] for di in fwd["design_inputs"]] == ["DI-1"]
        assert fwd["design_inputs"][0]["owned_by"] == "core"

    with verification_step("Backward: the design input names its need, owner, and realisers"):
        back = build_trace(dhf, "DI-1")
        assert back["kind"] == "design_input"
        assert back["traces_to"] == ["UN-001"]
        assert back["owned_by"] == "core"
        assert back["realised_by"] == ["edge"]

    with verification_step("Unknown target is reported, not crashed"):
        assert "error" in build_trace(dhf, "DI-404")

    # With executed results, the slice carries the design input's STATUS and the
    # verifying TESTS (the part that was previously untested).
    with verification_step("With executed results, the slice carries the design input's STATUS and the verifying "
                           "TESTS (the…"):
        results = tmp_path / "allure"
        _allure_result(results, "the_test", "passed", "DI-1")
        enriched = build_trace(dhf, "DI-1", allure_results_dir=results)
        assert enriched["status"] == "verified"
        assert enriched["tests"] == ["the_test"]

    with verification_step("A failed input's tests are tested by, each named once; an unreadable result is listed; "
                           "a missing results directory is refused, as by the gate"):
        _allure_result(results, "the_test_again", "failed", "DI-1")
        (results / "the_test_again-result.json").write_text(
            (results / "the_test_again-result.json").read_text().replace("the_test_again", "the_test"))
        (results / "cut-result.json").write_text('{"status": "failed"')
        assert story_trace_command("DI-1", dhf, results) == 0
        out = capsys.readouterr().out
        assert "status:      failed" in out and "tested by:   the_test\n" in out and "verified by" not in out, out
        assert "cut-result.json" in out, out
        assert build_trace(dhf, "DI-1", results)["unreadable"] == ["cut-result.json"]
        assert story_trace_command("DI-1", dhf, tmp_path / "resluts") == 2
        assert "results directory not found" in capsys.readouterr().out
