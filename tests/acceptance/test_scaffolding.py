"""Acceptance tests for the scaffolding context's design inputs (see dhf/).

The acceptance criteria ("live BDD") for DI-15 (`rdm init`) and DI-22
(`rdm story new-input`), tagged `@allure.story`, exercising the real
scaffolders. Lightweight on purpose: the end-to-end "the scaffold builds a
release" check lives in `fresh_release_test.py` (it needs Pandoc/make), not here.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.init import init
from rdm.record.sdd import design_inputs
from rdm.story_audit.new_input import story_new_input_command

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


def _mini_dhf(tmp_path: Path) -> Path:
    """A minimal DHF: one design doc owning DI-1/DI-3, one registered user need."""
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "vv_plan.md").write_text(
        "---\nuser_needs:\n  - {id: UN-001, text: 'a need'}\n---\n"
    )
    (dhf / "documents" / "design" / "alarms.md").write_text(
        "---\n"
        "id: SDS-A-001\n"
        "kind: design\n"
        "context: alarms\n"
        "satisfies: [UN-001]\n"
        "design_inputs:\n"
        "  - id: DI-1\n"
        '    text: "existing input"\n'
        "    traces_to: [UN-001]\n"
        "  - id: DI-3\n"
        '    text: "another input"\n'
        "    traces_to: [UN-001]\n"
        "---\n\n# Alarms\n"
    )
    (tmp_path / "tests").mkdir()
    return dhf


@allure.story("DI-22")
@allure.label("output", "rdm/story_audit/new_input.py")
def test_new_input_scaffolds_a_traced_design_input(tmp_path: Path, capsys) -> None:
    """DI-22: next unused id allocated, frontmatter entry inserted, failing tagged
    stub test emitted, checklist printed; unknown context/user need rejected."""
    dhf = _mini_dhf(tmp_path)

    assert story_new_input_command(
        dhf_dir=dhf, context="alarms", text="RDM shall beep.", traces_to="UN-001"
    ) == 0
    out = capsys.readouterr().out

    # Allocates the next UNUSED id: max(DI-1, DI-3) + 1, not first-gap or count.
    # The entry lands in the chosen context's design_inputs frontmatter, traced.
    declared = {di["id"]: di for di in design_inputs(dhf)}
    assert set(declared) == {"DI-1", "DI-3", "DI-4"}
    assert declared["DI-4"]["text"] == "RDM shall beep."
    assert declared["DI-4"]["traces_to"] == ["UN-001"]
    assert declared["DI-4"]["context"] == "alarms"

    # Emits a stub acceptance test tagged with the new id that FAILS until
    # implemented (honest red at the release gate), and prints the checklist.
    stub = tmp_path / "tests" / "acceptance" / "test_alarms.py"
    assert '@allure.story("DI-4")' in stub.read_text()
    result = pytest.main(["-q", "--no-header", "-p", "no:cacheprovider", str(stub)])
    assert result == pytest.ExitCode.TESTS_FAILED
    assert "checklist" in out and "DI-4" in out

    # Rejects an unknown context and an unknown user need (nothing scaffolded).
    assert story_new_input_command(
        dhf_dir=dhf, context="nope", text="x", traces_to="UN-001"
    ) != 0
    assert story_new_input_command(
        dhf_dir=dhf, context="alarms", text="x", traces_to="UN-999"
    ) != 0
    assert set(di["id"] for di in design_inputs(dhf)) == {"DI-1", "DI-3", "DI-4"}
