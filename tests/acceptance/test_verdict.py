"""Acceptance test for the verdict-recorder design input (see dhf/).

Acceptance criterion ("live BDD") for DI-20, tagged `@allure.story`, over the
real `record_verdict` (the logic behind `rdm story verdict`).

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.record import faithfulness
from rdm.record.allure import find_tests_dir
from rdm.record.sdd import design_inputs
from rdm.story_audit.design_gate import record_verdict
from tests.util import write_design_doc

allure = pytest.importorskip("allure")


def _dhf(tmp_path: Path) -> Path:
    docs = tmp_path / "dhf" / "documents"
    docs.mkdir(parents=True)
    write_design_doc(docs / "design", "core", design_inputs=(("DI-1", []),))
    (tmp_path / "tests").mkdir()  # empty -> isolate the test-source hash

    return tmp_path / "dhf"


@allure.story("DI-20")
@allure.label("output", "rdm/story_audit/design_gate.py")
def test_records_verdict_hash_pinned_and_rejects_unknown(tmp_path: Path) -> None:
    """DI-20: records a verdict pinned to the current test source (so it reads
    faithful immediately), and returns None for an undeclared design input."""
    dhf = _dhf(tmp_path)

    out = record_verdict(dhf, "DI-1", "faithful", reviewer="r", rationale="exercises the input")
    assert out is not None and out.is_file()

    # The written verdict is pinned to the CURRENT hash, so the gate reads it as
    # faithful straight away (proves the pin uses the live source, not a guess).
    report = faithfulness.reconcile(design_inputs(dhf), dhf / "faithfulness", find_tests_dir(dhf))
    assert report.faithful == ["DI-1"]

    # An undeclared design input is rejected (no file written, None returned).
    assert record_verdict(dhf, "DI-404", "faithful", reviewer="r", rationale="x") is None
