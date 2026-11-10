"""Acceptance tests for the scaffolding context's requirements (see spec/).

The tests ("live BDD") that verify item-01-002 (`rdm init`) and item-01-003
(`rdm adopt`), tagged `@allure.tag`, exercising the real scaffolders.
Lightweight on purpose: the end-to-end "the scaffold builds a release" check
lives in `fresh_release_test.py` (it needs Pandoc/make), not here.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest


allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402


@allure.tag("item-01-002")
@allure.label("output", "rdm/specification/init.py")
def test_init_scaffolds_a_project(tmp_path: Path, capsys) -> None:
    """`rdm init` lays down the templates, Makefile, and render config."""
    from rdm.main import cli

    project = tmp_path / "regulatory"  # must not pre-exist (copytree)
    assert cli(["init", "-o", str(project)]) == 0
    with verification_step("it says what it laid down and what to do next; a directory that exists is refused"):
        out = capsys.readouterr().out
        assert str(project) in out and "Next steps" in out and "rdm gap" in out, out
        assert cli(["init", "-o", str(project)]) == 2
        assert "exists" in capsys.readouterr().out
    with verification_step("the scaffold builds offline and without an RDM checkout: local images, Word rebuilt "
                           "when its reference document changes, the container installs RDM without a wheel"):
        for doc in (project / "documents").rglob("*.md"):
            assert "github.com/innolitics/rdm/raw" not in doc.read_text(), doc
        assert "$(wildcard reference.docx)" in (project / "Makefile").read_text()
        dockerfile = (project / "Dockerfile").read_text()
        assert "COPY dist/*.whl" not in dockerfile and "git+https://github.com/scope-impact/rdm" in dockerfile

    with verification_step("the build skeleton"):
        assert (project / "Makefile").is_file()
        assert (project / "config.yml").is_file()
        assert (project / "documents").is_dir()

    with verification_step("the regulatory document templates ship, the traceability matrix among them"):
        docs = project / "documents"
        assert (docs / "software_design_specification.md").is_file()
        assert (docs / "design_review.md").is_file()
        assert (docs / "traceability_matrix.md").is_file()


@allure.tag("item-01-003")
@allure.label("output", "rdm/specification/adopt.py")
def test_adopt_brings_existing_repo_under_controls(tmp_path: Path, capsys) -> None:
    """One command lays down the DHF skeleton and the CI workflow into
    an existing repo, skipping (never overwriting) anything that already
    exists."""
    from rdm.specification.adopt import adopt_command

    repo = tmp_path / "legacy"
    (repo / "src").mkdir(parents=True)
    (repo / "src" / "app.py").write_text("print('legacy')\n")
    (repo / "dhf").mkdir()
    sentinel = "# our own pre-existing DHF notes\n"
    (repo / "dhf" / "README.md").write_text(sentinel)

    assert adopt_command(str(repo)) == 0
    out = capsys.readouterr().out

    with verification_step("The control surface lands: DHF skeleton"):
        docs = repo / "dhf" / "documents"
        assert (docs / "verification_and_validation_plan.md").is_file()   # V&V plan
        assert (docs / "traceability_matrix.md").is_file()                # matrix template
    with verification_step("...and the CI workflow, pinned to the RDM that adopted"):
        workflow = (repo / ".github" / "workflows" / "design-controls.yml").read_text()
        assert "scope-impact/rdm/.github/workflows/gates.yml@v" in workflow

    with verification_step("Pre-existing files are skipped, never overwritten — and reported"):
        assert (repo / "dhf" / "README.md").read_text() == sentinel
        assert "dhf/README.md" in out and "Skipped" in out
        assert (repo / "src" / "app.py").read_text() == "print('legacy')\n"

    with verification_step("Re-running is safe: everything now exists, so nothing is copied"):
        assert adopt_command(str(repo)) == 0
        rerun_out = capsys.readouterr().out
        assert "Laid down:" not in rerun_out
    with verification_step("what RDM generates is ignored and a render configuration lands"):
        ignored = (repo / ".gitignore").read_text()
        for entry in ("allure-results/", "dhf/data/", ".rdm/", "__pycache__/"):
            assert entry in ignored, entry
        assert (repo / "dhf" / "config.yml").is_file()
    with verification_step("an existing .gitignore is kept, and the lines to add are named; a symbolic link, "
                           "dangling or not, is never written through"):
        other = tmp_path / "other"
        other.mkdir()
        (other / ".gitignore").write_text("node_modules/\n")
        (other / "dhf").mkdir()
        (other / "dhf" / "config.yml").symlink_to("../../outside-the-repo.yml")
        capsys.readouterr()
        assert adopt_command(str(other)) == 0
        out = capsys.readouterr().out
        assert (other / ".gitignore").read_text() == "node_modules/\n"
        assert "__pycache__/" in out and ".gitignore" in out
        assert not (tmp_path / "outside-the-repo.yml").exists()
        assert "dhf/config.yml" in out.split("Skipped")[1]
