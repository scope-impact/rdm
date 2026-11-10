"""
The record's requirements and user needs, read from the spec
(``rdm.specification.spec``, ADR 0002), and the DHF documents RDM renders.

A requirement is a Gherkin scenario tagged with an id; a user need is what
the spec's needs source makes one (a Feature, by default). This module gives
the rest of RDM that reading as plain data.
"""

from __future__ import annotations

from pathlib import Path

from rdm.specification.spec import Spec, find_spec, read_spec

# The traceability-matrix template: an output, rendered from the record, never
# part of it (item-06-015) — the graph leaves it out, the evidence bundle renders it.
MATRIX_DOC = "traceability_matrix.md"


def find_dhf_doc(dhf_dir: Path, basename: str) -> Path | None:
    """Locate a DHF document by basename: ``<dhf>/documents/<basename>``
    first, then anywhere under the DHF."""
    preferred = dhf_dir / "documents" / basename
    if preferred.exists():
        return preferred
    matches = sorted(dhf_dir.rglob(basename))
    return matches[0] if matches else None


def load_spec(dhf_dir: Path) -> Spec | None:
    """The spec the record is held to, or None when the DHF names none and
    none is found (``rdm.specification.spec.find_spec``)."""
    config = find_spec(Path(dhf_dir))
    return read_spec(config) if config is not None else None


def requirements(dhf_dir: Path, spec: Spec | None = None) -> list[dict]:
    """Every requirement in spec order: ``{id, text, rule, source, context,
    traces_to}``, ``traces_to`` the user needs it addresses."""
    spec = spec if spec is not None else load_spec(dhf_dir)
    if spec is None:
        return []
    return [{"id": i.requirement, "text": i.title, "rule": i.rule, "source": i.source,
             "context": i.context, "traces_to": spec.needs_of(i),
             "covers": [f"{kind}.{name}" for kind, name in i.obligations]} for i in spec.items]


def requirement_ids(dhf_dir: Path, spec: Spec | None = None) -> set[str]:
    return {r["id"] for r in requirements(dhf_dir, spec)}


def user_needs(dhf_dir: Path, spec: Spec | None = None) -> list:
    """The user needs (``rdm.specification.spec.Need``), in spec order."""
    spec = spec if spec is not None else load_spec(dhf_dir)
    return list(spec.needs) if spec is not None else []


def user_need_ids(dhf_dir: Path, spec: Spec | None = None) -> set[str]:
    return {n.id for n in user_needs(dhf_dir, spec)}


def user_need_texts(dhf_dir: Path, spec: Spec | None = None) -> dict[str, str]:
    """Each user need's text: its screen's description, else its actors and surfaces."""
    return {n.id: n.text or f"{', '.join(n.actors)} at {', '.join(n.surfaces)}" for n in user_needs(dhf_dir, spec)}
