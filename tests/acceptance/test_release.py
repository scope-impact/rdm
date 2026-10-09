"""Acceptance tests for the release context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")


@allure.story("DI-87")
@allure.label("component", "TODO")
def test_di_87_not_implemented() -> None:
    """DI-87: RDM shall build rdm.wasm, one WASI 0.3 component composed from the RDM core and
    its record-state and c4 providers, that imports no network interface of its own and
    that, for design-gate, verify, release-gate, trace, dmr, gap, new-input, init and graph
    build, query, validate and explorer-file, gives the same exit code, output and written
    files as the command line on the same record."""
    pytest.fail("DI-87 acceptance test not implemented -- replace this stub with real assertions")
