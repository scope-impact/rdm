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
    """A minimal DHF: two design contexts (so context *choice* is observable),
    design inputs DI-1/DI-3 taken, one registered user need."""
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "vv_plan.md").write_text(
        "---\nuser_needs:\n"
        "  - {id: UN-001, text: 'a need'}\n"
        "  - {id: UN-002, text: 'another need'}\n"
        "---\n"
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
        "---\n\n# Alarms\n"
    )
    (dhf / "documents" / "design" / "trends.md").write_text(
        "---\n"
        "id: SDS-T-001\n"
        "kind: design\n"
        "context: trends\n"
        "satisfies: [UN-001]\n"
        "design_inputs:\n"
        "  - id: DI-3\n"
        '    text: "another input"\n'
        "    traces_to: [UN-001]\n"
        "---\n\n# Trends\n"
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
        dhf_dir=dhf, context="alarms", text="RDM shall beep.", traces_to="UN-002"
    ) == 0
    out = capsys.readouterr().out

    # Allocates the next UNUSED id: max(DI-1, DI-3) + 1, not first-gap or count.
    # The entry lands in the CHOSEN context's design_inputs frontmatter (and not
    # in the other context's), traced to the user need.
    declared = {di["id"]: di for di in design_inputs(dhf)}
    assert set(declared) == {"DI-1", "DI-3", "DI-4"}
    assert declared["DI-4"]["text"] == "RDM shall beep."
    assert declared["DI-4"]["traces_to"] == ["UN-002"]
    assert declared["DI-4"]["context"] == "alarms"
    alarms_text = (dhf / "documents" / "design" / "alarms.md").read_text()
    assert "DI-4" in alarms_text
    assert "DI-4" not in (dhf / "documents" / "design" / "trends.md").read_text()

    # The newly referenced user need joins the owning context's satisfies list
    # (UN-002 was not there; UN-001 stays).
    assert "satisfies: [UN-001, UN-002]" in alarms_text

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


@allure.story("DI-24")
@allure.label("output", "rdm/adopt.py")
def test_adopt_brings_existing_repo_under_controls(tmp_path: Path, capsys) -> None:
    """DI-24: one command lays down the DHF skeleton, runbook, design-gate hook,
    session bootstrap, and CI workflow into an existing repo, skipping (never
    overwriting) anything that already exists."""
    import os

    from rdm.adopt import adopt_command

    repo = tmp_path / "legacy"
    (repo / "src").mkdir(parents=True)
    (repo / "src" / "app.py").write_text("print('legacy')\n")
    (repo / "dhf").mkdir()
    sentinel = "# our own pre-existing DHF notes\n"
    (repo / "dhf" / "README.md").write_text(sentinel)

    assert adopt_command(str(repo)) == 0
    out = capsys.readouterr().out

    # The control surface lands: DHF skeleton...
    docs = repo / "dhf" / "documents"
    assert (docs / "verification_and_validation_plan.md").is_file()   # V&V plan
    design_template = docs / "design" / "example_context.md"
    assert "kind: design" in design_template.read_text()              # context template
    assert (docs / "design_review.md").is_file()                      # design review
    assert (docs / "traceability_matrix.md").is_file()                # matrix template
    # ...the runbook, the hook, the bootstrap, and the CI workflow.
    assert (repo / "dhf" / "AGENT_WORKFLOW.md").is_file()
    hook = repo / ".githooks" / "pre-commit"
    assert "design-gate" in hook.read_text() and os.access(hook, os.X_OK)
    bootstrap = repo / "scripts" / "agent-bootstrap.sh"
    assert os.access(bootstrap, os.X_OK)
    assert "agent-bootstrap" in (repo / ".claude" / "settings.json").read_text()
    assert "rdm story design-gate" in (repo / ".github" / "workflows" / "design-controls.yml").read_text()

    # Pre-existing files are skipped, never overwritten — and reported.
    assert (repo / "dhf" / "README.md").read_text() == sentinel
    assert "dhf/README.md" in out and "Skipped" in out
    assert (repo / "src" / "app.py").read_text() == "print('legacy')\n"

    # Re-running is safe: everything now exists, so nothing is copied.
    assert adopt_command(str(repo)) == 0
    rerun_out = capsys.readouterr().out
    assert "Laid down:" not in rerun_out
