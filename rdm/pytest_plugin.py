"""
pytest plugin: record which version of the code an acceptance run is evidence
for, and who or what ran it.

A test claims the requirements of the spec it verifies with
``@allure.tag("<requirement id>")``. The plugin adds no requirement labels of its
own: the trace from a run to what it verifies is the tag, read by RDM from
the results. At run time it adds:

- ``commit``: the commit under test, and ``worktree=dirty`` when the working
  tree had uncommitted changes (DI-59);
- once per run, Allure's ``executor.json`` and ``environment.properties`` in
  the results directory: who or what ran the tests, and where (DI-65).

Enable it for a run with ``-p rdm.pytest_plugin``, or in the repository's
top-level ``conftest.py`` with ``pytest_plugins = ["rdm.pytest_plugin"]``; to
scope it to the acceptance suite, import its hook in that suite's conftest
(``from rdm.pytest_plugin import pytest_runtest_call``). The DHF is
``--rdm-dhf`` when the plugin is enabled with ``-p``, else ``dhf`` under the
pytest root. Use it for acceptance tests only: unit tests carry no Allure.
"""

from __future__ import annotations

import getpass
import os
import platform
import socket
from functools import lru_cache
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

import pytest

from rdm.evidence.allure import COMMIT_LABEL, DIRTY, REQUIREMENT_LABEL, WORKTREE_LABEL, write_run_facts
from rdm.kernel.git import head, repo_root


def pytest_addoption(parser):
    parser.addoption("--rdm-dhf", default=None,
                     help="RDM design history file the acceptance tests verify (default: dhf under the pytest root)")


@lru_cache(maxsize=4)
def _record(dhf: str) -> dict:
    """The spec's requirement ids, and the commit under test."""
    from rdm.specification.sdd import requirement_ids

    path = Path(dhf)
    commit, dirty = head((repo_root(path) or path.parent).resolve())
    return {"requirements": set(requirement_ids(path)), "commit": commit, "dirty": dirty}


def item_ids(item) -> list[str]:
    """What a test claims: its ``@allure.tag(...)`` values (allure-pytest
    keeps its labels as ``allure_label`` marks); the plugin acts on those
    that are requirements of the spec."""
    ids = [str(v).lstrip("@") for m in item.iter_markers(name="allure_label")
           if str(getattr(m.kwargs.get("label_type"), "value", m.kwargs.get("label_type"))) == REQUIREMENT_LABEL
           for v in m.args]
    return list(dict.fromkeys(ids))


_RUN_RECORDED = pytest.StashKey[bool]()


def _version(package: str) -> str:
    try:
        return version(package)
    except PackageNotFoundError:
        return "not installed"


def executor(env=os.environ) -> dict:
    """Allure's executor.json: the CI run that executed the tests, or a local run."""
    if env.get("GITHUB_ACTIONS") == "true":
        server, repo, run = env.get("GITHUB_SERVER_URL", "https://github.com"), env.get("GITHUB_REPOSITORY"), \
            env.get("GITHUB_RUN_ID")
        url = f"{server}/{repo}/actions/runs/{run}"
        if env.get("GITHUB_RUN_ATTEMPT"):
            url += f"/attempts/{env['GITHUB_RUN_ATTEMPT']}"
        return {"name": "GitHub Actions", "type": "github", "buildOrder": env.get("GITHUB_RUN_NUMBER"),
                "buildName": f"{env.get('GITHUB_WORKFLOW', 'workflow')} #{env.get('GITHUB_RUN_NUMBER', '?')}",
                "buildUrl": url}
    return {"name": "local", "type": "local", "buildName": f"{getpass.getuser()}@{socket.gethostname()}"}


def environment(record: dict | None, env=os.environ) -> dict[str, str]:
    """Allure's environment.properties: the configuration and tools of the run."""
    record = record or {}
    facts = {
        "os": f"{platform.system()} {platform.release()} ({platform.machine()})",
        "python": f"{platform.python_implementation()} {platform.python_version()}",
        "pytest": _version("pytest"),
        "allure-pytest": _version("allure-pytest"),
        "rdm": _version("rdm"),
        "commit": record.get("commit") or "unknown",
        # Nothing was checked when the commit is unknown: never claim a clean worktree.
        "worktree": "unknown" if not record.get("commit") else DIRTY if record.get("dirty") else "clean",
    }
    if env.get("GITHUB_ACTIONS") == "true":
        facts |= {"ci.actor": env.get("GITHUB_ACTOR", ""), "ci.workflow": env.get("GITHUB_WORKFLOW", ""),
                  "ci.event": env.get("GITHUB_EVENT_NAME", ""), "ci.ref": env.get("GITHUB_REF", ""),
                  "ci.runner": f"{env.get('RUNNER_OS', '')} {env.get('RUNNER_ARCH', '')}".strip()}
    else:
        facts["user"] = getpass.getuser()
    return facts


def _record_run(config, record: dict | None) -> None:
    """Write executor.json and environment.properties once per session (DI-65)."""
    results = getattr(config.option, "allure_report_dir", None)
    if not results or config.stash.get(_RUN_RECORDED, False):
        return
    config.stash[_RUN_RECORDED] = True
    write_run_facts(Path(results), executor(), environment(record))


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_call(item):
    """Label in the call phase, so the labels and the attachment belong to the
    test result (in set-up they would land on a fixture)."""
    _label(item)
    yield


def _label(item) -> None:
    """Label a tagged test's result with the commit under test (DI-59, DI-65)."""
    try:
        import allure
    except ImportError:
        return
    ids = item_ids(item)
    if not ids:
        return
    dhf = Path(item.config.getoption("--rdm-dhf", default=None) or item.config.rootpath / "dhf")
    record = _record(str(dhf.resolve())) if dhf.is_dir() else None
    _record_run(item.config, record)
    if record is None:
        return
    declared = [i for i in ids if i in record["requirements"]]
    if declared and record["commit"]:  # DI-59: the version this run is evidence for
        allure.dynamic.label(COMMIT_LABEL, record["commit"])
        if record["dirty"]:
            allure.dynamic.label(WORKTREE_LABEL, DIRTY)
