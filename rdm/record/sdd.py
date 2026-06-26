"""
Read the per-context design documents and other DHF documents.

The unit of design is the **bounded context**, captured in **one document per
context** (``kind: design`` in its frontmatter). Each such document carries both
halves of design control: the **design inputs** it owns (§820.30(c), the "what")
and the **design output** prose (§820.30(d), the "how"). Discovery keys on the
``kind: design`` frontmatter marker — never on filename or folder — so documents
can be named for the context they describe.

The user-need registry (the validation anchor) lives once in the V&V plan
(``user_needs``); each design document references the needs it ``satisfies`` and
declares the design inputs that refine them. This module has no dependency on the
story-audit / pydantic layer so the record pipeline stays lightweight.
"""

from __future__ import annotations

from pathlib import Path

import yaml

# Frontmatter marker that identifies a per-context design document. Discovery
# keys on this, not on filename/folder, so docs are named for their context.
DESIGN_KIND = "design"


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


def _frontmatter_of(path: Path) -> dict:
    """Frontmatter of a document, or ``{}`` if unreadable/absent."""
    try:
        return parse_frontmatter(path.read_text(encoding="utf-8"))
    except OSError:
        return {}


def user_needs_from_doc(doc_path: Path) -> set[str]:
    """Return user-need IDs from a document's frontmatter ``user_needs`` list.

    Per ADR 0001 the user-need registry lives in the V&V plan; this reads it
    from any document's frontmatter. Accepts both ``{id, text}`` mappings and
    bare string IDs.
    """
    if not doc_path.exists():
        return set()
    value = _frontmatter_of(doc_path).get("user_needs")
    if not isinstance(value, list):
        return set()
    ids: set[str] = set()
    for item in value:
        if isinstance(item, dict) and str(item.get("id", "")).strip():
            ids.add(str(item["id"]).strip())
        elif not isinstance(item, dict) and str(item).strip():
            ids.add(str(item).strip())
    return ids


def find_design_docs(dhf_dir: Path) -> list[Path]:
    """Discover the per-context design documents under a DHF.

    A markdown file is a design document when its frontmatter declares
    ``kind: design``. There is one such document per bounded context; it holds
    both the design inputs it owns and the design-output prose.
    """
    found: list[Path] = []
    for md in dhf_dir.rglob("*.md"):
        if _frontmatter_of(md).get("kind") == DESIGN_KIND:
            found.append(md)
    return sorted(found)


def context_of(path: Path) -> str:
    """The bounded-context name a design document declares (or its filename)."""
    context = str(_frontmatter_of(path).get("context", "")).strip()
    return context or path.stem


def satisfies_for(doc_path: Path) -> set[str]:
    """Return the user-need IDs a single design document ``satisfies``."""
    if not doc_path.exists():
        return set()
    value = _frontmatter_of(doc_path).get("satisfies")
    if not isinstance(value, list):
        return set()
    return {str(v).strip() for v in value if str(v).strip()}


def satisfies_by_context(dhf_dir: Path) -> dict[Path, set[str]]:
    """Map each design document to the user-need IDs it ``satisfies``."""
    return {doc: satisfies_for(doc) for doc in find_design_docs(dhf_dir)}


def satisfied_user_needs(dhf_dir: Path) -> set[str]:
    """Union of user needs addressed (via ``satisfies``) across design docs."""
    ids: set[str] = set()
    for refs in satisfies_by_context(dhf_dir).values():
        ids |= refs
    return ids


def registry_user_needs(dhf_dir: Path) -> set[str]:
    """Union of ``user_needs`` declared in any document frontmatter under the DHF.

    Per ADR 0001 the registry lives in the V&V plan, but this finds it wherever
    it is authored.
    """
    ids: set[str] = set()
    for md in dhf_dir.rglob("*.md"):
        ids |= user_needs_from_doc(md)
    return ids


def design_inputs(dhf_dir: Path) -> list[dict]:
    """Return the design inputs declared across all per-context design docs.

    Each design document owns its design inputs in a ``design_inputs``
    frontmatter list of ``{id, text, traces_to}`` (``traces_to`` is the user
    need(s) the input refines). The union across documents is the verification
    anchor (tests verify each input via ``@allure.story("DI-…")``). If two
    documents declare the same id, the first by sorted path wins.
    """
    inputs: list[dict] = []
    seen: set[str] = set()
    for doc in find_design_docs(dhf_dir):
        value = _frontmatter_of(doc).get("design_inputs")
        if not isinstance(value, list):
            continue
        for item in value:
            if not isinstance(item, dict):
                continue
            di_id = str(item.get("id", "")).strip()
            if not di_id or di_id in seen:
                continue
            seen.add(di_id)
            traces = item.get("traces_to") or []
            inputs.append(
                {
                    "id": di_id,
                    "text": str(item.get("text", "")).strip(),
                    "traces_to": [str(t).strip() for t in traces if str(t).strip()],
                    "context": context_of(doc),
                }
            )
    return inputs


def design_input_ids(dhf_dir: Path) -> set[str]:
    """The set of declared design-input IDs (the verification denominator)."""
    return {di["id"] for di in design_inputs(dhf_dir)}


def realises_by_context(dhf_dir: Path) -> dict[Path, set[str]]:
    """Map each design document to the shared design-input IDs it ``realises``.

    ``realises`` lets a context contribute to a design input that another
    context owns (declared in that other context's ``design_inputs``); it never
    introduces a new input on its own.
    """
    refs: dict[Path, set[str]] = {}
    for doc in find_design_docs(dhf_dir):
        value = _frontmatter_of(doc).get("realises")
        if isinstance(value, list):
            refs[doc] = {str(v).strip() for v in value if str(v).strip()}
        else:
            refs[doc] = set()
    return refs
