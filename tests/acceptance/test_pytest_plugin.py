"""Acceptance test for the RDM pytest plugin (see .pi/spec/test_evidence).

Over a real pytest run with allure-pytest and the plugin, in a small project:
a git repository with an origin, a spec, a DHF, and tests that claim the
spec's requirements with ``@allure.tag("item-NN-SSS")``. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.util import git_run, write_spec  # noqa: E402


def _project(tmp_path: Path) -> Path:
    repo = tmp_path / "device"
    (repo / "sw" / "dhf").mkdir(parents=True)  # not at the repo root: the spec is found from the repository
    write_spec(repo, (("item-01-001", "The device alarms.", ["Alarm"]), ("item-01-002", "The device logs.", [])),
               {"Alarm": ("alarms", "Nurse", "A nurse is alerted.")}, {"01": "alarms"})
    (repo / "tests").mkdir()
    (repo / "tests" / "test_alarms.py").write_text(
        'import allure\n\n@allure.tag("item-01-001")\ndef test_alarm():\n    pass\n\n'
        '@allure.tag("item-01-002")\ndef test_log():\n    pass\n\n'
        '@allure.tag("item-01-099")\ndef test_undeclared():\n    pass\n\n'
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


@allure.tag("item-03-002")
@allure.label("output", "rdm/pytest_plugin.py")
def test_runs_carry_the_commit_under_test(tmp_path: Path) -> None:
    """Each run of a test that claims a requirement of the spec carries the
    commit under test, and a mark when the working tree had uncommitted changes."""
    repo = _project(tmp_path)
    head = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout.strip()
    clean = _run(repo)
    attach("test_alarm labels, clean tree", clean["test_alarm"]["labels"])
    with verification_step("a run of a test claiming a requirement of the spec carries the commit under test"):
        assert _labels(clean["test_alarm"], "commit") == [head] and _labels(clean["test_log"], "commit") == [head]
    with verification_step("a clean working tree carries no mark"):
        assert not _labels(clean["test_alarm"], "worktree")
    with verification_step("a test claiming no requirement of the spec, or nothing, carries no commit"):
        assert not _labels(clean["test_undeclared"], "commit") and not _labels(clean["test_untagged"], "commit")
    with verification_step("uncommitted changes mark the run"):
        tests = repo / "tests" / "test_alarms.py"
        tests.write_text(tests.read_text() + "\n# an edit not yet committed\n")
        for f in (repo.parent / "allure").glob("*"):
            f.unlink()
        dirty = _run(repo)
        assert _labels(dirty["test_alarm"], "worktree") == ["dirty"]
        assert _labels(dirty["test_alarm"], "commit") == [head]
