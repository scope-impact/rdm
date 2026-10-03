"""Acceptance test for the run's executor and environment (DI-65, see dhf/).

Tagged `@allure.story("DI-65")`. A real pytest session runs a tagged test
against a committed record with `rdm.pytest_plugin`, once as GitHub Actions
would and once locally, and the Allure results are read back. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import getpass
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
from importlib.metadata import version
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.util import git_run  # noqa: E402

GITHUB = {"GITHUB_ACTIONS": "true", "GITHUB_SERVER_URL": "https://github.com", "GITHUB_REPOSITORY": "acme/device",
          "GITHUB_RUN_ID": "7001", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_RUN_NUMBER": "42",
          "GITHUB_WORKFLOW": "Design controls", "GITHUB_ACTOR": "octocat", "GITHUB_EVENT_NAME": "pull_request",
          "GITHUB_REF": "refs/pull/5/merge", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64"}


def _project(tmp_path: Path) -> tuple[Path, str]:
    repo = tmp_path / "device"
    (repo / "dhf" / "documents" / "design").mkdir(parents=True)
    (repo / "dhf" / "documents" / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\nuser_needs:\n  - {id: UN-001, text: 'a need'}\n---\n")
    (repo / "dhf" / "documents" / "design" / "alarms.md").write_text(
        "---\nid: SDS-1\nkind: design\ncontext: alarms\ndesign_inputs:\n"
        "  - {id: DI-1, text: 'The device shall alarm.', traces_to: [UN-001]}\n---\n")
    (repo / "test_alarm.py").write_text('import allure\n\n\n@allure.story("DI-1")\ndef test_alarm():\n    pass\n\n\n'
                                        '@allure.story("DI-1")\ndef test_alarm_again():\n    pass\n')
    for args in (["init", "-q"], ["add", "-A"], ["commit", "-qm", "r"]):
        git_run(repo, *args)
    return repo, git_run(repo, "rev-parse", "HEAD")


def _run(repo: Path, results: Path, env: dict[str, str]) -> tuple[dict, dict[str, str]]:
    base = {k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "RUNNER_"))}
    base["PYTHONDONTWRITEBYTECODE"] = "1"  # no __pycache__ in the repository: its worktree stays clean
    done = subprocess.run([sys.executable, "-m", "pytest", "-q", "-p", "rdm.pytest_plugin", "-p", "no:cacheprovider",
                           f"--alluredir={results}", "test_alarm.py"], cwd=repo, env=base | env,
                          capture_output=True, text=True)
    assert done.returncode == 0, done.stdout + done.stderr
    executor = json.loads((results / "executor.json").read_text())
    properties = dict(line.split("=", 1) for line in (results / "environment.properties").read_text().splitlines())
    return executor, properties


@allure.story("DI-65")
@allure.label("output", "rdm/pytest_plugin.py")
def test_the_results_record_who_ran_the_tests_and_where(tmp_path: Path) -> None:
    """DI-65: executor.json names the CI run and its URL, or a local run's user
    and host; environment.properties records the OS, tool versions, commit,
    worktree state, and the CI actor and workflow."""
    repo, head = _project(tmp_path)

    executor, properties = _run(repo, tmp_path / "ci", GITHUB)
    attach("executor.json (CI)", executor)
    attach("environment.properties (CI)", properties)
    with verification_step("on GitHub Actions, executor.json names the CI system, the run and its URL"):
        assert executor == {"name": "GitHub Actions", "type": "github", "buildOrder": "42",
                            "buildName": "Design controls #42",
                            "buildUrl": "https://github.com/acme/device/actions/runs/7001/attempts/2"}
    with verification_step("environment.properties: the OS, the Python, pytest, allure-pytest and RDM versions"):
        assert properties["os"] == f"{platform.system()} {platform.release()} ({platform.machine()})"
        assert properties["python"] == f"{platform.python_implementation()} {platform.python_version()}"
        assert (properties["pytest"], properties["allure-pytest"], properties["rdm"]) == (
            version("pytest"), version("allure-pytest"), version("rdm"))
    with verification_step("the commit under test and the worktree state"):
        assert (properties["commit"], properties["worktree"]) == (head, "clean")
    with verification_step("the CI actor and workflow"):
        assert (properties["ci.actor"], properties["ci.workflow"], properties["ci.event"],
                properties["ci.runner"]) == ("octocat", "Design controls", "pull_request", "Linux X64")
        assert "user" not in properties

    (repo / "notes.txt").write_text("uncommitted\n")
    executor, properties = _run(repo, tmp_path / "local", {})
    attach("executor.json (local)", executor)
    with verification_step("locally, executor.json names a local run with its user and host"):
        assert executor == {"name": "local", "type": "local",
                            "buildName": f"{getpass.getuser()}@{socket.gethostname()}"}
        assert properties["user"] == getpass.getuser() and not any(k.startswith("ci.") for k in properties)
    with verification_step("uncommitted changes are recorded as such"):
        assert (properties["commit"], properties["worktree"]) == (head, "dirty")
    with verification_step("the files sit beside the results of the run's tests, which Allure reads with them"):
        assert len(list((tmp_path / "local").glob("*-result.json"))) == 2
        assert (tmp_path / "local" / "executor.json").is_file()
        assert (tmp_path / "local" / "environment.properties").is_file()
    with verification_step("outside git the commit is unknown, and so is the worktree state"):
        plain = tmp_path / "plain"
        shutil.copytree(repo, plain, ignore=shutil.ignore_patterns(".git"))
        _, properties = _run(plain, tmp_path / "plain-results", {})
        attach("environment.properties (no git)", properties)
        assert (properties["commit"], properties["worktree"]) == ("unknown", "unknown")
