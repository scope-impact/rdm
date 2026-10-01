"""Every acceptance run carries the requirement it verifies.

For each design input a test is tagged with (``@allure.story("DI-n")``), the
run attaches that input's text from RDM's own record, as it stood when the
test ran. The retained results then say what each verdict was about, in the
requirement's words. Acceptance tests only: unit tests carry no Allure tags,
steps or attachments (``tests/allure_scope_test.py``).
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

import pytest

DHF = Path(__file__).resolve().parents[2] / "dhf"


@lru_cache(maxsize=1)
def _requirements() -> dict[str, dict]:
    from rdm.record.sdd import design_inputs

    return {di["id"]: di for di in design_inputs(DHF)}


def _story_ids(item) -> list[str]:
    """The design inputs a test is tagged with: allure-pytest keeps its labels
    as ``allure_label`` marks on the test function."""
    marks = list(getattr(getattr(item, "function", None), "pytestmark", []))
    marks += [m for m in item.iter_markers(name="allure_label") if m not in marks]
    ids = []
    for mark in marks:
        if mark.name == "allure_label" and str(mark.kwargs.get("label_type")) in ("story", "LabelType.STORY"):
            ids.extend(str(v) for v in mark.args)
    return list(dict.fromkeys(ids))


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_call(item):
    """Attach in the call phase, so the attachment belongs to the test result
    (in set-up it would land on a fixture, in the container file)."""
    try:
        import allure
    except ImportError:
        allure = None
    if allure is not None:
        for di in _story_ids(item):
            requirement = _requirements().get(di)
            if requirement:
                allure.attach(f"{di} ({requirement['context']}): {requirement['text']}\n"
                              f"Traces to: {', '.join(requirement['traces_to']) or '—'}\n",
                              name=f"requirement {di}", attachment_type=allure.attachment_type.TEXT)
    yield
