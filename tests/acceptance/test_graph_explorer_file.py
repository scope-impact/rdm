"""Acceptance test for the Graph Explorer graph file (DI-39, see dhf/).

Tagged `@allure.story("DI-39")`, over the real projection and
`rdm graph explorer-file`. Skips cleanly if allure-pytest or the `graph`
extra is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402
ox = pytest.importorskip("pyoxigraph")

from rdm.graph import cli as graph_cli  # noqa: E402
from rdm.graph.explorer import explorer_graph  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402

NS = "https://github.com/scope-impact/rdm/ns#"
TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"


@allure.story("DI-39")
@allure.label("output", "rdm/graph/explorer.py")
def test_whole_record_as_a_graph_explorer_file(tmp_path: Path) -> None:
    """DI-39: every record node and every link between them, no vocabulary or
    type statements, chosen classes left out on request, the endpoint as the
    connection, in Graph Explorer's graph-export envelope."""
    dhf, results = _record(tmp_path)
    run = results / "r1-result.json"  # a run with evidence: a step and an attachment
    run.write_text(json.dumps({**json.loads(run.read_text()), "steps": [{"name": "alarm sounds", "status": "passed"}],
                               "attachments": [{"name": "log", "source": "l-attachment.txt"}]}))
    lists = tmp_path / "mini.txt"
    lists.write_text("STD:1 a clause\n")
    native = tmp_path / "native.ttl"  # a native RDF checklist whose clause has its own https IRI
    native.write_text(
        "@prefix rdm: <https://github.com/scope-impact/rdm/ns#> .\n"
        "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
        "<https://example.org/std/rdfx/2> a rdm:Clause ; skos:notation \"RDFX:2\" .\n"
        "<urn:rdm:checklist:native> a rdm:Checklist ; skos:member <https://example.org/std/rdfx/2> .\n")
    (dhf / "documents" / "plan.md").write_text("---\nid: PLAN-1\n---\n# Plan\nCovers [[RDFX:2]].\n")
    quads = project(dhf, results, checklists=[str(lists), str(native)])
    graph = explorer_graph(quads, endpoint="http://example:7878")

    with verification_step("Graph Explorer's envelope and connection"):
        assert graph["meta"]["kind"] == "graph-export" and graph["meta"]["version"] == "1.0"
        assert graph["data"]["connection"] == {"dbUrl": "http://example:7878", "queryEngine": "sparql"}

    with verification_step("Every node of the record -- of every kind -- and nothing from the vocabulary"):
        vertices = set(graph["data"]["vertices"])
        for node in ("urn:dhf:acme:need/UN-001", "urn:dhf:acme:input/DI-1", "urn:dhf:acme:context/alarms",
                     "urn:dhf:acme:doc/SDS-ALM-001", "urn:dhf:acme:testfile/tests/test_alarms.py",
                     "urn:dhf:acme:test/tests/test_alarms.py%3A%3Atest_alarm",
                     "urn:dhf:acme:run/r1-result", "urn:rdm:clause:STD:1", "urn:rdm:checklist:mini"):
            assert node in vertices, node
        assert any(v.startswith("urn:dhf:acme:commit/") for v in vertices)
        assert "https://example.org/std/rdfx/2" in vertices  # a record node with its own IRI
        vocabulary = {q.subject.value for q in quads if q.graph_name.value.endswith("graph/ontology")}
        assert vocabulary and not vertices & vocabulary
        assert not any(v.startswith(NS) for v in vertices)

    with verification_step("Every link between two record nodes, in Graph Explorer's edge-id form, and no rdf:type "
                           "statement"):
        edges = set(graph["data"]["edges"])
        assert f"urn:dhf:acme:input/DI-1-[{NS}tracesTo]->urn:dhf:acme:need/UN-001" in edges
        assert f"urn:dhf:acme:run/r1-result-[{NS}exercises]->urn:dhf:acme:input/DI-1" in edges
        assert "urn:rdm:checklist:mini-[http://www.w3.org/2004/02/skos/core#member]->urn:rdm:clause:STD:1" in edges
        assert ("urn:dhf:acme:doc/PLAN-1-[http://purl.org/dc/terms/references]->https://example.org/std/rdfx/2"
                in edges)
        assert not any("22-rdf-syntax-ns#type" in e for e in edges)
        for edge in edges:
            subject, rest = edge.split("-[", 1)
            _, obj = rest.split("]->", 1)
            assert subject in vertices and obj in vertices, edge
        expected_links = {(q.subject.value, q.predicate.value, q.object.value) for q in quads
                          if q.subject.value in vertices and getattr(q.object, "value", None) in vertices
                          and not q.predicate.value.endswith("#type")}
        assert len(edges) == len(expected_links)

    with verification_step("Leaving out a class drops its nodes and their links, the nodes that hang only from them, "
                           "and only those"):
        trimmed = explorer_graph(quads, exclude=["TestRun"])["data"]
        assert "urn:dhf:acme:run/r1-result" not in trimmed["vertices"]
        assert not any("run/r1-result" in e for e in trimmed["edges"])
        # the run's step and attachment hung only from the run: they go with it
        run_details = {v for v in vertices if v.startswith(("urn:dhf:acme:step/", "urn:dhf:acme:attachment/"))}
        assert len(run_details) == 2
        assert set(trimmed["vertices"]) == vertices - {"urn:dhf:acme:run/r1-result"} - run_details
        # a record node with no links at all is a record island, and stays
        lone_need = ox.Quad(ox.NamedNode("urn:dhf:acme:need/UN-LONE"), ox.NamedNode(TYPE),
                            ox.NamedNode(NS + "UserNeed"), ox.NamedNode("urn:dhf:acme:graph/record"))
        island = explorer_graph(quads + [lone_need], exclude=["TestRun"])["data"]
        assert "urn:dhf:acme:need/UN-LONE" in island["vertices"]
        # leaving out steps keeps the run: it links to the record (run -> design input)
        assert "urn:dhf:acme:run/r1-result" in explorer_graph(quads, exclude=["Step"])["data"]["vertices"]
        # with nothing left out, nothing is pruned: a lone run detail stays
        lone = ox.Quad(ox.NamedNode("urn:dhf:acme:step/lone"), ox.NamedNode(TYPE), ox.NamedNode(NS + "Step"),
                       ox.NamedNode("urn:dhf:acme:graph/executions"))
        assert "urn:dhf:acme:step/lone" in explorer_graph(quads + [lone])["data"]["vertices"]

    with verification_step("The command writes it, from a fresh projection or from a built store"):
        out = tmp_path / "acme.graph.json"
        assert graph_cli.graph_explorer_file_command(out, dhf_dir=dhf, allure_results_dir=results,
                                                     checklists=[str(lists), str(native)]) == 0
        assert set(json.loads(out.read_text())["data"]["vertices"]) == vertices
        store = tmp_path / "store"
        assert graph_cli.graph_build_command(dhf_dir=dhf, allure_results_dir=results, store=store,
                                             checklists=[str(lists), str(native)]) == 0
        assert graph_cli.graph_explorer_file_command(out, store=store, exclude=["TestRun"]) == 0
        assert set(json.loads(out.read_text())["data"]["vertices"]) == set(trimmed["vertices"])
        assert graph_cli.graph_explorer_file_command(out, store=tmp_path / "missing") == 2
        assert graph_cli.graph_explorer_file_command(out, store=store, unit_coverage=tmp_path / "cov.xml") == 2
    with verification_step("A class to leave out that the graph does not have is refused, naming the ones it has"):
        with pytest.raises(ValueError, match="TestRun"):
            explorer_graph(quads, exclude=["Testrun"])
        assert graph_cli.graph_explorer_file_command(out, dhf_dir=dhf, exclude=["Testrun"]) == 2
