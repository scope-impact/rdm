"""Acceptance tests for the architecture context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")


@allure.story("DI-84")
@allure.label("component", "TODO")
def test_di_84_not_implemented() -> None:
    """DI-84: RDM shall export and draw the architecture workspace through one c4 interface
    that takes the workspace's sources and returns the exported model and each view's
    drawing, so that the command line and the component build the same model from the same
    workspace, and a view the drawing provider cannot draw is refused, naming the view."""
    pytest.fail("DI-84 acceptance test not implemented -- replace this stub with real assertions")
