"""Acceptance tests for the record context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")


@allure.story("DI-66")
@allure.label("output", "TODO")
def test_di_66_not_implemented() -> None:
    """DI-66: RDM shall read the C4 model from the Mermaid C4 diagrams in the record (C4Context
    and C4Container in the architecture document, C4Component and C4 code views in each
    bounded context's design document): every person, software system, container and
    component with its alias, name, technology, description and whether it is external; the
    boundary that contains it; the document and bounded context that declare it; every
    relationship with its direction, label and technology; and the code a component names
    with $link, a file or a directory."""
    pytest.fail("DI-66 acceptance test not implemented -- replace this stub with real assertions")
