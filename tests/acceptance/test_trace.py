"""Acceptance test for the trace-query design input (see dhf/).

Acceptance criterion ("live BDD") for DI-18, tagged `@allure.story`, over the
real `build_trace` — the read-only traceability audit query.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.story_audit.design_gate import build_trace
from tests.util import write_allure_result as _allure_result
from tests.util import write_design_doc

allure = pytest.importorskip("allure")


def _dhf(tmp_path: Path) -> Path:
    """A small DHF: one user need refined by a design input owned by 'core' and
    realised by 'edge'."""
    docs = tmp_path / "dhf" / "documents"
    docs.mkdir(parents=True)
    (docs / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\nuser_needs:\n  - {id: UN-001, text: a need}\n---\n\nplan\n"
    )
    write_design_doc(docs / "design", "core", satisfies=("UN-001",),
                     design_inputs=(("DI-1", ["UN-001"]),))
    write_design_doc(docs / "design", "edge", satisfies=("UN-001",), realises=("DI-1",))
    return tmp_path / "dhf"


@allure.story("DI-18")
@allure.label("output", "rdm/story_audit/design_gate.py")
def test_trace_user_need_and_design_input(tmp_path: Path) -> None:
    """DI-18: trace forward (need → inputs) and backward (input → need/owner/realisers)."""
    dhf = _dhf(tmp_path)

    # Forward: the user need lists the design inputs that refine it.
    fwd = build_trace(dhf, "UN-001")
    assert fwd["kind"] == "user_need"
    assert [di["design_input"] for di in fwd["design_inputs"]] == ["DI-1"]
    assert fwd["design_inputs"][0]["owned_by"] == "core"

    # Backward: the design input names its need, owner, and realisers.
    back = build_trace(dhf, "DI-1")
    assert back["kind"] == "design_input"
    assert back["traces_to"] == ["UN-001"]
    assert back["owned_by"] == "core"
    assert back["realised_by"] == ["edge"]

    # Unknown target is reported, not crashed.
    assert "error" in build_trace(dhf, "DI-404")

    # With executed results, the slice carries the design input's STATUS and the
    # verifying TESTS (the clause that was previously untested).
    results = tmp_path / "allure"
    _allure_result(results, "the_test", "passed", "DI-1")
    enriched = build_trace(dhf, "DI-1", allure_results_dir=results)
    assert enriched["status"] == "verified"
    assert enriched["tests"] == ["the_test"]
