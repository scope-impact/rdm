"""Acceptance test for derived relations and their rules (DI-62, see dhf/).

Tagged `@allure.story("DI-62")`, over the real vocabulary, projection and
agent server functions. Skips cleanly if allure-pytest or the `graph` extra
is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
pytest.importorskip("pyoxigraph")

from rdm.graph.agent import Record, query, schema  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from rdm.graph.rules import rules  # noqa: E402
from tests.acceptance.evidence import attach, clause  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"
P = "urn:dhf:acme:"
INFERRED = P + "graph/inferred"


def _serves(quads) -> set[tuple[str, str, str]]:
    return {(q.subject.value.rsplit("/", 1)[1], q.object.value.rsplit("/", 1)[1], q.graph_name.value)
            for q in quads if q.predicate.value == RDM + "serves"}


@allure.story("DI-62")
@allure.label("output", "rdm/graph/rules.py")
@allure.label("output", "rdm/graph/ontology.ttl")
@allure.label("output", "rdm/graph/agent.py")
def test_derived_relations_are_rules_not_facts(tmp_path: Path) -> None:
    """DI-62: each derived relation declared with its CONSTRUCT rule — first,
    a context serves what its owned and realised inputs trace to; schema lists
    the rules; inferring adds the results to a separate graph only."""
    dhf, results = _record(tmp_path)  # alarms owns DI-1 (UN-001), DI-2 (UN-002); ui realises DI-1

    with clause("the vocabulary declares each derived relation with the CONSTRUCT that derives it"):
        declared = {r["rule"]: r for r in rules()}
        serves = declared[RDM + "ServesRule"]
        attach("rdm:ServesRule", serves)
        assert serves["derives"] == RDM + "serves" and serves["construct"].lstrip().startswith("CONSTRUCT")
    with clause("a context serves the user needs its owned and realised design inputs trace to"):
        inferred = project(dhf, results, infer=True)
        assert {(c, n) for c, n, _ in _serves(inferred)} == {
            ("alarms", "UN-001"), ("alarms", "UN-002"), ("ui", "UN-001")}
    with clause("the derived facts are in the inferred graph and nowhere else"):
        assert {g for _, _, g in _serves(inferred)} == {INFERRED}
        plain = project(dhf, results)
        assert not _serves(plain) and not any(q.graph_name.value == INFERRED for q in plain)
        assert {str(q) for q in inferred if q.graph_name.value != INFERRED} == {str(q) for q in plain}
    with clause("the agent server's schema lists the rules, and its queries see what they derive"):
        assert RDM + "ServesRule" in {r["rule"] for r in schema()["rules"]}
        rows = query(Record(dhf, results), "SELECT ?c WHERE { ?x rdm:serves ?n ; rdfs:label ?c . "
                                           "?n dcterms:identifier 'UN-001' } ORDER BY ?c")["rows"]
        assert [r["c"] for r in rows] == ["alarms", "ui"]
