"""
Tests for the design-controls gate.

The gate ensures a Design Input document and a Design Review document exist and
are complete (no scaffold placeholders) before design work transitions into
backlog/implementation tasks.
"""

from __future__ import annotations

from pathlib import Path

from rdm.story_audit.design_gate import (
    DESIGN_INPUT_DOC,
    DESIGN_REVIEW_DOC,
    check_artifact,
    run_design_gate,
    story_design_gate_command,
)

COMPLETE_DOC = "# Design Input\n\nThe inputs are defined and approved.\n"


def _make_dhf(tmp_path: Path, input_text: str | None, review_text: str | None) -> Path:
    dhf = tmp_path / "dhf"
    docs = dhf / "documents"
    docs.mkdir(parents=True)
    if input_text is not None:
        (docs / DESIGN_INPUT_DOC).write_text(input_text)
    if review_text is not None:
        (docs / DESIGN_REVIEW_DOC).write_text(review_text)
    return dhf


class TestCheckArtifact:
    def test_missing_document_fails(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, None, None)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert not result.exists
        assert not result.ok
        assert result.reasons

    def test_placeholder_document_is_incomplete(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, "TODO: fill this in\nENDTODO\n", None)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert result.exists
        assert not result.complete
        assert any("placeholder" in r for r in result.reasons)

    def test_complete_document_passes(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, COMPLETE_DOC, None)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert result.ok

    def test_empty_document_is_incomplete(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, "   \n", None)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert not result.complete

    def test_document_found_outside_documents_dir(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        (dhf / "sub").mkdir(parents=True)
        (dhf / "sub" / DESIGN_INPUT_DOC).write_text(COMPLETE_DOC)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert result.ok


class TestRunDesignGate:
    def test_gate_passes_when_both_complete(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, COMPLETE_DOC, COMPLETE_DOC)
        result = run_design_gate(dhf)
        assert result.passed

    def test_gate_fails_when_review_missing(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, COMPLETE_DOC, None)
        result = run_design_gate(dhf)
        assert not result.passed

    def test_gate_fails_when_input_incomplete(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, "TODO: x\nENDTODO", COMPLETE_DOC)
        result = run_design_gate(dhf)
        assert not result.passed


class TestStoryDesignGateCommand:
    def test_missing_dhf_returns_2(self, tmp_path: Path) -> None:
        assert story_design_gate_command(dhf_dir=tmp_path / "nope") == 2

    def test_complete_dhf_returns_0(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, COMPLETE_DOC, COMPLETE_DOC)
        assert story_design_gate_command(dhf_dir=dhf) == 0

    def test_incomplete_dhf_returns_1(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, COMPLETE_DOC, None)
        assert story_design_gate_command(dhf_dir=dhf) == 1


class TestInstalledTemplates:
    """The init templates must ship and render the gate's required documents."""

    def test_templates_exist_in_init_files(self) -> None:
        docs = Path(__file__).resolve().parents[1] / "rdm" / "init_files" / "documents"
        assert (docs / DESIGN_INPUT_DOC).exists()
        assert (docs / DESIGN_REVIEW_DOC).exists()
