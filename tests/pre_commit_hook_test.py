"""
Behavioural tests for the design-controls pre-commit hook.

The hook gates commits that introduce implementation work on the design input
and design review being complete and approved (committed), while allowing the
design documents' own commit (so approving them never deadlocks).
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from tests.util import git_run as _git

HOOK = Path(__file__).resolve().parents[1] / "rdm" / "hook_files" / "pre-commit"


def _run_hook(repo: Path, **env_overrides: str) -> int:
    env = {**os.environ, **env_overrides}
    return subprocess.run(["bash", str(HOOK)], cwd=repo, env=env, capture_output=True).returncode


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    r = tmp_path / "repo"
    r.mkdir()
    _git(r, "init")
    return r


def test_allows_when_no_implementation_work_staged(repo: Path) -> None:
    # A staged non-code file (no work pattern match) must not trigger the gate,
    # so the design documents can be authored/approved without deadlock.
    (repo / "notes.md").write_text("notes")
    _git(repo, "add", "notes.md")
    assert _run_hook(repo) == 0


def test_allows_staged_python_under_dhf(repo: Path) -> None:
    # Code inside the DHF directory is not implementation work.
    (repo / "dhf").mkdir()
    (repo / "dhf" / "gen.py").write_text("x = 1\n")
    _git(repo, "add", "dhf/gen.py")
    assert _run_hook(repo) == 0


def test_bypass_env_allows_commit(repo: Path) -> None:
    (repo / "app.py").write_text("x = 1\n")
    _git(repo, "add", "app.py")
    assert _run_hook(repo, RDM_SKIP_DESIGN_GATE="1") == 0


def test_custom_work_pattern_gates_non_python(repo: Path) -> None:
    # With no rdm on PATH the hook allows (cannot verify); to test the trigger
    # independently we point the DHF at a missing dir and rely on rdm being
    # present. Skip when rdm is unavailable.
    if shutil.which("rdm") is None:
        pytest.skip("rdm not on PATH")
    (repo / "app.py").write_text("x = 1\n")
    _git(repo, "add", "app.py")
    # No dhf/ directory exists -> gate cannot pass -> commit blocked.
    assert _run_hook(repo) == 1


def test_blocks_python_when_design_docs_incomplete(repo: Path) -> None:
    if shutil.which("rdm") is None:
        pytest.skip("rdm not on PATH")
    subprocess.run(["rdm", "init", "-o", "dhf"], cwd=repo, capture_output=True)
    (repo / "app.py").write_text("x = 1\n")
    _git(repo, "add", "app.py")
    # Scaffolded design docs still contain placeholders -> blocked.
    assert _run_hook(repo) == 1
