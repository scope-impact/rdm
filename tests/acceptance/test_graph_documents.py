"""Acceptance test for documents linked from the record (DI-58, see dhf/).

Tagged `@allure.story("DI-58")`, over the real projection and the real gate
shapes. Skips cleanly if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
ox = pytest.importorskip("pyoxigraph")

from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate  # noqa: E402
from tests.acceptance.evidence import attach, clause  # noqa: E402

PREFIXES = """PREFIX rdm: <https://github.com/scope-impact/rdm/ns#>
PREFIX dcterms: <http://purl.org/dc/terms/>
PREFIX prov: <http://www.w3.org/ns/prov#>
"""
UNDECLARED = "bounded context has a design document but no document declares it (contexts frontmatter)"
DANGLING = "references a document the record does not hold"


def _dhf(tmp_path: Path, architecture: str | None) -> Path:
    """Needs in a V&V plan, two contexts with design documents, a document
    control procedure, a generated matrix, and (optionally) an architecture."""
    docs = tmp_path / "acme" / "dhf" / "documents"
    (docs / "design").mkdir(parents=True)
    (docs / "vv.md").write_text("---\nid: VVP-1\nuser_needs:\n  - {id: UN-001, text: a need}\n---\n")
    for context in ("alarms", "ui"):
        (docs / "design" / f"{context}.md").write_text(
            f"---\nid: SDS-{context.upper()}\nkind: design\ncontext: {context}\nsatisfies: [UN-001]\n"
            "design_inputs: []\n---\n")
    (docs / "control.md").write_text("---\nid: DC-1\ntitle: Document control\n---\nCovers [[STD:1]].\n")
    (docs / "traceability_matrix.md").write_text("---\nid: TM-1\n---\n{{ verification }}\n")
    if architecture is not None:
        (docs / "architecture.md").write_text(architecture)
    return docs.parent


def _ask(store, body: str) -> bool:
    return bool(store.query(PREFIXES + "ASK { " + body + " }", use_default_graph_as_union=True))


@allure.story("DI-58")
@allure.label("output", "rdm/graph/project.py")
@allure.label("output", "rdm/graph/shapes.ttl")
def test_documents_link_from_the_record(tmp_path: Path) -> None:
    """DI-58: contexts link to the document declaring them (with their part),
    documents to the documents they reference, the matrix to its sources; a
    warning for an undeclared context once any is declared; a violation for a
    reference to a document the record does not hold."""
    dhf = _dhf(tmp_path / "declared", "---\nid: ARCH-1\ncontexts:\n  - {id: alarms, part: Record}\n"
                                      "references: [DC-1, NOPE-9]\n---\n# Architecture\n")
    checklist = tmp_path / "std.txt"
    checklist.write_text("STD:1 a clause\n")
    quads = project(dhf, checklists=[str(checklist)])
    store = ox.Store()
    store.extend(quads)
    doc = "<urn:dhf:acme:doc/{}>".format
    report = validate(quads)
    attach("validation results", [(r.severity, r.label, r.message) for r in report])

    with clause("each bounded context links to the document whose contexts frontmatter declares it, with its part"):
        assert _ask(store, f'<urn:dhf:acme:context/alarms> rdm:declaredIn {doc("ARCH-1")} ; rdm:part "Record"')
        assert not _ask(store, "<urn:dhf:acme:context/ui> rdm:declaredIn ?d")
    with clause("each controlled document links to the controlled documents its references frontmatter names"):
        assert _ask(store, f"{doc('ARCH-1')} dcterms:references {doc('DC-1')} . {doc('DC-1')} a rdm:Document")
    with clause("the traceability matrix links to the design documents and the user-need registry"):
        sources = {row["s"].value for row in store.query(
            PREFIXES + f"SELECT ?s WHERE {{ {doc('TM-1')} prov:wasDerivedFrom ?s }}", use_default_graph_as_union=True)}
        assert sources == {f"urn:dhf:acme:doc/{d}" for d in ("SDS-ALARMS", "SDS-UI", "VVP-1")}
    with clause("a shape warns on a context no document declares, once any document declares contexts"):
        warned = {r.label for r in report if r.message == UNDECLARED}
        assert warned == {"ui"} and all(r.severity == "Warning" for r in report if r.message == UNDECLARED)
        quiet = validate(project(_dhf(tmp_path / "none", None)))
        assert not [r for r in quiet if r.message == UNDECLARED]
    with clause("validation fails on a reference to a document the record does not hold"):
        assert _ask(store, f"{doc('DC-1')} dcterms:references <urn:rdm:clause:STD:1>")  # a clause, not a document
        dangling = [r for r in report if r.message == DANGLING]
        assert [(r.severity, r.label) for r in dangling] == [("Violation", "ARCH-1")]

    with clause("a context may be declared by id alone; a single reference may be a string"):
        plain = project(_dhf(tmp_path / "plain", "---\nid: ARCH-1\ncontexts: [alarms, ui]\nreferences: DC-1\n---\n"))
        s = ox.Store()
        s.extend(plain)
        assert _ask(s, f"<urn:dhf:acme:context/ui> rdm:declaredIn {doc('ARCH-1')}")
        assert _ask(s, f"{doc('ARCH-1')} dcterms:references {doc('DC-1')}")
        assert not [r for r in validate(plain) if r.message in (UNDECLARED, DANGLING)]
