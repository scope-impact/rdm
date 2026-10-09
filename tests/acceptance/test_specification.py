"""Acceptance tests for the specification context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import re
import tomllib
from importlib.resources import files
from pathlib import Path

import pytest

from rdm.kernel import record_state as record_state_module
from rdm.kernel.git import GitCommand
from rdm.kernel.record_state import Commit, FileState, HeadState, Reference, Tracking, record_state
from rdm.specification.design_gate import has_uncommitted_changes, story_design_gate_command
from tests.util import COMPLETE_DOC, git_run, write_design_doc

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]


@allure.story("DI-83")
@allure.label("component", "Shared kernel")
def test_record_state_is_one_interface(tmp_path: Path, monkeypatch, capsys) -> None:
    """DI-83: every question about the record in git goes through one record-state
    interface, answered by git on the command line and by a provider in the
    component; both give the same answer, and unknown is never approved."""
    with verification_step("only the shared kernel runs git: no other module imports the git command"):
        askers = sorted(str(f.relative_to(ROOT)) for f in (ROOT / "rdm").rglob("*.py")
                        if re.search(r"from rdm\.kernel\.git import .*\bgit\b|\[\s*[\"']git[\"']", f.read_text()))
        attach("modules that run git", askers)
        assert askers == ["rdm/kernel/git.py"]

    repo = tmp_path / "repo"
    (repo / "dhf").mkdir(parents=True)
    git_run(repo, "init", "-q", "-b", "main")
    git_run(repo, "remote", "add", "origin", "https://github.com/acme/repo")
    (repo / "dhf" / "clean.md").write_text("clean\n")
    (repo / "dhf" / "edited.md").write_text("v1\n")
    (repo / "dhf" / "staged.md").write_text("v1\n")
    (repo / "dhf" / "hidden.md").write_text("v1\n")
    (repo / ".gitignore").write_text("ignored.md\n")
    git_run(repo, "add", "-A")
    git_run(repo, "commit", "-qm", "first")
    first = git_run(repo, "rev-parse", "HEAD")
    (repo / "dhf" / "edited.md").write_text("v2\n")
    (repo / "dhf" / "staged.md").write_text("v2\n")
    git_run(repo, "add", "dhf/staged.md")
    git_run(repo, "update-index", "--skip-worktree", "dhf/hidden.md")
    (repo / "dhf" / "new.md").write_text("new\n")
    (repo / "dhf" / "ignored.md").write_text("ignored\n")

    with verification_step("the git provider answers the record's state in the record's terms, as git sees it"):
        state = record_state(repo / "dhf")
        assert state is not None and state.root == repo.resolve()
        head = state.head()
        assert head == HeadState(commit=first, origin="https://github.com/acme/repo", dirty=True, merging=False)
        files = {f.path: f for f in state.files([repo / "dhf"])}
        attach("file states", {p: (f.tracking.value, f.staged, f.modified) for p, f in files.items()})
        assert files["dhf/clean.md"] == FileState("dhf/clean.md", Tracking.TRACKED, False, False)
        assert files["dhf/edited.md"] == FileState("dhf/edited.md", Tracking.TRACKED, False, True)
        assert files["dhf/staged.md"] == FileState("dhf/staged.md", Tracking.TRACKED, True, False)
        assert files["dhf/hidden.md"].tracking is Tracking.SKIP_WORKTREE
        assert files["dhf/new.md"].tracking is Tracking.UNTRACKED
        assert files["dhf/ignored.md"].tracking is Tracking.IGNORED
        assert not files["dhf/clean.md"].changed and all(files[p].changed for p in files if p != "dhf/clean.md")

    with verification_step("what a commit holds at a path, and the history the graph asks for, as git answers them"):
        assert state.staged_in_history(repo / "dhf" / "clean.md")
        assert not state.staged_in_history(repo / "dhf" / "staged.md")
        git_run(repo, "checkout", "-qb", "topic")
        git_run(repo, "commit", "-qam", "edit on topic")
        topic = git_run(repo, "rev-parse", "HEAD")
        git_run(repo, "checkout", "-q", "main")
        git_run(repo, "merge", "-q", "--no-ff", "-m", "merge topic", "topic")
        merge = git_run(repo, "rev-parse", "HEAD")
        assert state.latest_commits(["dhf/clean.md", "dhf/edited.md", "nothing.md"]) == \
            {"dhf/clean.md": first, "dhf/edited.md": topic}
        assert state.commit("HEAD") == Commit(merge, "t", git_run(repo, "log", "-1", "--format=%aI"), "merge topic")
        assert state.commit("nope") is None
        assert state.resolve("refs/heads/main") == Reference(merge, None) and state.resolve("refs/heads/none") is None
        git_run(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/heads/main")
        assert state.resolve("refs/remotes/origin/HEAD") == Reference(merge, "refs/heads/main")
        assert state.branches() == ["main", "topic"]
        git_run(repo, "config", "init.defaultBranch", "main")
        assert state.config("init.defaultBranch") == "main" and state.config("no.such.key") is None
        assert state.first_parents("main") == [merge, first]
        assert state.ancestry_path(first, "main") == [merge, topic]

    with verification_step("the design gate asks only the interface: another provider's answer is its verdict"):
        doc = repo / "dhf" / "clean.md"
        assert has_uncommitted_changes(doc) is False

        class Edited(GitCommand):
            def files(self, paths):
                return [FileState(f.path, f.tracking, f.staged, True) for f in super().files(paths)]

        monkeypatch.setattr(record_state_module, "_provider", Edited)
        assert has_uncommitted_changes(doc) is True
        monkeypatch.setattr(record_state_module, "_provider", None)
        views = repo / "dhf" / "views"
        views.mkdir()
        (views / "c1.svg").write_text("<svg/>")
        assert has_uncommitted_changes(views) is True, "a directory with an untracked file is not committed"
        git_run(repo, "add", "-A")
        git_run(repo, "commit", "-qm", "views")
        assert has_uncommitted_changes(views) is False

    with verification_step("a question no provider can answer is unknown, and unknown is never approved"):
        class Silent(GitCommand):
            def files(self, paths):
                return None

        monkeypatch.setattr(record_state_module, "_provider", Silent)
        assert has_uncommitted_changes(doc) is None
        assert record_state(tmp_path / "nowhere") is None
        plain = tmp_path / "plain"
        write_design_doc(plain / "documents" / "design", "core", design_inputs=(("DI-1", ["UN-002"]),))
        (plain / "documents" / "design_review.md").write_text(COMPLETE_DOC)
        (plain / "documents" / "verification_and_validation_plan.md").write_text(
            "---\nid: VVP-001\nuser_needs:\n  - {id: UN-002, text: need}\n---\n\nplan\n")
        assert story_design_gate_command(plain) == 0
        out = capsys.readouterr().out
        attach("design gate with no answer", out)
        assert "could not be checked" in out and "approved (committed) in version control" not in out


@allure.story("DI-86")
@allure.label("component", "Project templates")
def test_shipped_files_are_package_resources() -> None:
    """DI-86: RDM's shipped files are read as resources of the installed package,
    never by a path relative to its source, so a component or a zipped install
    finds them."""
    with verification_step("no module of the package locates a file beside its own source"):
        by_source = sorted(str(f.relative_to(ROOT)) for f in (ROOT / "rdm").rglob("*.py")
                           if "__file__" in f.read_text())
        attach("modules reading beside __file__", by_source)
        assert by_source == []

    with verification_step("every shipped file the package declares is a resource the installed package finds"):
        declared = tomllib.loads((ROOT / "pyproject.toml").read_text())["tool"]["setuptools"]["package-data"]["rdm"]
        package = files("rdm")
        missing = [pattern for pattern in declared
                   if not any(e.is_file() for e in package.joinpath(pattern.rsplit("/", 1)[0]).iterdir())]
        attach("declared package data", declared)
        assert not missing, missing

    with verification_step("the shapes, the vocabulary, the checklists and the templates are read through resources"):
        from rdm.compliance.gaps import builtin_checklists
        from rdm.graph.project import ONTOLOGY_FILE
        from rdm.graph.validate import SHAPES_FILE

        assert "sh:NodeShape" in SHAPES_FILE.read_text(encoding="utf-8")
        assert "rdm:Rule" in ONTOLOGY_FILE.read_text(encoding="utf-8")
        checklists = builtin_checklists()
        assert "62304_2015_class_b" in checklists and Path(checklists["62304_2015_class_b"]).read_text()
        assert (files("rdm.specification") / "init_files").is_dir()
        assert (files("rdm.specification") / "adopt_files").is_dir()
        assert (files("rdm.publishing") / "verification_report.typ").is_file()
