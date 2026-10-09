"""Acceptance tests for the specification context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")


@allure.story("DI-83")
@allure.label("component", "TODO")
def test_di_83_not_implemented() -> None:
    """DI-83: RDM shall ask every question about the state of the record in git — the commit
    checked out and whether the worktree is clean, whether a file is tracked, unchanged,
    changed or hidden from git, what a commit holds at a path, the repository's origin, and
    the commits that reach a commit — through one record-state interface, answered by git on
    the command line and by the record-state provider inside the component, so that both
    give the same answer and a question that cannot be answered is never reported as
    approved."""
    pytest.fail("DI-83 acceptance test not implemented -- replace this stub with real assertions")


@allure.story("DI-86")
@allure.label("component", "TODO")
def test_di_86_not_implemented() -> None:
    """DI-86: RDM shall read its own shipped files — the document and project templates, the
    checklists, the SHACL shapes and the vocabulary — as package resources of the installed
    package, never by a path relative to its source, so that a component or a zipped install
    finds them."""
    pytest.fail("DI-86 acceptance test not implemented -- replace this stub with real assertions")
