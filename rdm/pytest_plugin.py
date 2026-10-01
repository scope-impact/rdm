"""
pytest plugin: label each acceptance run from RDM's record (DI-57).

Allure organises results as epic → feature → story; RDM's record is user
need → bounded context → design input. A test carries one tag,
``@allure.story("DI-n")``. This plugin adds the rest at run time, from the
record, with Allure's dynamic API:

- ``epic``: each user need the design input traces to;
- ``feature``: its bounded context;
- ``link``: its design document, at the commit under test;
- ``severity``: critical when the input controls a risk;
- an attachment with the input's text.

Enable it for a run with ``-p rdm.pytest_plugin``, or in the repository's
top-level ``conftest.py`` with ``pytest_plugins = ["rdm.pytest_plugin"]``; to
scope it to the acceptance suite, import its hook in that suite's conftest
(``from rdm.pytest_plugin import pytest_runtest_call``). The DHF is
``--rdm-dhf`` when the plugin is enabled with ``-p``, else ``dhf`` under the
pytest root. Use it for acceptance tests only: unit tests carry no Allure.
"""

from __future__ import annotations

import re
import subprocess
from functools import lru_cache
from pathlib import Path

import pytest


def pytest_addoption(parser):
    parser.addoption("--rdm-dhf", default=None,
                     help="RDM design history file the acceptance tests verify (default: dhf under the pytest root)")


def _git(root: Path, *args: str) -> str | None:
    out = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 and out.stdout.strip() else None


def web_url(remote: str | None) -> str | None:
    """A browsable https URL for a GitHub, GitLab or similar remote."""
    if not remote:
        return None
    match = re.match(r"^(?:git@|ssh://git@)([^:/]+)[:/](.+?)(?:\.git)?/?$", remote) or \
        re.match(r"^https?://(?:[^@/]+@)?([^/]+)/(.+?)(?:\.git)?/?$", remote)
    return f"https://{match.group(1)}/{match.group(2)}" if match else None


@lru_cache(maxsize=4)
def _record(dhf: str) -> dict:
    """Design inputs, their documents, and the inputs that control risks."""
    from rdm.record.risk import risks
    from rdm.record.sdd import design_inputs, find_design_docs, context_of

    path = Path(dhf)
    root = Path(_git(path, "rev-parse", "--show-toplevel") or path.parent)
    docs = {context_of(doc): doc for doc in find_design_docs(path)}
    return {
        "inputs": {di["id"]: di for di in design_inputs(path)},
        "docs": {ctx: str(doc.resolve().relative_to(root.resolve())) for ctx, doc in docs.items()},
        "controls": {c for r in risks(path) for c in r.controls},
        "web": web_url(_git(root, "remote", "get-url", "origin")),
        "commit": _git(root, "rev-parse", "HEAD"),
    }


def story_ids(item) -> list[str]:
    """The design inputs a test is tagged with: allure-pytest keeps its labels
    as ``allure_label`` marks on the test function."""
    marks = list(getattr(getattr(item, "function", None), "pytestmark", []))
    marks += [m for m in item.iter_markers(name="allure_label") if m not in marks]
    ids = [str(v) for m in marks if m.name == "allure_label" and str(m.kwargs.get("label_type")) in
           ("story", "LabelType.STORY") for v in m.args]
    return list(dict.fromkeys(ids))


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
        for di in ids:
            requirement = record["inputs"].get(di) if record else None
            if not requirement:
                continue
            for need in requirement["traces_to"]:
                allure.dynamic.epic(need)
            allure.dynamic.feature(requirement["context"])
            doc = record["docs"].get(requirement["context"])
            if doc and record["web"] and record["commit"]:
                allure.dynamic.link(f"{record['web']}/blob/{record['commit']}/{doc}", name=f"{di} in {doc}")
            if di in record["controls"]:
                allure.dynamic.severity(allure.severity_level.CRITICAL)
            allure.attach(f"{di} ({requirement['context']}): {requirement['text']}\n"
                          f"Traces to: {', '.join(requirement['traces_to']) or '—'}\n",
                          name=f"requirement {di}", attachment_type=allure.attachment_type.TEXT)
    yield
