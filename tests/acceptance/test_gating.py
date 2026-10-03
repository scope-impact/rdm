"""Acceptance tests for the git hooks: DI-26 (the installer installs the
design-gate hooks only, by default) and DI-71 (the hooks run the design gate
on every commit and merge of implementation work), tagged with
`@allure.story`, exercising the real installer and the real hooks.

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from rdm.specification import design_gate as design_gate_module
from rdm.specification.hooks import install_hooks
from tests.util import git_run as _git

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402
from tests.acceptance.test_user_needs import _approved_dhf  # noqa: E402


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


@allure.story("DI-71")
@allure.label("output", "rdm/specification/hook_files/pre-commit")
@allure.label("output", "rdm/specification/hook_files/pre-merge-commit")
def test_hooks_gate_every_commit_and_merge_of_implementation(tmp_path: Path) -> None:
    """DI-71: the hooks run the design gate on a commit or a merge that stages
    implementation files and block it unless the gate passes; a commit of only
    design documents passes."""
    with verification_step("a commit staging implementation is blocked while the gate fails, however its files "
                           "are named, cased or staged"):
        hooks = Path(design_gate_module.__file__).parent / "hook_files"
        red = tmp_path / "red"
        (red / "dhf").mkdir(parents=True)  # no design document: the gate is red
        _git(red, "init", "-q", "-b", "main")
        (red / "README.md").write_text("start\n")
        _git(red, "add", "-A")
        _git(red, "commit", "-qm", "start")
        assert "pip install rdm " not in (hooks / "pre-commit").read_text()

        def hook(name: str = "pre-commit") -> int:
            return subprocess.run(["bash", str(hooks / name)], cwd=red, capture_output=True).returncode

        for name in ("a\tb.py", 'we"ird.py', "app.PY", "Main.JAVA", "config.json", "settings.ini", "setup.cfg",
                     "Dockerfile", "Makefile", "index.html", "App.vue", "stubs.pyi", "analysis.R", "deploy.ps1"):
            (red / name).write_text("x\n")
            _git(red, "add", "--", name)
            assert hook() == 1, name
            _git(red, "reset", "-q")
            (red / name).unlink()
        (red / "app.py").write_text("x\n")
        (red / "evil.py").write_text("y\n")
        _git(red, "add", "-A")
        _git(red, "commit", "-qm", "before the gate")  # no hook installed yet
        (red / "app.py").unlink()
        (red / "app.py").symlink_to("evil.py")
        _git(red, "add", "app.py")
        assert hook() == 1, "a type change"
        _git(red, "reset", "-q", "--hard")
    with verification_step("a merge bringing implementation in is blocked while the gate fails"):
        install_hooks(str(red / ".git" / "hooks"))
        _git(red, "checkout", "-qb", "feature")
        (red / "merged.py").write_text("z\n")
        _git(red, "add", "-A")
        subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "work"], cwd=red,
                       check=True, env={**os.environ, "RDM_SKIP_DESIGN_GATE": "1"})
        _git(red, "checkout", "-q", "main")
        (red / "README.md").write_text("moved on\n")
        _git(red, "commit", "-qam", "docs")
        head = _git(red, "rev-parse", "HEAD")
        merge = subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "merge", "--no-ff", "-m", "m",
                                "feature"], cwd=red, capture_output=True, text=True)
        assert merge.returncode != 0 and _git(red, "rev-parse", "HEAD") == head, merge.stdout + merge.stderr
    with verification_step("a commit of only design documents passes, and so does the merge of a design change "
                           "approved on its branch with its implementation"):
        green = _approved_dhf(tmp_path / "green", ["UN-001"]).parent
        _git(green, "branch", "-M", "main")
        install_hooks(str(green / ".git" / "hooks"))
        _git(green, "checkout", "-qb", "feature")
        core = green / "dhf" / "documents" / "design" / "core.md"
        core.write_text(core.read_text().replace("DI-1 requirement", "DI-1 refined requirement"))
        _git(green, "commit", "-qam", "design")  # the approval: design documents only
        (green / "app.py").write_text("print(1)\n")
        _git(green, "add", "app.py")
        _git(green, "commit", "-qm", "code")
        _git(green, "checkout", "-q", "main")
        (green / "README.md").write_text("moved on\n")
        _git(green, "add", "README.md")
        _git(green, "commit", "-qm", "docs")
        merge = subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "merge", "--no-ff", "-m", "m",
                                "feature"], cwd=green, capture_output=True, text=True)
        assert merge.returncode == 0 and (green / "app.py").exists(), merge.stdout + merge.stderr
    with verification_step("when the gate cannot run (no rdm to run it, no DHF to run it on) the commit is blocked"):
        bin_dir = tmp_path / "bin"  # git only: neither rdm nor uv can be found
        bin_dir.mkdir()
        (bin_dir / "git").symlink_to(shutil.which("git"))
        (red / "work.py").write_text("x\n")
        _git(red, "add", "work.py")
        no_runner = subprocess.run([shutil.which("bash"), str(hooks / "pre-commit")], cwd=red, capture_output=True,
                                   text=True, env={**os.environ, "PATH": str(bin_dir)})
        assert no_runner.returncode == 1 and "not on PATH" in no_runner.stderr, no_runner.stderr
        no_dhf = subprocess.run(["bash", str(hooks / "pre-commit")], cwd=red, capture_output=True, text=True,
                                env={**os.environ, "RDM_DHF_DIR": "nowhere"})
        assert no_dhf.returncode == 1 and "'nowhere' not found" in no_dhf.stderr, no_dhf.stderr
