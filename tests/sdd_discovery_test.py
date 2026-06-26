"""Tests for design-doc discovery (kind: design) and satisfies/registry reconciliation."""

from __future__ import annotations

from pathlib import Path

from rdm.record.sdd import (
    design_input_ids,
    find_design_docs,
    realises_by_context,
    registry_user_needs,
    satisfied_user_needs,
    satisfies_for,
)
from tests.util import write_design_doc


def _doc(path: Path, frontmatter: str = "", body: str = "body") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fm = f"---\n{frontmatter}\n---\n\n" if frontmatter else ""
    path.write_text(f"{fm}{body}\n")


class TestFindDesignDocs:
    def test_matches_kind_design_anywhere(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        docs = dhf / "documents"
        write_design_doc(docs / "design", "auth", satisfies=("UN-002",))
        write_design_doc(docs, "alerting", satisfies=("UN-001",))  # any path, any name
        _doc(docs / "architecture.md", "id: ARCH-001")            # no kind -> NOT a design doc
        _doc(docs / "verification_and_validation_plan.md", "user_needs: [UN-001]")

        names = {p.name for p in find_design_docs(dhf)}
        assert names == {"auth.md", "alerting.md"}

    def test_discovery_ignores_filename_and_folder(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        # A file literally named sdd/ or *sdd* is NOT a design doc unless tagged.
        _doc(dhf / "documents" / "sdd" / "legacy.md", "id: X")
        assert find_design_docs(dhf) == []

    def test_no_design_docs(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        _doc(dhf / "documents" / "architecture.md")
        assert find_design_docs(dhf) == []


class TestSatisfies:
    def test_satisfies_for_reads_list(self, tmp_path: Path) -> None:
        p = write_design_doc(tmp_path / "dhf" / "design", "auth", satisfies=("UN-002", "UN-003"))
        assert satisfies_for(p) == {"UN-002", "UN-003"}

    def test_satisfied_union_across_docs(self, tmp_path: Path) -> None:
        docs = tmp_path / "dhf" / "design"
        write_design_doc(docs, "auth", satisfies=("UN-002", "UN-003"))
        write_design_doc(docs, "alerting", satisfies=("UN-001",))
        write_design_doc(docs, "dashboard", satisfies=("UN-001", "UN-002"))
        assert satisfied_user_needs(tmp_path / "dhf") == {"UN-001", "UN-002", "UN-003"}


class TestDesignInputs:
    def test_union_across_docs_with_first_id_winning(self, tmp_path: Path) -> None:
        docs = tmp_path / "dhf" / "design"
        write_design_doc(docs, "record", design_inputs=(("DI-1", ["UN-001"]),))
        write_design_doc(docs, "gating", design_inputs=(("DI-2", ["UN-002"]), ("DI-3", ["UN-003"])))
        assert design_input_ids(tmp_path / "dhf") == {"DI-1", "DI-2", "DI-3"}

    def test_realises_collected(self, tmp_path: Path) -> None:
        docs = tmp_path / "dhf" / "design"
        write_design_doc(docs, "record", design_inputs=(("DI-1", ["UN-001"]),))
        rendering = write_design_doc(docs, "rendering", satisfies=("UN-001",), realises=("DI-1",))
        refs = realises_by_context(tmp_path / "dhf")
        assert refs[rendering] == {"DI-1"}


class TestRegistry:
    def test_registry_reads_user_needs_from_vv_plan(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        _doc(
            dhf / "documents" / "verification_and_validation_plan.md",
            "user_needs:\n  - {id: UN-001, text: a}\n  - {id: UN-002, text: b}",
        )
        write_design_doc(dhf / "documents" / "design", "auth", satisfies=("UN-001",))
        assert registry_user_needs(dhf) == {"UN-001", "UN-002"}
