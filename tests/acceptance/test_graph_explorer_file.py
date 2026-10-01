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

from tests.acceptance.evidence import clause  # noqa: E402
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
    quads = project(dhf, results, checklists=[str(lists)])
    graph = explorer_graph(quads, endpoint="http://example:7878")

    with clause("Graph Explorer's envelope and connection"):
        assert graph["meta"]["kind"] == "graph-export" and graph["meta"]["version"] == "1.0"
        assert graph["data"]["connection"] == {"dbUrl": "http://example:7878", "queryEngine": "sparql"}

    with clause("Every node of the record -- of every kind -- and nothing from the vocabulary"):
        vertices = set(graph["data"]["vertices"])
        for node in ("urn:dhf:acme:need/UN-001", "urn:dhf:acme:input/DI-1", "urn:dhf:acme:context/alarms",
                     "urn:dhf:acme:doc/SDS-ALM-001", "urn:dhf:acme:testfile/tests/test_alarms.py",
                     "urn:dhf:acme:test/tests/test_alarms.py%3A%3Atest_alarm",
                     "urn:dhf:acme:run/r1-result", "urn:rdm:clause:STD:1", "urn:rdm:checklist:mini"):
            assert node in vertices, node
        assert any(v.startswith("urn:dhf:acme:commit/") for v in vertices)
        assert not any(v.startswith(NS) or v.startswith("http") for v in vertices)

    # Every link between two record nodes, in Graph Explorer's edge-id form;
    # no rdf:type statements and no link to anything outside the node set.
    with clause("Every link between two record nodes, in Graph Explorer's edge-id form; no rdf:type statements…"):
        edges = set(graph["data"]["edges"])
        assert f"urn:dhf:acme:input/DI-1-[{NS}tracesTo]->urn:dhf:acme:need/UN-001" in edges
        assert f"urn:dhf:acme:run/r1-result-[{NS}exercises]->urn:dhf:acme:input/DI-1" in edges
        assert "urn:rdm:checklist:mini-[http://www.w3.org/2004/02/skos/core#member]->urn:rdm:clause:STD:1" in edges
        assert not any("22-rdf-syntax-ns#type" in e for e in edges)
        for edge in edges:
            subject, rest = edge.split("-[", 1)
            _, obj = rest.split("]->", 1)
            assert subject in vertices and obj in vertices, edge
        expected_links = {(q.subject.value, q.predicate.value, q.object.value) for q in quads
                          if q.subject.value in vertices and getattr(q.object, "value", None) in vertices
                          and not q.predicate.value.endswith("#type")}
        assert len(edges) == len(expected_links)

    with clause("Leaving out a class drops its nodes and their links, the nodes that hang only from them, "
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

    with clause("The command writes it, from a fresh projection or from a built store"):
        out = tmp_path / "acme.graph.json"
        assert graph_cli.graph_explorer_file_command(out, dhf_dir=dhf, allure_results_dir=results,
                                                     checklists=[str(lists)]) == 0
        assert set(json.loads(out.read_text())["data"]["vertices"]) == vertices
        store = tmp_path / "store"
        assert graph_cli.graph_build_command(dhf_dir=dhf, allure_results_dir=results, store=store,
                                             checklists=[str(lists)]) == 0
        assert graph_cli.graph_explorer_file_command(out, store=store, exclude=["TestRun"]) == 0
        assert set(json.loads(out.read_text())["data"]["vertices"]) == set(trimmed["vertices"])
        assert graph_cli.graph_explorer_file_command(out, store=tmp_path / "missing") == 2
