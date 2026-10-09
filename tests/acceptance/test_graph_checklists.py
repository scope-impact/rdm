"""Acceptance test for checklists and reference tags in the graph (DI-37, see dhf/).

Tagged `@allure.story("DI-37")`, over the real projection and `rdm gap`'s own
reader and matcher. Skips cleanly if allure-pytest or the `graph` extra is
not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.compliance import gaps

allure = pytest.importorskip("allure")
pytest.importorskip("rdflib")
from rdm.graph import rdf as ox  # noqa: E402

from rdm.graph.project import project  # noqa: E402
from tests.acceptance.evidence import verification_step  # noqa: E402

PREFIXES = """PREFIX rdm: <https://github.com/scope-impact/rdm/ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>
"""


def _clause(key: str) -> str:
    return f"<urn:rdm:clause:{key}>"


def _fixture(tmp_path: Path) -> tuple[Path, Path, Path]:
    """A DHF with one controlled document, a two-file text checklist (one
    includes the other) and a native RDF checklist."""
    dhf = tmp_path / "proj" / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "design" / "core.md").write_text(
        "---\nid: SDS-1\nkind: design\ncontext: core\ndesign_inputs: []\n---\n# Core\n")
    (dhf / "documents" / "plan.md").write_text(
        "---\nid: PLAN-1\n---\n# Plan\n"
        "Covers [[STD:1.2.a]], [[RDFX:1: a pointer note]] and [[RDFX:2]].\n"
        "Mentions STD:4 outside a tag block, which is not a reference.\n")
    lists = tmp_path / "checklists"
    lists.mkdir()
    (lists / "parent.txt").write_text(
        "# a comment\ninclude child.txt\n"
        "STD:1.2 the parent clause\nSTD:1.2.a its first part\nSTD:4 another clause\n"
        "OTHER:9@2020 an edition-qualified clause\n"
        "STD:5 a top clause\nSTD:5.1 a mid clause\nSTD:5.1.x a leaf clause\n")
    (lists / "child.txt").write_text("STD:3 an included clause\nSTD:1.2 the parent clause again\n")
    rdf = lists / "native.ttl"
    rdf.write_text(
        "@prefix rdm: <https://github.com/scope-impact/rdm/ns#> .\n"
        "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
        "<urn:rdm:standard:RDFX> a skos:ConceptScheme ; skos:prefLabel \"RDF-native standard\" .\n"
        "<urn:rdm:clause:RDFX:1> a rdm:Clause ; skos:notation \"RDFX:1\" ; "
        "skos:inScheme <urn:rdm:standard:RDFX> ; skos:definition \"authored as RDF\" .\n"
        "<https://example.org/std/rdfx/2> a rdm:Clause ; skos:notation \"RDFX:2\" ; "
        "skos:inScheme <urn:rdm:standard:RDFX> .\n"
        "<urn:rdm:checklist:native> a rdm:Checklist ; skos:member <urn:rdm:clause:RDFX:1> , "
        "<https://example.org/std/rdfx/2> .\n")
    return dhf, lists / "parent.txt", rdf


def _store(quads):
    store = ox.Store()
    store.extend(quads)
    return store


def _ask(store, body: str) -> bool:
    # Patterns without a GRAPH clause match across all named graphs, as
    # `rdm graph query` and the served endpoint do.
    return bool(store.query(PREFIXES + "ASK { " + body + " }", use_default_graph_as_union=True))


@allure.story("DI-37")
@allure.label("output", "rdm/graph/checklists.py")
def test_checklists_are_data(tmp_path: Path) -> None:
    """DI-37: checklists (text or RDF, includes resolved, built-ins by name)
    become SKOS collections of clauses in a checklists graph."""
    dhf, text_list, rdf_list = _fixture(tmp_path)
    quads = project(dhf, checklists=[str(text_list), str(rdf_list), "part11_document_control"])
    s = _store(quads)
    cl = "<urn:dhf:proj:graph/checklists>"
    parent, child = "<urn:rdm:checklist:parent>", "<urn:rdm:checklist:child>"

    with verification_step("A checklist is a collection of its own clauses that links the checklists it includes"):
        # A checklist is a collection: its OWN items as members, includes as links,
        # effective contents via rdm:includes*/skos:member (includes not flattened).
        assert _ask(s, f"GRAPH {cl} {{ {parent} a rdm:Checklist ; rdm:includes {child} ; "
                       f"skos:member {_clause('STD:4')} }}")
        assert not _ask(s, f"{parent} skos:member {_clause('STD:3')}")
        assert _ask(s, f"{parent} rdm:includes*/skos:member {_clause('STD:3')}")
        # A key in two checklists is one clause in two collections.
        assert _ask(s, f"{parent} skos:member {_clause('STD:1.2')} . {child} skos:member {_clause('STD:1.2')}")

    with verification_step("A built-in checklist resolves by name"):
        assert _ask(s, "<urn:rdm:checklist:part11_document_control> skos:member <urn:rdm:clause:P11:11.10a>")

    with verification_step("Each clause has its key, description, standard, edition and nearest listed parent clause"):
        # A clause: key as notation, description as definition, standard (key
        # prefix) as concept scheme, nearest listed dotted parent as broader.
        std12a = _clause("STD:1.2.a")
        assert _ask(s, f'GRAPH {cl} {{ {std12a} a rdm:Clause ; skos:notation "STD:1.2.a" ; '
                       f'skos:definition "its first part" ; skos:inScheme <urn:rdm:standard:STD> ; '
                       f'skos:broader {_clause("STD:1.2")} . <urn:rdm:standard:STD> a skos:ConceptScheme }}')
        assert not _ask(s, f"{_clause('STD:1.2')} skos:broader ?p")  # STD:1 is not listed
        # Only the NEAREST listed ancestor: the leaf is under the mid clause, not the top one.
        assert _ask(s, f"{_clause('STD:5.1.x')} skos:broader {_clause('STD:5.1')}")
        assert not _ask(s, f"{_clause('STD:5.1.x')} skos:broader {_clause('STD:5')}")
        assert _ask(s, f'{_clause("OTHER:9@2020")} rdm:edition "2020" ; skos:inScheme <urn:rdm:standard:OTHER>')

    with verification_step("An RDF checklist is loaded as it is, its nodes labelled like any other"):
        # An RDF checklist is loaded as-is (richer metadata kept) and takes part in matching.
        assert _ask(s, f'GRAPH {cl} {{ <urn:rdm:standard:RDFX> skos:prefLabel "RDF-native standard" }}')
        # ...and its nodes get labels like any other (key, preferred label, IRI tail).
        assert _ask(s, f'GRAPH {cl} {{ {_clause("RDFX:1")} rdfs:label "RDFX:1" . '
                       f'<urn:rdm:standard:RDFX> rdfs:label "RDF-native standard" . '
                       f'<urn:rdm:checklist:native> rdfs:label "native" }}')


@allure.story("DI-48")
@allure.label("output", "rdm/graph/checklists.py")
def test_reference_tags_become_links_matched_as_rdm_gap_matches(tmp_path: Path) -> None:
    """DI-48: documents' [[…]] tags become dcterms:references in a references
    graph, matched as rdm gap matches; unreferenced clauses are exactly what
    rdm gap reports missing."""
    dhf, text_list, rdf_list = _fixture(tmp_path)
    s = _store(project(dhf, checklists=[str(text_list), str(rdf_list), "part11_document_control"]))
    refs = "<urn:dhf:proj:graph/references>"

    with verification_step("Tags link documents to clauses, matched as rdm gap matches"):
        # References: [[…]] tags only, matched as rdm gap matches (a descendant
        # covers its parent; an annotation tail is allowed; bare mentions are not tags).
        doc = "<urn:dhf:proj:doc/PLAN-1>"
        for key in ("STD:1.2.a", "STD:1.2", "RDFX:1"):
            assert _ask(s, f"GRAPH {refs} {{ {doc} dcterms:references {_clause(key)} }}"), key
        assert not _ask(s, f"?d dcterms:references {_clause('STD:4')}")
        # An RDF clause with its own IRI is the one referenced — no clause is fabricated for it.
        assert _ask(s, f"GRAPH {refs} {{ {doc} dcterms:references <https://example.org/std/rdfx/2> }}")
        assert not _ask(s, f"?x ?p {_clause('RDFX:2')}") and not _ask(s, f"{_clause('RDFX:2')} ?p ?x")

    with verification_step("An unreferenced clause is exactly one rdm gap reports missing"):
        # Unreferenced clauses == rdm gap's missing items, for the same documents.
        unreferenced = {row["k"].value for row in s.query(
            PREFIXES + "SELECT ?k WHERE { ?c a rdm:Clause ; skos:notation ?k . "
                       "FILTER NOT EXISTS { ?d dcterms:references ?c } }", use_default_graph_as_union=True)}
        documents = [str(p) for p in sorted((dhf / "documents").rglob("*.md"))]
        missing = set()
        for spec in (str(text_list), "part11_document_control"):
            missing |= {item["reference"] for item in gaps.missing_references(spec, documents)[0]}
        assert unreferenced == missing
        assert "STD:4" in missing and "STD:1.2" not in missing
