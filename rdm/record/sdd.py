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


def user_needs_from_doc(doc_path: Path) -> set[str]:
    """Return user-need IDs from a document's frontmatter ``user_needs`` list.

    Per ADR 0001 the user-need registry lives in the V&V plan; this reads it
    from any document's frontmatter. Accepts both ``{id, text}`` mappings and
    bare string IDs.
    """
    if not doc_path.exists():
        return set()
    frontmatter = parse_frontmatter(doc_path.read_text(encoding="utf-8"))
    value = frontmatter.get("user_needs")
    if not isinstance(value, list):
        return set()
    ids: set[str] = set()
    for item in value:
        if isinstance(item, dict) and str(item.get("id", "")).strip():
            ids.add(str(item["id"]).strip())
        elif not isinstance(item, dict) and str(item).strip():
            ids.add(str(item).strip())
    return ids


def find_sdds(dhf_dir: Path) -> list[Path]:
    """Discover SDD documents under a DHF.

    A markdown file is treated as an SDD when any of these hold (the project may
    have many SDDs, one per bounded context):

    - it lives under a folder named ``sdd`` (case-insensitive), or
    - its filename stem starts or ends with ``sdd`` (case-insensitive), e.g.
      ``sdd-auth.md`` / ``auth-sdd.md`` / ``sdd.md``, or
    - it is the legacy ``software_design_specification.md``.
    """
    found: set[Path] = set()
    for md in dhf_dir.rglob("*.md"):
        stem = md.stem.lower()
        in_sdd_folder = any(part.lower() == "sdd" for part in md.parent.parts)
        if in_sdd_folder or stem.startswith("sdd") or stem.endswith("sdd") or md.name == SDD_DOC:
            found.add(md)
    return sorted(found)


def satisfies_for(sdd_path: Path) -> set[str]:
    """Return the user-need IDs a single SDD declares it ``satisfies``."""
    if not sdd_path.exists():
        return set()
    frontmatter = parse_frontmatter(sdd_path.read_text(encoding="utf-8"))
    value = frontmatter.get("satisfies")
    if not isinstance(value, list):
        return set()
    return {str(v).strip() for v in value if str(v).strip()}


def satisfies_by_sdd(dhf_dir: Path) -> dict[Path, set[str]]:
    """Map each discovered SDD to the user-need IDs it ``satisfies``."""
    return {sdd: satisfies_for(sdd) for sdd in find_sdds(dhf_dir)}


def satisfied_user_needs(dhf_dir: Path) -> set[str]:
    """Union of user needs addressed (via ``satisfies``) across all SDDs."""
    ids: set[str] = set()
    for refs in satisfies_by_sdd(dhf_dir).values():
        ids |= refs
    return ids


def registry_user_needs(dhf_dir: Path) -> set[str]:
    """Union of ``user_needs`` declared in any document frontmatter under the DHF.

    Per ADR 0001 the registry lives in the V&V plan, but this finds it wherever
    it is authored (and remains compatible with declaring it in an SDD).
    """
    ids: set[str] = set()
    for md in dhf_dir.rglob("*.md"):
        ids |= user_needs_from_doc(md)
    return ids
