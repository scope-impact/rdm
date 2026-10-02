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

from rdm.record.git import web_url  # noqa: E402
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


@allure.story("DI-57")
@allure.label("output", "rdm/pytest_plugin.py")
def test_runs_are_labelled_from_the_record(tmp_path: Path) -> None:
    """DI-57: epics from the input's user needs, the feature from its context,
    links to the Markdown that declares it (design document, V&V plan, risk
    document) at the tested commit, critical severity when it controls a risk,
    and the requirement text attached — from the record,
    for tagged tests only."""
    repo = _project(tmp_path)
    commit = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True,
                            text=True).stdout.strip()
    results = _run(repo)
    alarm, log = results["test_alarm"], results["test_log"]
    attach("test_alarm result labels and links", {"labels": alarm["labels"], "links": alarm.get("links")})
    with verification_step("epics are the user needs the design input traces to"):
        assert _labels(alarm, "epic") == ["UN-1", "UN-2"] and _labels(log, "epic") == ["UN-2"]
    with verification_step("the feature is its bounded context; the story stays the design input"):
        assert _labels(alarm, "feature") == ["alarms"] and _labels(alarm, "story") == ["DI-1"]
    blob = f"https://github.com/acme/device/blob/{commit}/sw/dhf/documents"
    with verification_step("links to the Markdown that declares it — design document, V&V plan per user need, "
                           "risk document per risk it controls — one per document, at the tested commit"):
        assert alarm["links"] == [
            {"type": "link", "name": "DI-1 in sw/dhf/documents/design/alarms.md", "url": f"{blob}/design/alarms.md"},
            {"type": "link", "name": "UN-1, UN-2 in sw/dhf/documents/vv.md", "url": f"{blob}/vv.md"},
            {"type": "link", "name": "RISK-A-1 in sw/dhf/documents/risk/rmf.md", "url": f"{blob}/risk/rmf.md"}]
        assert [link["name"] for link in log["links"]] == [
            "DI-2 in sw/dhf/documents/design/alarms.md", "UN-2 in sw/dhf/documents/vv.md"]
    with verification_step("critical severity when the input controls a risk, and not otherwise"):
        assert _labels(alarm, "severity") == ["critical"]
        assert _labels(log, "severity") in ([], ["normal"])
    with verification_step("the requirement text is attached to the result"):
        names = {a["name"]: a["source"] for a in alarm["attachments"]}
        text = (repo.parent / "allure" / names["requirement DI-1"]).read_text()
        assert text == "DI-1 (alarms): The device shall alarm.\nTraces to: UN-1, UN-2\n"
    with verification_step("an undeclared id or an untagged test gets nothing from the record"):
        for name in ("test_undeclared", "test_untagged"):
            assert not _labels(results[name], "epic") and not _labels(results[name], "feature")
            assert not results[name].get("links") and not results[name].get("attachments")
    with verification_step("remotes become browsable URLs"):
        assert web_url("git@github.com:acme/device.git") == "https://github.com/acme/device"
        assert web_url("https://token@github.com/acme/device.git") == "https://github.com/acme/device"
        assert web_url("ssh://git@gitlab.example/team/device") == "https://gitlab.example/team/device"
        assert web_url(None) is None


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
