"""Acceptance test for DI-27's replay scope (see dhf/).

Replay re-executes the *killing* probes only: a probe recorded as SURVIVED
(an equivalent mutant, or a documented gap) is documentation, not a claim, so
it is neither replayed nor counted as a replay failure. Kept in its own module
so strengthening DI-27 does not re-open the module-scope reviews of the other
inputs tested in `test_gating.py`. Skips cleanly if allure-pytest is not
installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.story_audit.design_gate import record_verdict, replay_probes, run_faithfulness_gate
from tests.acceptance.test_gating import _TAGGED_TEST, _mini_record

allure = pytest.importorskip("allure")


@allure.story("DI-27")
@allure.label("output", "rdm/story_audit/design_gate.py")
def test_replay_runs_only_killing_probes(tmp_path: Path, monkeypatch) -> None:
    """DI-27: replay re-executes recorded KILLED probes; recorded SURVIVED
    probes are skipped -- not replayed and not reported as failures."""
    dhf = _mini_record(tmp_path, _TAGGED_TEST)
    killed = {"file": "impl.py", "find": 'VALUE = "good"', "replace": 'VALUE = "bad"',
              "test": "test_value_is_good", "result": "KILLED"}
    # A mutation the test cannot see: replaying it would SURVIVE.
    survived = {"file": "impl.py", "find": 'VALUE = "good"', "replace": 'VALUE = "good"  # noop',
                "test": "test_value_is_good", "result": "SURVIVED"}
    record_verdict(dhf, "DI-1", "faithful", reviewer="r2", rationale="one kill, one documented survivor",
                   probes=[killed, survived])
    monkeypatch.chdir(tmp_path)
    assert replay_probes(run_faithfulness_gate(dhf)) == (1, 1, [])
