"""Acceptance test for the RDM pytest plugin (DI-57, see dhf/).

Tagged `@allure.story("DI-57")`, over a real pytest run with allure-pytest and
the plugin, in a small project: a git repository with an origin, a DHF, a
risk register, and tests tagged with design inputs. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_risk import POLICY  # noqa: E402
from tests.util import git_run  # noqa: E402


def _project(tmp_path: Path) -> Path:
    repo = tmp_path / "device"
    docs = repo / "sw" / "dhf" / "documents"  # not at the repo root: links are repo-relative
    (docs / "design").mkdir(parents=True)
    (docs / "vv.md").write_text("---\nid: VVP-1\nuser_needs:\n  - {id: UN-1, text: a}\n  - {id: UN-2, text: b}\n---\n")
    (docs / "design" / "alarms.md").write_text(
        "---\nid: SDS-ALM\nkind: design\ncontext: alarms\ndesign_inputs:\n"
        "  - {id: DI-1, text: 'The device shall alarm.', traces_to: [UN-1, UN-2]}\n"
        "  - {id: DI-2, text: 'The device shall log.', traces_to: [UN-2]}\n---\n# Alarms\n")
    (docs / "risk").mkdir()
    (docs / "risk" / "rmf.md").write_text("---\n" + yaml.safe_dump({
        "id": "RMF-1", "kind": "risk", "risk_policy": POLICY, "risks": [{
            "id": "RISK-A-1", "category": "safety", "hazard": "h", "situation": "s", "harm": "x",
            "severity": "Serious", "probability": "Possible", "controls": ["DI-1"],
            "residual": {"probability": "Rare"}}]}) + "---\n")
    (repo / "tests").mkdir()
    (repo / "tests" / "test_alarms.py").write_text(
        'import allure\n\n@allure.story("DI-1")\ndef test_alarm():\n    pass\n\n'
        '@allure.story("DI-2")\ndef test_log():\n    pass\n\n'
        '@allure.story("DI-99")\ndef test_undeclared():\n    pass\n\n'
        'def test_untagged():\n    pass\n')
    (repo / ".gitignore").write_text("__pycache__/\n")  # what any repository ignores
    git_run(repo, "init")
    git_run(repo, "remote", "add", "origin", "git@github.com:acme/device.git")
    git_run(repo, "add", "-A")
    git_run(repo, "commit", "-m", "device")
    return repo


def _run(repo: Path) -> dict[str, dict]:
    out = repo.parent / "allure"  # outside the repository: results are not part of the commit
    subprocess.run([sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider", "-p", "rdm.pytest_plugin",
                    f"--alluredir={out}", "--rdm-dhf", str(repo / "sw" / "dhf"), "tests"],
                   cwd=repo, check=True, capture_output=True)
    return {(d := json.loads(f.read_text()))["name"]: d for f in out.glob("*-result.json")}


def _labels(result: dict, name: str) -> list[str]:
    return sorted(label["value"] for label in result["labels"] if label["name"] == name)


@allure.story("DI-59")
@allure.label("output", "rdm/pytest_plugin.py")
def test_runs_carry_the_commit_under_test(tmp_path: Path) -> None:
    """DI-59: each run of a test tagged with a declared design input carries the
    commit under test, and a mark when the working tree had uncommitted changes."""
    repo = _project(tmp_path)
    head = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout.strip()
    clean = _run(repo)
    attach("test_alarm labels, clean tree", clean["test_alarm"]["labels"])
    with verification_step("a run of a tagged, declared test carries the commit under test"):
        assert _labels(clean["test_alarm"], "commit") == [head] and _labels(clean["test_log"], "commit") == [head]
    with verification_step("a clean working tree carries no mark"):
        assert not _labels(clean["test_alarm"], "worktree")
    with verification_step("an undeclared id or an untagged test carries no commit"):
        assert not _labels(clean["test_undeclared"], "commit") and not _labels(clean["test_untagged"], "commit")
    with verification_step("uncommitted changes mark the run"):
        tests = repo / "tests" / "test_alarms.py"
        tests.write_text(tests.read_text() + "\n# an edit not yet committed\n")
        for f in (repo.parent / "allure").glob("*"):
            f.unlink()
        dirty = _run(repo)
        assert _labels(dirty["test_alarm"], "worktree") == ["dirty"]
        assert _labels(dirty["test_alarm"], "commit") == [head]
