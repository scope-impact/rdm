"""Acceptance test for the scaffolding context's design input (see dhf/).

The acceptance criterion ("live BDD") for DI-15, tagged `@allure.story`,
exercising the real `rdm init` scaffolder. Lightweight on purpose: the end-to-end
"the scaffold builds a release" check lives in `fresh_release_test.py` (it needs
Pandoc/make), not here.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.init import init

allure = pytest.importorskip("allure")


@allure.story("DI-15")
@allure.label("output", "rdm/init.py")
def test_init_scaffolds_a_project(tmp_path: Path) -> None:
    """DI-15: `rdm init` lays down the templates, Makefile, and render config."""
    project = tmp_path / "regulatory"  # must not pre-exist (copytree)
    init(str(project))

    # the build skeleton
    assert (project / "Makefile").is_file()
    assert (project / "config.yml").is_file()
    assert (project / "documents").is_dir()

    # the design-controls templates ship, so new projects inherit the model
    docs = project / "documents"
    assert (docs / "software_design_specification.md").is_file()  # the kind:design template
    assert (docs / "design_review.md").is_file()
    assert (docs / "traceability_matrix.md").is_file()
    assert "kind: design" in (docs / "software_design_specification.md").read_text()


@allure.story("DI-22")
@allure.label("output", "rdm/init_files/AGENTS.md")
def test_init_scaffolds_an_agent_operating_procedure(tmp_path: Path) -> None:
    """DI-22: the scaffold lays down an AGENTS.md documenting the record-first workflow."""
    project = tmp_path / "regulatory"  # must not pre-exist (copytree)
    init(str(project))

    agents = project / "AGENTS.md"
    assert agents.is_file()
    text = agents.read_text()

    # design approved (committed) before implementation
    assert "Design record first, committed first" in text
    assert "that commit *is* the approval" in text

    # each design input verified by an @allure.story-tagged acceptance test
    assert '@allure.story("DI-…")' in text

    # the design/release gates run via rdm story
    assert "rdm story design-gate" in text
    assert "rdm story release-gate" in text
    assert "rdm story verify" in text
    assert "rdm story faithfulness" in text

    prose = " ".join(text.replace("**", "").split())  # unwrap lines, drop emphasis

    # EACH design input is verified — the universality, not just the mechanism
    assert "each design input is verified by an automated test tagged" in prose

    # independent faithfulness review of EVERY verifying test
    assert (
        "Every new or edited tagged test needs a verdict recorded by a reviewer "
        "independent of the test's author" in prose
    )
    assert "rdm story verdict" in text
    assert "Never record a verdict for a test you authored" in text
