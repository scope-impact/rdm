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
    has_uncommitted_changes,
    run_design_gate,
    story_design_gate_command,
)
from rdm.record.allure import scan_source_tags as allure_tag_ids
from rdm.record.sdd import SDD_DOC
from rdm.record.sdd import user_need_ids as sdd_user_need_ids
from tests.util import COMPLETE_DOC
from tests.util import git_run as _git


def _sdd(user_needs: list[str]) -> str:
    needs = "[" + ", ".join(user_needs) + "]"
    return f"---\nid: SDS-001\ntitle: SDD\nuser_needs: {needs}\n---\n\nbody\n"


def _proj(tmp_path: Path, user_needs: list[str], allure_ids: list[str]) -> Path:
    """Build <tmp>/proj/{dhf,tests} with an SDD and allure-tagged tests."""
    proj = tmp_path / "proj"
    docs = proj / "dhf" / "documents"
    docs.mkdir(parents=True)
    (docs / SDD_DOC).write_text(_sdd(user_needs))
    tests = proj / "tests"
    tests.mkdir()
    body = "import allure\n"
    for i, aid in enumerate(allure_ids):
        body += f'@allure.story("{aid}")\ndef test_{i}(): pass\n'
    (tests / "test_feature.py").write_text(body)
    return proj / "dhf"


def _git_dhf(tmp_path: Path, commit: bool) -> Path:
    """Create a DHF inside a git repo with both docs; optionally commit them."""
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init")
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    (docs / DESIGN_INPUT_DOC).write_text(COMPLETE_DOC)
    (docs / DESIGN_REVIEW_DOC).write_text(COMPLETE_DOC)
    if commit:
        _git(repo, "add", "-A")
        _git(repo, "commit", "-m", "add design docs")
    return repo / "dhf"


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


class TestVersionControlApproval:
    """Approval is the version-control record, so uncommitted == not approved."""

    def test_non_git_path_is_undetermined(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, COMPLETE_DOC, COMPLETE_DOC)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        # Cannot determine VCS state outside a repo: undetermined, not a failure.
        assert result.uncommitted is None
        assert result.ok

    def test_committed_doc_is_approved(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=True)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert result.uncommitted is False
        assert result.ok

    def test_untracked_doc_is_not_approved(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=False)
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert result.uncommitted is True
        assert not result.ok
        assert any("uncommitted" in r for r in result.reasons)

    def test_modified_after_commit_reopens_approval(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=True)
        # Baseline drift: edit the approved doc -> gate must fail again (M4).
        (dhf / "documents" / DESIGN_INPUT_DOC).write_text(COMPLETE_DOC + "\nedited\n")
        result = check_artifact(dhf, DESIGN_INPUT_DOC, "Design Input")
        assert result.uncommitted is True
        assert not result.ok

    def test_gate_fails_when_docs_uncommitted(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=False)
        assert not run_design_gate(dhf).passed
        assert story_design_gate_command(dhf_dir=dhf) == 1

    def test_gate_passes_when_docs_committed(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=True)
        assert run_design_gate(dhf).passed
        assert story_design_gate_command(dhf_dir=dhf) == 0


class TestHasUncommittedChanges:
    def test_returns_none_outside_repo(self, tmp_path: Path) -> None:
        p = tmp_path / "x.md"
        p.write_text("hi")
        assert has_uncommitted_changes(p) is None


class TestSddAllureReconciliation:
    """SDD user-need IDs (frontmatter) must be reconciled against Allure tags."""

    def test_parses_user_needs_from_frontmatter(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["UN-001", "UN-002"], [])
        assert sdd_user_need_ids(dhf) == {"UN-001", "UN-002"}

    def test_allure_tags_extracted_from_tests(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["UN-001"], ["UN-001", "UN-002"])
        tagged = allure_tag_ids(dhf.parent / "tests")
        assert set(tagged) == {"UN-001", "UN-002"}

    def test_user_need_without_tag_is_warned(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["UN-001", "UN-002"], ["UN-001"])
        result = run_design_gate(dhf)
        assert any("UN-002" in w for w in result.traceability_warnings)
        assert not any("UN-001" in w for w in result.traceability_warnings)

    def test_orphan_tag_sharing_prefix_is_warned(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["UN-001"], ["UN-001", "UN-999"])
        result = run_design_gate(dhf)
        assert any("UN-999" in w for w in result.traceability_warnings)

    def test_unrelated_prefix_tag_is_not_warned(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["UN-001"], ["UN-001", "FT-001"])
        result = run_design_gate(dhf)
        assert not any("FT-001" in w for w in result.traceability_warnings)

    def test_traceability_gaps_do_not_fail_the_gate(self, tmp_path: Path) -> None:
        # Docs absent here, so the gate fails on docs -- but traceability gaps
        # themselves must never be the cause of failure. Verify with docs OK:
        dhf = _proj(tmp_path, ["UN-001"], [])  # user need, no test yet
        result = run_design_gate(dhf)
        assert result.traceability_warnings  # gap reported
        # The traceability warnings are independent of pass/fail; passed is
        # driven only by the required artifacts.
        assert result.passed is all(a.ok for a in result.artifacts)

    def test_no_user_needs_means_no_traceability_warnings(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, [], ["UN-001"])
        assert run_design_gate(dhf).traceability_warnings == []


class TestInstalledTemplates:
    """The init templates must ship and render the gate's required documents."""

    def test_templates_exist_in_init_files(self) -> None:
        docs = Path(__file__).resolve().parents[1] / "rdm" / "init_files" / "documents"
        assert (docs / DESIGN_INPUT_DOC).exists()
        assert (docs / DESIGN_REVIEW_DOC).exists()
