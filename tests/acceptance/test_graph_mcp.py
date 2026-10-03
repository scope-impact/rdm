"""Acceptance tests for the read-only agent interface (DI-41, DI-42, see dhf/).

Tagged `@allure.story`, over a real `rdm graph mcp` subprocess spoken to by
the official MCP client over stdio. Skips cleanly if allure-pytest or the
`graph` extra is not installed.
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402
pytest.importorskip("pyoxigraph")
mcp = pytest.importorskip("mcp")

from rdm.graph.agent import ROW_LIMIT, Record, query  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402


def _session(dhf: Path, results: Path, steps):
    """Run ``steps(call)`` against a real `rdm graph mcp` over stdio; ``call``
    returns (is_error, payload) for one tool call."""
    async def run():
        params = mcp.StdioServerParameters(
            command=sys.executable, cwd=str(Path(__file__).resolve().parents[2]),
            args=["-m", "rdm.main", "graph", "mcp", "--dhf", str(dhf), "--allure-results", str(results)])
        async with mcp.Client(params) as client:
            async def call(name, arguments=None):
                result = await client.call_tool(name, arguments or {})
                text = result.content[0].text
                return result.is_error, (text if result.is_error else json.loads(text))
            return await steps(client, call)
    return asyncio.run(asyncio.wait_for(run(), 60))


@allure.story("DI-41")
@allure.label("output", "rdm/graph/agent.py")
def test_agent_server_answers_from_the_current_record(tmp_path: Path) -> None:
    """DI-41: an MCP stdio server with schema, query, trace and validate, each
    answering from a projection of the record as it is at that call."""
    dhf, results = _record(tmp_path)
    design = dhf / "documents" / "design" / "alarms.md"

    async def steps(client, call):
        names = sorted(t.name for t in (await client.list_tools()).tools)
        out = {"names": names}
        out["schema"] = await call("schema")
        out["query"] = await call("query", {"sparql": "SELECT ?id WHERE { ?i a rdm:DesignInput ; "
                                                      "dcterms:identifier ?id } ORDER BY ?id"})
        out["trace_di"] = await call("trace", {"id": "DI-1"})
        out["trace_un"] = await call("trace", {"id": "un-001"})
        out["validate"] = await call("validate")
        # The record changes mid-session; the next call sees it.
        design.write_text(design.read_text().replace("The device shall alarm.", "The device shall alarm loudly."))
        out["after_edit"] = await call("trace", {"id": "DI-1"})
        out["unknown"] = await call("trace", {"id": "DI-99"})
        return out

    out = _session(dhf, results, steps)
    assert out["names"] == ["query", "schema", "trace", "validate"]

    with verification_step("schema: the vocabulary and the predeclared prefixes"):
        error, schema = out["schema"]
        assert not error and "rdm:DesignInput" in schema["ontology"]
        assert schema["prefixes"]["rdm"] == "https://github.com/scope-impact/rdm/ns#"

    with verification_step("query: SPARQL with the prefixes predeclared"):
        error, rows = out["query"]
        assert not error and [r["id"] for r in rows["rows"]] == ["DI-1", "DI-2"] and rows["truncated"] is False

    # trace a design input: text, need, owning context and document, realising
    # context, tagged test file, and its run.
    with verification_step("trace a design input: text, need, owning context and document, realising context, "
                           "tagged test…"):
        error, di = out["trace_di"]
        di = di["design_input"]
        assert not error and di["id"] == "DI-1" and di["text"] == "The device shall alarm."
        assert di["needs"] == ["UN-001"] and di["context"] == "alarms" and di["realised_by"] == ["ui"]
        assert di["document"]["id"] == "SDS-ALM-001" and di["document"]["path"] == "dhf/documents/design/alarms.md"
        assert di["document"]["last_commit"].endswith("approve design")
        assert di["tests"] == ["tests/test_alarms.py::test_alarm"]
        assert di["runs"] == [{"test": "test_alarm", "status": "passed", "steps": [], "attachments": []}]

    with verification_step("trace a user need (id case-insensitive): its text, contexts and inputs"):
        error, un = out["trace_un"]
        un = un["user_need"]
        assert not error and un["id"] == "UN-001" and un["text"] == "a need"
        assert un["contexts"] == ["alarms", "ui"] and [d["id"] for d in un["design_inputs"]] == ["DI-1"]

    with verification_step("validate: the gate shapes' results (DI-2 has no passing run)"):
        error, report = out["validate"]
        assert not error and report["violations"] >= 1
        assert {"severity": "Violation", "focus": "DI-2",
                "message": "design input is not verified by any passing test run"} in report["results"]

    with verification_step("Fresh per call: the edit made during the session is what the next call reads"):
        assert out["after_edit"][1]["design_input"]["text"] == "The device shall alarm loudly."
        error, message = out["unknown"]
        assert error and "DI-99 is not declared in the record" in message


@allure.story("DI-42")
@allure.label("output", "rdm/graph/agent.py")
def test_agent_server_cannot_change_anything(tmp_path: Path) -> None:
    """DI-42: no write tool; query takes SELECT/ASK/CONSTRUCT/DESCRIBE, rejects
    SPARQL Update and SERVICE; trace takes only ids; rows capped, the cut reported."""
    dhf, results = _record(tmp_path)
    before = {p: p.read_bytes() for p in dhf.rglob("*") if p.is_file()}
    count = "SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }"

    async def steps(client, call):
        tools = (await client.list_tools()).tools
        out = {"tools": [(t.name, t.annotations.read_only_hint, t.annotations.destructive_hint) for t in tools]}
        out["n0"] = await call("query", {"sparql": count})
        out["updates"] = [await call("query", {"sparql": u}) for u in (
            "INSERT DATA { <urn:x> <urn:y> <urn:z> }",
            "DELETE WHERE { ?s ?p ?o }",
            "CLEAR ALL",
            "PREFIX ex: <urn:ex:> DROP GRAPH ex:g",
        )]
        out["garbage"] = await call("query", {"sparql": "not sparql at all"})
        out["services"] = [await call("query", {"sparql": q}) for q in (
            "SELECT * WHERE { SERVICE <http://127.0.0.1:9/sparql> { ?s ?p ?o } }",
            "select * where { ?s ?p ?o . service ?u { ?a ?b ?c } }",
            "SELECT * WHERE { # a comment ended by a carriage return\rSERVICE <http://127.0.0.1:9/x> { ?s ?p ?o }\n}",
        )]
        out["service_word"] = await call("query", {"sparql": 'ASK { ?s ?p ?o FILTER(?o != "SERVICE") }'})
        out["bad_ids"] = [await call("trace", {"id": i}) for i in ('DI-1" } UNION { ?s ?p ?o', "DI 1", "")]
        out["n1"] = await call("query", {"sparql": count})
        out["ask"] = await call("query", {"sparql": "ASK { ?s a rdm:DesignInput }"})
        out["construct"] = await call(
            "query", {"sparql": "CONSTRUCT { ?s a rdm:UserNeed } WHERE { ?s a rdm:UserNeed }"})
        out["describe"] = await call("query", {"sparql": "DESCRIBE <urn:dhf:acme:need/UN-001>"})
        out["capped"] = await call("query", {"sparql": "SELECT ?s ?p ?o WHERE { ?s ?p ?o }", "limit": 3})
        return out

    out = _session(dhf, results, steps)
    with verification_step("Exactly the four read tools, all declared read-only and non-destructive"):
        assert sorted(out["tools"]) == [(n, True, None) for n in ("query", "schema", "trace", "validate")]

    with verification_step("Every form of SPARQL Update is refused, and nothing changed"):
        for error, message in out["updates"]:
            assert error and "SPARQL Update is not accepted: the graph is read-only" in message
        error, message = out["garbage"]
        assert error and "not a SPARQL query" in message
    # No network: SERVICE is refused (any case, IRI or variable endpoint), while
    # the word inside a literal is just text.
    with verification_step("No network: SERVICE is refused (any case, IRI or variable endpoint), while the word "
                           "inside a…"):
        for error, message in out["services"]:
            assert error and "SERVICE is not accepted: the graph does not reach the network" in message
        assert out["service_word"] == (False, {"boolean": True})
    with verification_step("trace takes only id-shaped input"):
        for error, message in out["bad_ids"]:
            assert error and "is not an id" in message
        assert out["n0"][1]["rows"] == out["n1"][1]["rows"]
        assert {p: p.read_bytes() for p in dhf.rglob("*") if p.is_file()} == before

    with verification_step("trace answers any id the record declares, whatever its shape and case"):
        from rdm.graph.agent import trace
        from tests.acceptance.test_graph_shapes import _dhf as _shaped

        odd = _shaped(tmp_path / "odd", inputs=(("DI-1", "UN-1"), ("DI-2a", "UN-2")), tagged=("DI-1", "DI-2a"))
        traced = trace(Record(odd), "di-2a")["design_input"]
        assert traced["id"] == "DI-2a" and traced["tests"], traced
        with pytest.raises(ValueError, match="is not an id"):
            trace(Record(odd), 'DI-2a" } UNION { ?s ?p ?o')

    with verification_step("The read forms all work"):
        assert out["ask"][1] == {"boolean": True}
        assert sorted(t[0] for t in out["construct"][1]["triples"]) == [
            "urn:dhf:acme:need/UN-001", "urn:dhf:acme:need/UN-002"]
        assert any(t[0] == "urn:dhf:acme:need/UN-001" for t in out["describe"][1]["triples"])

    with verification_step("Rows are capped and the cut is reported; under the cap, nothing is flagged"):
        capped = out["capped"][1]
        assert len(capped["rows"]) == 3 and capped["truncated"] is True
        many = "SELECT * WHERE { ?s ?p ?o . ?need a rdm:UserNeed }"  # every statement, twice
        assert 2 * int(out["n0"][1]["rows"][0]["n"]) > ROW_LIMIT
        default = query(Record(dhf, results), many)
        assert len(default["rows"]) == ROW_LIMIT and default["truncated"] is True
        small = query(Record(dhf, results), "SELECT ?s WHERE { ?s a rdm:UserNeed }")
        assert len(small["rows"]) == 2 and small["truncated"] is False
