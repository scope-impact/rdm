"""Acceptance tests for the publishing context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")


@allure.story("DI-88")
@allure.label("component", "TODO")
def test_di_88_not_implemented() -> None:
    """DI-88: RDM shall typeset the verification report through one typeset interface that
    takes the layout, its data and its attachments and returns the PDF, answered by the same
    Typst library on the command line (the rdm-typst program) and inside the component, with
    the fonts the layout names embedded in the provider, so that the report is the same
    wherever it is made; with no provider the report says so."""
    pytest.fail("DI-88 acceptance test not implemented -- replace this stub with real assertions")
