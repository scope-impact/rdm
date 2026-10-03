"""Acceptance test for the gating context's DI-26 (see dhf/).

DI-26 (design-gate-only hooks default), tagged with `@allure.story`,
exercising the real hooks installer. The earlier gating inputs (DI-2/3) are
verified in their own test modules.

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from rdm.specification.hooks import install_hooks

allure = pytest.importorskip("allure")


@allure.story("DI-26")
@allure.label("output", "rdm/specification/hooks.py")
def test_hooks_installs_design_gate_only_by_default(tmp_path: Path) -> None:
    """DI-26: default install is the design-gate pre-commit hook only; the
    issue-reference hooks land solely with the explicit flag."""
    default_dest = tmp_path / "default"
    install_hooks(str(default_dest))
    assert os.access(default_dest / "pre-commit", os.X_OK)
    assert os.access(default_dest / "pre-merge-commit", os.X_OK)  # a merge runs the same gate
    assert not (default_dest / "commit-msg").exists()
    assert not (default_dest / "prepare-commit-msg").exists()

    optin_dest = tmp_path / "optin"
    install_hooks(str(optin_dest), with_issue_hooks=True)
    for name in ("pre-commit", "commit-msg", "prepare-commit-msg"):
        assert os.access(optin_dest / name, os.X_OK)
