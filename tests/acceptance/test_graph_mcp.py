"""Acceptance tests for the read-only agent interface (item-06-006, item-06-007).

Tagged `@allure.tag`, over a real `rdm graph mcp` subprocess spoken to by
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


@allure.tag("item-06-006")
@allure.label("output", "rdm/graph/agent.py")
def test_agent_server_answers_from_the_current_record(tmp_path: Path) -> None:
    """item-06-006: an MCP stdio server with schema, query, trace (a
    requirement, user need or risk) and validate, each answering from a
    projection of the record as it is at that call."""
    dhf, results = _record(tmp_path)
    spec = dhf.parent / ".pi" / "spec" / "alarms" / "spec.feature"

    async def steps(client, call):
        names = sorted(t.name for t in (await client.list_tools()).tools)
        out = {"names": names}
        out["schema"] = await call("schema")
        out["query"] = await call("query", {"sparql": "SELECT ?id WHERE { ?i a rdm:Requirement ; "
                                                      "dcterms:identifier ?id } ORDER BY ?id"})
        out["trace_req"] = await call("trace", {"id": "item-01-001"})
        out["trace_need"] = await call("trace", {"id": "Alarm"})
        out["validate"] = await call("validate")
        # The spec changes mid-session; the next call sees it.
        spec.write_text(spec.read_text().replace("The device shall alarm.", "The device shall alarm loudly."))
        out["after_edit"] = await call("trace", {"id": "item-01-001"})
        out["unknown"] = await call("trace", {"id": "item-01-099"})
        return out

    out = _session(dhf, results, steps)

    with verification_step("Exactly four tools; schema: the vocabulary and the predeclared prefixes"):
        assert out["names"] == ["query", "schema", "trace", "validate"]
        error, schema = out["schema"]
        assert not error and "rdm:Requirement" in schema["ontology"]
        assert schema["prefixes"]["rdm"] == "https://github.com/scope-impact/rdm/ns#"

    with verification_step("query: SPARQL with the prefixes predeclared"):
        error, rows = out["query"]
        assert not error and [r["id"] for r in rows["rows"]] == ["item-01-001", "item-01-002"]
        assert rows["truncated"] is False

    with verification_step("trace a requirement: its scenario, context, source, needs, coverage and runs"):
        error, req = out["trace_req"]
        req = req["requirement"]
        assert not error and req["id"] == "item-01-001" and req["text"] == "The device shall alarm."
        assert req["context"] == "alarms" and req["source"] == ".pi/plan/01-alarms/spec.feature:5"
        assert req["needs"] == ["Alarm"] and req["covers"] == ["guarantee.Alarm", "rule-success.SoundAlarm"]
        assert req["runs"] == [{"test": "test_alarm", "status": "passed", "steps": [], "attachments": []}]

    with verification_step("trace a user need: what it faces, provides and guarantees, and its requirements"):
        error, need = out["trace_need"]
        need = need["user_need"]
        assert not error and need["id"] == "Alarm" and need["text"] == "a need" and need["context"] == "alarms"
        assert need["facing"] == ["Nurse"] and need["surfaces"] == ["Alarm"] and need["rules"] == ["SoundAlarm"]
        assert need["guarantees"] == ["Alarm"] and [r["id"] for r in need["requirements"]] == ["item-01-001"]

    with verification_step("validate: the gate shapes' results (item-01-002 has no passing run)"):
        error, report = out["validate"]
        assert not error and report["violations"] >= 1
        assert {"severity": "Violation", "focus": "item-01-002",
                "message": "requirement is not verified by any passing test run"} in report["results"]

    with verification_step("Fresh per call: the edit made during the session is what the next call reads"):
        assert out["after_edit"][1]["requirement"]["text"] == "The device shall alarm loudly."
        error, message = out["unknown"]
        assert error and "item-01-099 is not a requirement, user need or risk of the record" in message


@allure.tag("item-06-007")
@allure.label("output", "rdm/graph/agent.py")
def test_agent_server_cannot_change_anything(tmp_path: Path) -> None:
    """item-06-007: no write tool; query takes SELECT/ASK/CONSTRUCT/DESCRIBE, rejects
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
        out["bad_ids"] = [await call("trace", {"id": i}) for i in ('item-01-001" } UNION { ?s ?p ?o', "item 01", "")]
        out["n1"] = await call("query", {"sparql": count})
        out["ask"] = await call("query", {"sparql": "ASK { ?s a rdm:Requirement }"})
        out["construct"] = await call(
            "query", {"sparql": "CONSTRUCT { ?s a rdm:UserNeed } WHERE { ?s a rdm:UserNeed }"})
        out["describe"] = await call("query", {"sparql": "DESCRIBE <urn:dhf:acme:need/Alarm>"})
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

    with verification_step("trace answers an id the record declares whatever its shape (a user need's name), and "
                           "nothing built from it"):
        from rdm.graph.agent import trace

        assert trace(Record(dhf, results), "Logging")["user_need"]["id"] == "Logging"
        with pytest.raises(ValueError, match="is not an id"):
            trace(Record(dhf, results), 'Logging" } UNION { ?s ?p ?o')

    with verification_step("The read forms all work"):
        assert out["ask"][1] == {"boolean": True}
        assert sorted(t[0] for t in out["construct"][1]["triples"]) == [
            "urn:dhf:acme:need/Alarm", "urn:dhf:acme:need/Logging"]
        assert any(t[0] == "urn:dhf:acme:need/Alarm" for t in out["describe"][1]["triples"])

    with verification_step("Rows are capped and the cut is reported; under the cap, nothing is flagged"):
        capped = out["capped"][1]
        assert len(capped["rows"]) == 3 and capped["truncated"] is True
        many = "SELECT * WHERE { ?s ?p ?o . ?need a rdm:UserNeed }"  # every statement, twice
        assert 2 * int(out["n0"][1]["rows"][0]["n"]) > ROW_LIMIT
        default = query(Record(dhf, results), many)
        assert len(default["rows"]) == ROW_LIMIT and default["truncated"] is True
        small = query(Record(dhf, results), "SELECT ?s WHERE { ?s a rdm:UserNeed }")
        assert len(small["rows"]) == 2 and small["truncated"] is False
