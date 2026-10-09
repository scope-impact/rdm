"""
pytest plugin: label each acceptance run from RDM's record (DI-57).

Allure organises results as epic → feature → story; RDM's record is user
need → bounded context → design input. A test carries one tag,
``@allure.story("DI-n")``. This plugin adds the rest at run time, from the
record, with Allure's dynamic API:

- ``epic``: each user need the design input traces to;
- ``feature``: its bounded context;
- ``link``: each Markdown document that declares it, at the commit under test —
  its design document, where its user needs are declared (the V&V plan), and
  the risk document of each risk it controls;
- ``severity``: critical when the input controls a risk;
- an attachment with the input's text;
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

from rdm.evidence.allure import COMMIT_LABEL, DIRTY, REQUIREMENT_ATTACHMENT, WORKTREE_LABEL, write_run_facts
from rdm.kernel.git import web_url
from rdm.kernel.record_state import record_state
from rdm.specification.tags import DESIGN_INPUT_LABELS


def pytest_addoption(parser):
    parser.addoption("--rdm-dhf", default=None,
                     help="RDM design history file the acceptance tests verify (default: dhf under the pytest root)")


@lru_cache(maxsize=4)
def _record(dhf: str) -> dict:
    """Design inputs, the documents that declare each id (repo-relative), and
    the risks each input controls."""
    from rdm.risk.register import risks
    from rdm.specification.sdd import declarations, design_inputs

    path = Path(dhf)
    state = record_state(path)
    root = (state.root if state else path.parent).resolve()
    head = state.head() if state else None

    def rel(doc: Path) -> str:
        return doc.resolve().relative_to(root).as_posix()

    controls: dict[str, list[tuple[str, str]]] = {}
    for risk in risks(path):
        for control in risk.controls:
            controls.setdefault(control, []).append((risk.id, rel(path.parent / risk.document)))
    return {
        "inputs": {di["id"]: di for di in design_inputs(path)},
        "declared": {id_: list(dict.fromkeys(rel(path / d) for d in docs))
                     for id_, docs in declarations(path).items()},
        "controls": controls,
        "web": web_url(head.origin) if head else None,
        "commit": head.commit if head else None,
        "dirty": bool(head and head.dirty),
    }


def story_ids(item) -> list[str]:
    """The design inputs a test is tagged with: allure-pytest keeps its labels
    as ``allure_label`` marks on the test function."""
    ids = [str(v) for m in item.iter_markers(name="allure_label")
           if str(getattr(m.kwargs.get("label_type"), "value", m.kwargs.get("label_type"))) in DESIGN_INPUT_LABELS
           for v in m.args]
    return list(dict.fromkeys(ids))


def _documents(record: dict, di: str) -> list[tuple[str, str]]:
    """(link name, repo-relative Markdown path), one per document that
    declares the input: its design document, the V&V plan (or wherever) its
    user needs are declared, and the risk document of each risk it controls.
    One link per document (Allure keeps one link per URL), named for the ids it
    declares. None without a web remote and a commit to pin them to."""
    if not (record["web"] and record["commit"]):
        return []
    found = [(di, doc) for doc in record["declared"].get(di, [])]
    found += [(need, doc) for need in record["inputs"][di]["traces_to"] for doc in record["declared"].get(need, [])]
    found += [(risk, doc) for risk, doc in record["controls"].get(di, [])]
    ids: dict[str, list[str]] = {}
    for id_, doc in found:
        ids.setdefault(doc, []).append(id_)
    return [(f"{', '.join(names)} in {doc}", doc) for doc, names in ids.items()]


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
    """Label a tagged test's result from the record (DI-57, DI-59, DI-65)."""
    try:
        import allure
    except ImportError:
        return
    ids = story_ids(item)
    if not ids:
        return
    dhf = Path(item.config.getoption("--rdm-dhf", default=None) or item.config.rootpath / "dhf")
    record = _record(str(dhf.resolve())) if dhf.is_dir() else None
    _record_run(item.config, record)
    if record is None:
        return
    declared = [di for di in ids if di in record["inputs"]]
    if declared and record["commit"]:  # DI-59: the version this run is evidence for
        allure.dynamic.label(COMMIT_LABEL, record["commit"])
        if record["dirty"]:
            allure.dynamic.label(WORKTREE_LABEL, DIRTY)
    for di in declared:
        requirement = record["inputs"][di]
        for need in requirement["traces_to"]:
            allure.dynamic.epic(need)
        allure.dynamic.feature(requirement["context"])
        for name, doc in _documents(record, di):
            allure.dynamic.link(f"{record['web']}/blob/{record['commit']}/{doc}", name=name)
        if di in record["controls"]:
            allure.dynamic.severity(allure.severity_level.CRITICAL)
        allure.attach(f"{di} ({requirement['context']}): {requirement['text']}\n"
                      f"Traces to: {', '.join(requirement['traces_to']) or '—'}\n",
                      name=REQUIREMENT_ATTACHMENT.format(di), attachment_type=allure.attachment_type.TEXT)
