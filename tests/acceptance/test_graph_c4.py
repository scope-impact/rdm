"""Acceptance tests for the graph context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")


@allure.story("DI-67")
@allure.label("output", "TODO")
def test_di_67_not_implemented() -> None:
    """DI-67: RDM shall project the C4 model into the graph: each element typed person,
    software system, container or component, with its name, technology, description and
    external flag; the element that contains it; the bounded context that owns each
    component; each relationship with its source, target, label and technology; each
    component's code; the component every projected source file belongs to (the longest
    matching code path); and, for Python, an import from one component's code into another's
    as a dependency between the two."""
    pytest.fail("DI-67 acceptance test not implemented -- replace this stub with real assertions")


@allure.story("DI-68")
@allure.label("output", "TODO")
def test_di_68_not_implemented() -> None:
    """DI-68: RDM shall warn, never block, through the graph's gate shapes, when the C4 model
    and the record disagree: a design output in no component's code; a test run that
    exercises a component of a context that neither owns nor realises the design input it
    verifies; a dependency between two components with no relationship declared from the one
    to the other; a component in no container, or in a boundary that is not a container of
    the container diagram; a bounded context of the architecture with no component; a
    component whose code path does not exist; a relationship with no label; and an alias
    declared as non-external in two documents."""
    pytest.fail("DI-68 acceptance test not implemented -- replace this stub with real assertions")


@allure.story("DI-69")
@allure.label("output", "TODO")
def test_di_69_not_implemented() -> None:
    """DI-69: RDM's agent server shall show, in the trace of a design input, the components
    whose code its tests exercise, each with its container and owning bounded context."""
    pytest.fail("DI-69 acceptance test not implemented -- replace this stub with real assertions")
