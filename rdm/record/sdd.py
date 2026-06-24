"""
Read the Software Design Specification (SDD) and other DHF documents.

The SDD frontmatter declares the user-need IDs that are the traceability source
of truth (each is referenced by an Allure tag on the verifying test). This
module parses those IDs and locates DHF documents on disk; it has no dependency
on the story-audit / pydantic layer so the record pipeline stays lightweight.
"""

from __future__ import annotations

from pathlib import Path

import yaml

# The SDD document's basename, as installed by `rdm init`.
SDD_DOC = "software_design_specification.md"

# Frontmatter fields the SDD may use to list the user-need IDs it captures.
SDD_USER_NEED_FIELDS = ("user_needs", "user_need_ids", "ids")


def find_dhf_doc(dhf_dir: Path, basename: str) -> Path | None:
    """Locate a DHF document by basename.

    Checks ``<dhf>/documents/<basename>`` first (the layout produced by
    ``rdm init``), then falls back to a recursive search so rendered or
    relocated copies are still found.
    """
    preferred = dhf_dir / "documents" / basename
    if preferred.exists():
        return preferred
    matches = sorted(dhf_dir.rglob(basename))
    return matches[0] if matches else None


def parse_frontmatter(text: str) -> dict:
    """Parse a YAML frontmatter block delimited by leading ``---`` fences."""
    if not text.lstrip().startswith("---"):
        return {}
    parts = text.split("---", 2)
    if len(parts) < 3:
        return {}
    try:
        data = yaml.safe_load(parts[1])
    except yaml.YAMLError:
        return {}
    return data if isinstance(data, dict) else {}


def user_need_ids(dhf_dir: Path) -> set[str]:
    """Return the user-need IDs declared in the SDD frontmatter."""
    path = find_dhf_doc(dhf_dir, SDD_DOC)
    if path is None:
        return set()
    frontmatter = parse_frontmatter(path.read_text(encoding="utf-8"))
    ids: set[str] = set()
    for field_name in SDD_USER_NEED_FIELDS:
        value = frontmatter.get(field_name)
        if isinstance(value, list):
            ids.update(str(v).strip() for v in value if str(v).strip())
    return ids
