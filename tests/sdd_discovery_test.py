"""Tests for multi-SDD discovery and satisfies/registry reconciliation."""

from __future__ import annotations

from pathlib import Path

from rdm.record.sdd import (
    find_sdds,
    registry_user_needs,
    satisfied_user_needs,
    satisfies_for,
)


def _doc(path: Path, frontmatter: str = "", body: str = "body") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fm = f"---\n{frontmatter}\n---\n\n" if frontmatter else ""
    path.write_text(f"{fm}{body}\n")


class TestFindSdds:
    def test_matches_folder_prefix_suffix_and_legacy(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        _doc(dhf / "documents" / "sdd" / "auth.md")              # under sdd/ folder
        _doc(dhf / "documents" / "sdd-ingestion.md")             # prefix
        _doc(dhf / "documents" / "alerting-sdd.md")              # suffix
        _doc(dhf / "documents" / "software_design_specification.md")  # legacy
        _doc(dhf / "documents" / "architecture.md")              # NOT an SDD
        _doc(dhf / "documents" / "verification_and_validation_plan.md")  # NOT an SDD

        names = {p.name for p in find_sdds(dhf)}
        assert names == {
            "auth.md",
            "sdd-ingestion.md",
            "alerting-sdd.md",
            "software_design_specification.md",
        }

    def test_no_sdds(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        _doc(dhf / "documents" / "architecture.md")
        assert find_sdds(dhf) == []


class TestSatisfies:
    def test_satisfies_for_reads_list(self, tmp_path: Path) -> None:
        p = tmp_path / "dhf" / "sdd" / "auth.md"
        _doc(p, "context: auth\nsatisfies: [UN-002, UN-003]")
        assert satisfies_for(p) == {"UN-002", "UN-003"}

    def test_satisfied_union_across_sdds(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        _doc(dhf / "sdd" / "auth.md", "satisfies: [UN-002, UN-003]")
        _doc(dhf / "sdd" / "alerting.md", "satisfies: [UN-001]")
        _doc(dhf / "sdd" / "dashboard.md", "satisfies: [UN-001, UN-002]")
        assert satisfied_user_needs(dhf) == {"UN-001", "UN-002", "UN-003"}


class TestRegistry:
    def test_registry_reads_user_needs_from_vv_plan(self, tmp_path: Path) -> None:
        dhf = tmp_path / "dhf"
        _doc(
            dhf / "documents" / "verification_and_validation_plan.md",
            "user_needs:\n  - {id: UN-001, text: a}\n  - {id: UN-002, text: b}",
        )
        # an SDD with satisfies but no user_needs of its own
        _doc(dhf / "documents" / "sdd" / "auth.md", "satisfies: [UN-001]")
        assert registry_user_needs(dhf) == {"UN-001", "UN-002"}
