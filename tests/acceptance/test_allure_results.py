"""Acceptance test for item-03-001 (.pi/spec/test_evidence/spec.feature):
reading Allure results, so that a result RDM cannot read is never counted as
a pass.

Over the real Allure reader, release gate and verify command. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from rdm.evidence import allure as allure_ingest
from rdm.release.gate import REQUIREMENT_FAILED, UNREADABLE_RESULT, run_release_gate
from rdm.release.verify import verify_command
from tests.util import write_allure_result as _allure_result

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_user_needs import _approved_dhf  # noqa: E402


@allure.tag("item-03-001")
@allure.label("output", "rdm/evidence/allure.py")
def test_a_result_that_cannot_be_read_is_never_a_pass(tmp_path: Path) -> None:
    """item-03-001: what cannot be read blocks release and is named by verify;
    a byte-order mark is read; a failed step fails its run; a run tagged with
    an id no requirement has is warned about."""
    dhf = _approved_dhf(tmp_path, ["Release"])
    results = tmp_path / "allure"
    with verification_step("a readable passing result verifies its requirement: the baseline each case spoils"):
        _allure_result(results, "a", "passed", "item-01-001")
        assert run_release_gate(dhf, results).passed
    with verification_step("a result file that cannot be read blocks: it could hold a failed run"):
        for bad in ('{"status": "failed", "labels": [{"name": "tag", "value": "item-01-001"', "[]"):
            (results / "b-result.json").write_text(bad)
            gate = run_release_gate(dhf, results)
            attach("release gate blocking", gate.blocking)
            assert not gate.passed and any(e.name == UNREADABLE_RESULT for e in gate.events)
        (results / "b-result.json").write_bytes(b'{"name": "\xff"}')
        assert not run_release_gate(dhf, results).passed
    with verification_step("a result with a byte-order mark is read, and its failed run counts"):
        failed = '{"status": "failed", "labels": [{"name": "tag", "value": "item-01-001"}]}'
        (results / "b-result.json").write_text("\ufeff" + failed)
        gate = run_release_gate(dhf, results)
        assert not gate.passed and [e.name for e in gate.events if e.blocking] == [REQUIREMENT_FAILED]
    with verification_step("a result that could hold a failed run is unreadable: a status Allure does not write, "
                           "labels that are not name and value text, a symbolic link, JSON nested too deep"):
        story = [{"name": "tag", "value": "item-01-001"}]
        cases = {"capital-status": {"status": "Failed", "labels": story}, "no-status": {"labels": story},
                 "null-status": {"status": None, "labels": story},
                 "labels-text": {"status": "failed", "labels": "tag=item-01-001"},
                 "tag-list": {"status": "failed", "labels": [{"name": "tag", "value": ["item-01-001"]}]}}
        for name, data in cases.items():
            case = tmp_path / f"unreadable-{name}"
            _allure_result(case, "a", "passed", "item-01-001")
            (case / "b-result.json").write_text(json.dumps(data))
            gate = run_release_gate(dhf, case)
            attach(f"{name} blocking", gate.blocking)
            assert [e.name for e in gate.events if e.blocking] == [UNREADABLE_RESULT], (name, gate.blocking)
        case = tmp_path / "unreadable-link"
        _allure_result(case, "a", "passed", "item-01-001")
        (tmp_path / "elsewhere.json").write_text(json.dumps({"status": "passed", "labels": story}))
        (case / "b-result.json").symlink_to(tmp_path / "elsewhere.json")
        assert [e.name for e in run_release_gate(dhf, case).events if e.blocking] == [UNREADABLE_RESULT]
        case = tmp_path / "unreadable-deep"
        _allure_result(case, "a", "passed", "item-01-001")
        (case / "b-result.json").write_text("[" * 100000 + "]" * 100000)
        assert [e.name for e in run_release_gate(dhf, case).events if e.blocking] == [UNREADABLE_RESULT]
    with verification_step("a run tagged with a mistyped id is an orphan warning, never silence"):
        case = tmp_path / "mistyped"
        _allure_result(case, "a", "passed", "item-01-001")
        mistyped = ("Item-01-001", "item_01_001", "item\u201301-001", "item-01-777")
        for i, tag in enumerate(mistyped):
            _allure_result(case, f"m{i}", "failed", tag)
        gate = run_release_gate(dhf, case)
        attach("warnings", gate.warnings)
        assert gate.passed, gate.blocking
        assert {f"Allure result tag {tag} matches no requirement" for tag in mistyped} <= set(gate.warnings), \
            gate.warnings


    with verification_step("a run whose step failed or broke has failed, though the run says passed"):
        stepped = tmp_path / "allure-steps"
        stepped.mkdir()
        for name, status in (("s", "failed"), ("t", "broken")):
            (stepped / f"{name}-result.json").write_text(json.dumps(
                {"name": name, "status": "passed", "labels": [{"name": "tag", "value": f"item-09-00{1 if name == 's' else 2}"}],
                 "steps": [{"name": "outer", "status": "passed", "steps": [{"name": "inner", "status": status}]}]}))
        report = allure_ingest.reconcile({"item-09-001", "item-09-002"}, stepped)
        assert report.failed == ["item-09-001", "item-09-002"], report.by_id

    with verification_step("verify names a result file it cannot read, and exits non-zero, as the gate blocks"):
        (results / "x-result.json").write_text('{"status": "failed", "labels": [')
        out_file = tmp_path / "verification.yml"
        assert verify_command(dhf, results, out_file) == 1
        data = yaml.safe_load(out_file.read_text())
        assert data["unreadable"] == ["x-result.json"]


