"""Acceptance tests for the gating context's newer design inputs (see dhf/).

DI-26 (design-gate-only hooks default), DI-27 (replayable probes + report
filter), and DI-28 (per-verdict hash scope), tagged with `@allure.story`,
exercising the real hooks installer and faithfulness engine. The earlier
gating inputs (DI-2/3/19/20/21) are verified in their own test modules.

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from rdm.hooks import install_hooks
from rdm.record import faithfulness
from rdm.story_audit.design_gate import (
    record_verdict,
    replay_probes,
    run_faithfulness_gate,
    story_faithfulness_command,
)

allure = pytest.importorskip("allure")


@allure.story("DI-26")
@allure.label("output", "rdm/hooks.py")
def test_hooks_installs_design_gate_only_by_default(tmp_path: Path) -> None:
    """DI-26: default install is the design-gate pre-commit hook only; the
    issue-reference hooks land solely with the explicit flag."""
    default_dest = tmp_path / "default"
    install_hooks(str(default_dest))
    assert os.access(default_dest / "pre-commit", os.X_OK)
    assert not (default_dest / "commit-msg").exists()
    assert not (default_dest / "prepare-commit-msg").exists()

    optin_dest = tmp_path / "optin"
    install_hooks(str(optin_dest), with_issue_hooks=True)
    for name in ("pre-commit", "commit-msg", "prepare-commit-msg"):
        assert os.access(optin_dest / name, os.X_OK)


def _mini_record(tmp_path: Path, test_body: str) -> Path:
    """A minimal DHF (one design input) + tests dir + probed implementation."""
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "design" / "core.md").write_text(
        "---\nid: SDS-C-001\nkind: design\ncontext: core\n"
        "design_inputs:\n  - id: DI-1\n"
        '    text: "the value is good"\n    traces_to: []\n---\n\n# Core\n'
    )
    (tmp_path / "impl.py").write_text('VALUE = "good"\n')
    tests = tmp_path / "tests"
    tests.mkdir()
    (tests / "test_core.py").write_text(test_body)
    return dhf


_TAGGED_TEST = '''import pathlib

import allure


def _load_value():
    text = pathlib.Path(__file__).parents[1].joinpath("impl.py").read_text()
    return "good" if 'VALUE = "good"' in text else "bad"


@allure.story("DI-1")
def test_value_is_good():
    assert _load_value() == "good"
'''


@allure.story("DI-27")
@allure.label("output", "rdm/story_audit/design_gate.py")
def test_verdicts_carry_replayable_probes(tmp_path: Path, monkeypatch, capsys) -> None:
    """DI-27: probes are recorded as structured data; replay re-executes the
    killing probes and fails when one no longer kills; --stale filters the
    report to non-faithful inputs."""
    dhf = _mini_record(tmp_path, _TAGGED_TEST)
    probe = {"file": "impl.py", "find": 'VALUE = "good"', "replace": 'VALUE = "bad"',
             "test": "test_value_is_good", "result": "KILLED"}
    out = record_verdict(dhf, "DI-1", "faithful", reviewer="r2", rationale="probe killed",
                         probes=[probe])

    # Structured data, on disk, exactly as recorded.
    assert json.loads(out.read_text())["probes"] == [probe]

    # Replay from the repo the record lives in: the probe still kills.
    monkeypatch.chdir(tmp_path)
    report = run_faithfulness_gate(dhf)
    replayed, killed, failures = replay_probes(report)
    assert (replayed, killed, failures) == (1, 1, [])

    # Weaken the test so the mutation no longer matters: replay must fail.
    (tmp_path / "tests" / "test_core.py").write_text(
        _TAGGED_TEST.replace('assert _load_value() == "good"', "assert True")
    )
    record_verdict(dhf, "DI-1", "faithful", reviewer="r2", rationale="re-pinned",
                   probes=[probe])  # re-pin so the verdict itself is current
    weak_report = run_faithfulness_gate(dhf)
    _, _, failures = replay_probes(weak_report)
    assert len(failures) == 1 and "SURVIVES" in failures[0]

    # --stale filters the report to the non-faithful worklist.
    (dhf / "faithfulness" / "DI-1-faithfulness.json").unlink()
    capsys.readouterr()
    story_faithfulness_command(dhf_dir=dhf, stale_only=True)
    shown = capsys.readouterr().out
    assert "DI-1: unreviewed" in shown


@allure.story("DI-28")
@allure.label("output", "rdm/record/faithfulness.py")
def test_verdict_hash_scope_module_default_function_selectable(tmp_path: Path) -> None:
    """DI-28: module scope is the default (a helper edit re-opens the review);
    function scope is selectable; a legacy verdict without the field is honored
    as function-scoped."""
    dhf = _mini_record(tmp_path, _TAGGED_TEST)
    test_file = tmp_path / "tests" / "test_core.py"

    def _edit_helper() -> None:
        test_file.write_text(test_file.read_text().replace(
            'return "good" if', 'return "good"  if'))  # helper-only change

    # Default (module) scope: editing the untagged helper makes it stale.
    record_verdict(dhf, "DI-1", "faithful", reviewer="r2", rationale="module pin")
    assert json.loads((dhf / "faithfulness" / "DI-1-faithfulness.json").read_text())["hash_scope"] == "module"
    assert run_faithfulness_gate(dhf).by_id["DI-1"].status == faithfulness.FAITHFUL
    _edit_helper()
    assert run_faithfulness_gate(dhf).by_id["DI-1"].status == faithfulness.STALE

    # Function scope, selected explicitly: the same helper edit does not stale.
    record_verdict(dhf, "DI-1", "faithful", reviewer="r2", rationale="function pin",
                   hash_scope="function")
    _edit_helper()
    assert run_faithfulness_gate(dhf).by_id["DI-1"].status == faithfulness.FAITHFUL

    # Legacy verdict (no hash_scope field) is honored as function-scoped.
    from rdm.record.allure import find_tests_dir
    from rdm.record.sdd import design_inputs

    legacy_hash = faithfulness.current_hashes(
        design_inputs(dhf), find_tests_dir(dhf), scope="function")["DI-1"]
    (dhf / "faithfulness" / "DI-1-faithfulness.json").write_text(json.dumps({
        "design_input": "DI-1", "verdict": "faithful", "reviewer": "r2",
        "rationale": "legacy record", "test_hash": legacy_hash,
    }))
    assert run_faithfulness_gate(dhf).by_id["DI-1"].status == faithfulness.FAITHFUL
