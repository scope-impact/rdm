"""
Tests for the design-controls gate.

The gate ensures at least one per-context design document (`kind: design`,
carrying its design inputs and outputs) and a Design Review document exist and
are complete (no scaffold placeholders) and approved (committed) before design
work transitions into backlog/implementation tasks.
"""

from __future__ import annotations

from pathlib import Path

from rdm.record.allure import scan_source_tags as allure_tag_ids
from rdm.story_audit.design_gate import (
    DESIGN_REVIEW_DOC,
    check_artifact,
    check_design_docs,
    check_doc_path,
    has_uncommitted_changes,
    run_design_gate,
    story_design_gate_command,
)
from tests.util import COMPLETE_DOC
from tests.util import git_run as _git
from tests.util import write_design_doc


def _proj(
    tmp_path: Path,
    design_input_ids: list[str],
    allure_ids: list[str],
    user_needs: list[str] | None = None,
) -> Path:
    """Build <tmp>/proj/{dhf,tests} with a per-context design doc and allure-tagged
    tests. Traceability is reconciled against design inputs.
    """
    proj = tmp_path / "proj"
    docs = proj / "dhf" / "documents"
    docs.mkdir(parents=True)
    if design_input_ids:
        write_design_doc(
            docs / "design",
            "core",
            satisfies=tuple(user_needs or []),
            design_inputs=tuple((di, []) for di in design_input_ids),
        )
    elif user_needs:
        (docs / "verification_and_validation_plan.md").write_text(
            "---\nid: VVP-001\nuser_needs: [" + ", ".join(user_needs) + "]\n---\n\nplan\n"
        )
    tests = proj / "tests"
    tests.mkdir()
    body = "import allure\n"
    for i, aid in enumerate(allure_ids):
        body += f'@allure.story("{aid}")\ndef test_{i}(): pass\n'
    (tests / "test_feature.py").write_text(body)
    return proj / "dhf"


def _git_dhf(tmp_path: Path, commit: bool) -> Path:
    """Create a DHF inside a git repo with a design doc + review; optionally commit."""
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init")
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    write_design_doc(docs / "design", "core", satisfies=("UN-001",),
                     design_inputs=(("DI-1", ["UN-001"]),))
    (docs / DESIGN_REVIEW_DOC).write_text(COMPLETE_DOC)
    if commit:
        _git(repo, "add", "-A")
        _git(repo, "commit", "-m", "add design docs")
    return repo / "dhf"


def _make_dhf(
    tmp_path: Path,
    *,
    design_doc: bool = True,
    review_text: str | None = COMPLETE_DOC,
    design_inputs: tuple[tuple[str, list[str]], ...] = (("DI-1", ["UN-001"]),),
    design_text: str | None = None,
) -> Path:
    dhf = tmp_path / "dhf"
    docs = dhf / "documents"
    docs.mkdir(parents=True)
    if design_doc:
        if design_text is not None:
            (docs / "design").mkdir(parents=True, exist_ok=True)
            (docs / "design" / "core.md").write_text(design_text)
        else:
            write_design_doc(docs / "design", "core", satisfies=("UN-001",),
                             design_inputs=design_inputs)
    if review_text is not None:
        (docs / DESIGN_REVIEW_DOC).write_text(review_text)
    return dhf


class TestCheckDocPath:
    def test_placeholder_document_is_incomplete(self, tmp_path: Path) -> None:
        p = tmp_path / "d.md"
        p.write_text("TODO: fill this in\nENDTODO\n")
        result = check_doc_path(p, "Design Review")
        assert result.exists
        assert not result.complete
        assert any("placeholder" in r for r in result.reasons)

    def test_complete_document_passes(self, tmp_path: Path) -> None:
        p = tmp_path / "d.md"
        p.write_text(COMPLETE_DOC)
        assert check_doc_path(p, "Design Review").ok

    def test_empty_document_is_incomplete(self, tmp_path: Path) -> None:
        p = tmp_path / "d.md"
        p.write_text("   \n")
        assert not check_doc_path(p, "Design Review").complete


class TestCheckArtifact:
    def test_missing_document_fails(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, review_text=None)
        result = check_artifact(dhf, DESIGN_REVIEW_DOC, "Design Review")
        assert not result.exists
        assert not result.ok
        assert result.reasons

    def test_document_found_outside_documents_dir(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        (dhf / "sub").mkdir(parents=True)
        (dhf / "sub" / DESIGN_REVIEW_DOC).write_text(COMPLETE_DOC)
        result = check_artifact(dhf, DESIGN_REVIEW_DOC, "Design Review")
        assert result.ok


class TestCheckDesignDocs:
    def test_no_design_doc_fails(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, design_doc=False)
        checks = check_design_docs(dhf)
        assert len(checks) == 1
        assert not checks[0].exists
        assert not checks[0].ok

    def test_design_doc_present_and_complete_passes(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path)
        checks = check_design_docs(dhf)
        assert all(c.ok for c in checks)
        assert "core" in checks[0].name

    def test_incomplete_design_doc_fails(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, design_text="---\nkind: design\ncontext: core\n---\nTODO\nENDTODO\n")
        checks = check_design_docs(dhf)
        assert not checks[0].complete


class TestRunDesignGate:
    def test_gate_passes_when_both_complete(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path)
        assert run_design_gate(dhf).passed

    def test_gate_fails_when_review_missing(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, review_text=None)
        assert not run_design_gate(dhf).passed

    def test_gate_fails_when_no_design_doc(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, design_doc=False)
        assert not run_design_gate(dhf).passed

    def test_gate_fails_when_design_doc_incomplete(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, design_text="---\nkind: design\ncontext: core\n---\nTODO\nENDTODO\n")
        assert not run_design_gate(dhf).passed


class TestStoryDesignGateCommand:
    def test_missing_dhf_returns_2(self, tmp_path: Path) -> None:
        assert story_design_gate_command(dhf_dir=tmp_path / "nope") == 2

    def test_complete_dhf_returns_0(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path)
        assert story_design_gate_command(dhf_dir=dhf) == 0

    def test_incomplete_dhf_returns_1(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path, review_text=None)
        assert story_design_gate_command(dhf_dir=dhf) == 1


class TestVersionControlApproval:
    """Approval is the version-control record, so uncommitted == not approved."""

    def test_non_git_path_is_undetermined(self, tmp_path: Path) -> None:
        dhf = _make_dhf(tmp_path)
        result = check_artifact(dhf, DESIGN_REVIEW_DOC, "Design Review")
        # Cannot determine VCS state outside a repo: undetermined, not a failure.
        assert result.uncommitted is None
        assert result.ok

    def test_committed_doc_is_approved(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=True)
        result = check_artifact(dhf, DESIGN_REVIEW_DOC, "Design Review")
        assert result.uncommitted is False
        assert result.ok

    def test_untracked_doc_is_not_approved(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=False)
        result = check_artifact(dhf, DESIGN_REVIEW_DOC, "Design Review")
        assert result.uncommitted is True
        assert not result.ok
        assert any("uncommitted" in r for r in result.reasons)

    def test_modified_after_commit_reopens_approval(self, tmp_path: Path) -> None:
        dhf = _git_dhf(tmp_path, commit=True)
        # Baseline drift: edit the approved doc -> gate must fail again.
        (dhf / "documents" / DESIGN_REVIEW_DOC).write_text(COMPLETE_DOC + "\nedited\n")
        result = check_artifact(dhf, DESIGN_REVIEW_DOC, "Design Review")
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


class TestDesignInputAllureReconciliation:
    """Design-input IDs (declared in design docs) must be reconciled against Allure
    tags -- verification is anchored on design inputs, not user needs."""

    def test_allure_tags_extracted_from_tests(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["DI-1"], ["DI-1", "DI-2"])
        tagged = allure_tag_ids(dhf.parent / "tests")
        assert set(tagged) == {"DI-1", "DI-2"}

    def test_design_input_without_tag_is_warned(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["DI-1", "DI-2"], ["DI-1"])
        result = run_design_gate(dhf)
        assert any("DI-2" in w for w in result.traceability_warnings)
        assert not any("DI-1" in w for w in result.traceability_warnings)

    def test_orphan_tag_sharing_prefix_is_warned(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["DI-1"], ["DI-1", "DI-999"])
        result = run_design_gate(dhf)
        assert any("DI-999" in w for w in result.traceability_warnings)

    def test_unrelated_prefix_tag_is_not_warned(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, ["DI-1"], ["DI-1", "FT-001"])
        result = run_design_gate(dhf)
        assert not any("FT-001" in w for w in result.traceability_warnings)

    def test_traceability_gaps_do_not_fail_the_gate(self, tmp_path: Path) -> None:
        # Review absent here, so the gate fails on docs -- but traceability gaps
        # themselves must never be the cause of failure.
        dhf = _proj(tmp_path, ["DI-1"], [])  # design input, no test yet
        result = run_design_gate(dhf)
        assert result.traceability_warnings  # gap reported
        assert result.passed is all(a.ok for a in result.artifacts)

    def test_no_design_inputs_means_no_traceability_warnings(self, tmp_path: Path) -> None:
        dhf = _proj(tmp_path, [], ["UN-001"])
        assert run_design_gate(dhf).traceability_warnings == []


class TestInstalledTemplates:
    """The init templates must ship the gate's required documents."""

    def test_templates_exist_in_init_files(self) -> None:
        docs = Path(__file__).resolve().parents[1] / "rdm" / "init_files" / "documents"
        assert (docs / DESIGN_REVIEW_DOC).exists()
        # The combined per-context design document (kind: design) ships too.
        sdd = docs / "software_design_specification.md"
        assert sdd.exists()
        assert "kind: design" in sdd.read_text()
