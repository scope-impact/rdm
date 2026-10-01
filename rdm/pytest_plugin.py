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
  tree had uncommitted changes (DI-59).

Enable it for a run with ``-p rdm.pytest_plugin``, or in the repository's
top-level ``conftest.py`` with ``pytest_plugins = ["rdm.pytest_plugin"]``; to
scope it to the acceptance suite, import its hook in that suite's conftest
(``from rdm.pytest_plugin import pytest_runtest_call``). The DHF is
``--rdm-dhf`` when the plugin is enabled with ``-p``, else ``dhf`` under the
pytest root. Use it for acceptance tests only: unit tests carry no Allure.
"""

from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path

import pytest

from rdm.record.git import git


def pytest_addoption(parser):
    parser.addoption("--rdm-dhf", default=None,
                     help="RDM design history file the acceptance tests verify (default: dhf under the pytest root)")


def web_url(remote: str | None) -> str | None:
    """A browsable https URL for a GitHub, GitLab or similar remote."""
    if not remote:
        return None
    match = re.match(r"^(?:git@|ssh://git@)([^:/]+)[:/](.+?)(?:\.git)?/?$", remote) or \
        re.match(r"^https?://(?:[^@/]+@)?([^/]+)/(.+?)(?:\.git)?/?$", remote)
    return f"https://{match.group(1)}/{match.group(2)}" if match else None


@lru_cache(maxsize=4)
def _record(dhf: str) -> dict:
    """Design inputs, the documents that declare each id (repo-relative), and
    the risks each input controls."""
    from rdm.record.risk import risks
    from rdm.record.sdd import declarations, design_inputs

    path = Path(dhf)
    root = Path(git(path, "rev-parse", "--show-toplevel") or path.parent).resolve()

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
        "web": web_url(git(root, "remote", "get-url", "origin")),
        "commit": git(root, "rev-parse", "HEAD"),
        "dirty": bool(git(root, "status", "--porcelain")),
    }


def story_ids(item) -> list[str]:
    """The design inputs a test is tagged with: allure-pytest keeps its labels
    as ``allure_label`` marks on the test function."""
    marks = list(getattr(getattr(item, "function", None), "pytestmark", []))
    marks += [m for m in item.iter_markers(name="allure_label") if m not in marks]
    ids = [str(v) for m in marks if m.name == "allure_label" and str(m.kwargs.get("label_type")) in
           ("story", "LabelType.STORY") for v in m.args]
    return list(dict.fromkeys(ids))


def _documents(record: dict, di: str, requirement: dict) -> list[tuple[str, str]]:
    """(link name, repo-relative Markdown path), one per document that
    declares the input: its design document, the V&V plan (or wherever) its
    user needs are declared, and the risk document of each risk it controls.
    One link per document (Allure keeps one link per URL), named for the ids it
    declares. None without a web remote and a commit to pin them to."""
    if not (record["web"] and record["commit"]):
        return []
    found = [(di, doc) for doc in record["declared"].get(di, [])]
    found += [(need, doc) for need in requirement["traces_to"] for doc in record["declared"].get(need, [])]
    found += [(risk, doc) for risk, doc in record["controls"].get(di, [])]
    ids: dict[str, list[str]] = {}
    for id_, doc in found:
        ids.setdefault(doc, []).append(id_)
    return [(f"{', '.join(names)} in {doc}", doc) for doc, names in ids.items()]


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_call(item):
    """Label in the call phase, so the labels and the attachment belong to the
    test result (in set-up they would land on a fixture)."""
    try:
        import allure
    except ImportError:
        allure = None
    ids = story_ids(item) if allure is not None else []
    if ids:
        dhf = item.config.getoption("--rdm-dhf", default=None) or str(Path(str(item.config.rootpath)) / "dhf")
        record = _record(str(Path(dhf).resolve())) if Path(dhf).is_dir() else None
        declared = [di for di in ids if record and di in record["inputs"]]
        if declared and record["commit"]:  # DI-59: the version this run is evidence for
            allure.dynamic.label("commit", record["commit"])
            if record["dirty"]:
                allure.dynamic.label("worktree", "dirty")
        for di in declared:
            requirement = record["inputs"][di]
            for need in requirement["traces_to"]:
                allure.dynamic.epic(need)
            allure.dynamic.feature(requirement["context"])
            for name, doc in _documents(record, di, requirement):
                allure.dynamic.link(f"{record['web']}/blob/{record['commit']}/{doc}", name=name)
            if di in record["controls"]:
                allure.dynamic.severity(allure.severity_level.CRITICAL)
            allure.attach(f"{di} ({requirement['context']}): {requirement['text']}\n"
                          f"Traces to: {', '.join(requirement['traces_to']) or '—'}\n",
                          name=f"requirement {di}", attachment_type=allure.attachment_type.TEXT)
    yield
