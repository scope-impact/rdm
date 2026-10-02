"""
Read the per-context design documents and other DHF documents.

The unit of design is the **bounded context**, captured in **one document per
context** (``kind: design`` in its frontmatter). Each such document carries both
halves of design control: the **design inputs** it owns (§820.30(c), the "what")
and the **design output** prose (§820.30(d), the "how"). Discovery keys on the
``kind: design`` frontmatter marker — never on filename or folder — so documents
can be named for the context they describe.

The user-need registry (the validation anchor) lives once in the V&V plan
(``user_needs``); each design document declares the design inputs that refine
them, each naming the needs it ``traces_to`` — so the needs a context serves
follow from its inputs and are never declared separately. This module has no dependency on the
story-audit / pydantic layer so the record pipeline stays lightweight.
"""

from __future__ import annotations

from pathlib import Path

from rdm.kernel.frontmatter import documents, frontmatter_of

# The traceability-matrix template: an output, rendered from the record, never
# part of it (DI-58) — the graph leaves it out, the evidence bundle renders it.
MATRIX_DOC = "traceability_matrix.md"

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


def _entry_id(item) -> str:
    """The id of a frontmatter list entry: an ``{id: ...}`` mapping or a bare id."""
    return str(item.get("id", "") if isinstance(item, dict) else item).strip()


def _context(front: dict, path: Path) -> str:
    return str(front.get("context", "")).strip() or path.stem


def user_needs_from_doc(doc_path: Path) -> set[str]:
    """Return user-need IDs from a document's frontmatter ``user_needs`` list.

    Per ADR 0001 the user-need registry lives in the V&V plan; this reads it
    from any document's frontmatter. Accepts both ``{id, text}`` mappings and
    bare string IDs.
    """
    return _user_needs(frontmatter_of(doc_path))


def _user_needs(front: dict) -> set[str]:
    value = front.get("user_needs")
    return {i for i in map(_entry_id, value) if i} if isinstance(value, list) else set()


def find_design_docs(dhf_dir: Path) -> list[Path]:
    """Discover the per-context design documents under a DHF.

    A markdown file is a design document when its frontmatter declares
    ``kind: design``. There is one such document per bounded context; it holds
    both the design inputs it owns and the design-output prose.
    """
    return [md for md, front in documents(dhf_dir) if front.get("kind") == DESIGN_KIND]


def context_of(path: Path) -> str:
    """The bounded-context name a design document declares (or its filename)."""
    return _context(frontmatter_of(path), path)


def registry_user_needs(dhf_dir: Path) -> set[str]:
    """Union of ``user_needs`` declared in any document frontmatter under the DHF.

    Per ADR 0001 the registry lives in the V&V plan, but this finds it wherever
    it is authored.
    """
    return set().union(*(_user_needs(front) for _, front in documents(dhf_dir)))


def declarations(dhf_dir: Path) -> dict[str, list[str]]:
    """Every user-need and design-input id, with the document (relative to the
    DHF) of each declaration — one entry per declaration, so an id declared
    twice in one document lists that document twice (DI-46)."""
    found: dict[str, list[str]] = {}
    for md, front in documents(dhf_dir):
        where = str(md.relative_to(dhf_dir))
        entries = list(front.get("user_needs") or [])
        if front.get("kind") == DESIGN_KIND:
            entries += list(front.get("design_inputs") or [])
        for item in entries:
            if ident := _entry_id(item):
                found.setdefault(ident, []).append(where)
    return found


def duplicate_declarations(dhf_dir: Path) -> dict[str, list[str]]:
    """The ids declared more than once, with every declaring document."""
    return {ident: docs for ident, docs in declarations(dhf_dir).items() if len(docs) > 1}


def user_need_texts(dhf_dir: Path) -> dict[str, str]:
    """Each registered user need's text, where its ``{id, text}`` entry gives one."""
    texts: dict[str, str] = {}
    for _, front in documents(dhf_dir):
        value = front.get("user_needs")
        for item in value if isinstance(value, list) else []:
            if isinstance(item, dict) and _entry_id(item) and item.get("text"):
                texts.setdefault(_entry_id(item), str(item["text"]).strip())
    return texts


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
    for doc, front in documents(dhf_dir):
        value = front.get("design_inputs")
        if front.get("kind") != DESIGN_KIND or not isinstance(value, list):
            continue
        context = _context(front, doc)
        for item in value:
            if not isinstance(item, dict):
                continue
            di_id = _entry_id(item)
            if not di_id or di_id in seen:
                continue
            seen.add(di_id)
            traces = item.get("traces_to") or []
            inputs.append(
                {
                    "id": di_id,
                    "text": str(item.get("text", "")).strip(),
                    "traces_to": [str(t).strip() for t in traces if str(t).strip()],
                    "context": context,
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
    for doc, front in documents(dhf_dir):
        if front.get("kind") == DESIGN_KIND:
            value = front.get("realises")
            refs[doc] = {i for i in map(_entry_id, value) if i} if isinstance(value, list) else set()
    return refs
