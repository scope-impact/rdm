"""Acceptance test for the mutation-probe design input (see dhf/).

Acceptance criterion ("live BDD") for DI-21, tagged `@allure.story`, over the
real `run_mutation_probe`. Uses an injected stub runner so the probe's mechanics
(apply / observe / restore) are tested without nesting pytest.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.story_audit.mutation import run_mutation_probe

allure = pytest.importorskip("allure")


@allure.story("DI-21")
@allure.label("output", "rdm/story_audit/mutation.py")
def test_mutation_probe_applies_reports_and_restores(tmp_path: Path) -> None:
    """DI-21: the probe applies the mutation while the test runs, reports
    killed/survived from the result, always restores the file, and rejects an
    ambiguous mutation site."""
    src = tmp_path / "m.py"
    original = "VALUE = 1\n"
    src.write_text(original)

    # A runner that "catches" the mutation (tests FAIL) → killed. It also records
    # what the file looked like *while it ran*, proving the mutation was applied.
    seen = {}

    def catching_runner() -> bool:
        seen["during"] = src.read_text()
        return False  # tests failed under the mutation

    res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", catching_runner)
    assert seen["during"] == "VALUE = 2\n"          # mutation was live during the run
    assert res["killed"] and not res["survived"]     # caught
    assert res["restored"] and src.read_text() == original  # always reverted

    # A runner that does NOT catch it (tests pass) → survived (a test hole).
    res2 = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", lambda: True)
    assert res2["survived"] and not res2["killed"]
    assert src.read_text() == original

    # An ambiguous mutation site (text not unique) is rejected; file untouched.
    src.write_text("x = 1\nx = 1\n")
    assert "error" in run_mutation_probe(src, "x = 1", "x = 2", lambda: False)
    assert src.read_text() == "x = 1\nx = 1\n"
