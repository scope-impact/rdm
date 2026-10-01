"""
Run the gate shapes over the projected graph (DI-38).

``shapes.ttl`` states the gate rules as SHACL; teams add their own rules as
more shape files. The coded gates (`rdm story release-gate`, `rdm gap`) stay
authoritative — an acceptance test holds the shapes to agreement with them.
"""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import pyoxigraph as ox

from rdm.graph.ns import RDFS, SHACL, SKOS

SHAPES_FILE = Path(__file__).with_name("shapes.ttl")
_SH, _LABEL, _NOTATION = SHACL, RDFS + "label", SKOS + "notation"
SEVERITY_ORDER = {"Violation": 0, "Warning": 1, "Info": 2}


@dataclass(frozen=True)
class Result:
    severity: str        # Violation | Warning | Info
    focus: str           # the node's IRI
    label: str           # its rdfs:label, for people
    message: str


def _rdflib_graph(quads: list[ox.Quad]):
    """All quads as one rdflib graph (the union of the named graphs)."""
    import rdflib

    text = ox.serialize([ox.Triple(q.subject, q.predicate, q.object) for q in quads],
                        format=ox.RdfFormat.N_TRIPLES).decode("utf-8")
    return rdflib.Graph().parse(data=text, format="nt")


@lru_cache(maxsize=1)
def _gate_shapes():
    """RDM's gate shapes, parsed once per process."""
    import rdflib

    return rdflib.Graph().parse(SHAPES_FILE, format="turtle")


def validate(quads: list[ox.Quad], extra_shapes: list[Path] | None = None) -> list[Result]:
    """Every SHACL result for the graph, most severe first."""
    import pyshacl
    import rdflib

    data = _rdflib_graph(quads)
    shapes = _gate_shapes()
    if extra_shapes:
        shapes = shapes + rdflib.Graph()  # a copy: the parsed gate shapes are shared
        for path in extra_shapes:
            shapes.parse(str(path))
    _, report, _ = pyshacl.validate(data, shacl_graph=shapes, inference="none", allow_warnings=True)

    sh = rdflib.Namespace(_SH)
    label, notation = rdflib.URIRef(_LABEL), rdflib.URIRef(_NOTATION)
    results = set()
    for node in report.subjects(rdflib.RDF.type, sh.ValidationResult):
        focus = report.value(node, sh.focusNode)
        severity = str(report.value(node, sh.resultSeverity)).rsplit("#", 1)[-1]
        message = str(report.value(node, sh.resultMessage) or "")
        name = data.value(focus, label) or data.value(focus, notation) or focus
        results.add(Result(severity, str(focus), str(name), message))
    return sorted(results, key=lambda r: (SEVERITY_ORDER.get(r.severity, 9), r.label, r.message))


def validate_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    checklists: list[str] | None = None,
    extra_shapes: list[Path] | None = None,
) -> int:
    """Run `rdm graph validate`: print each result; exit 1 on any violation."""
    from rdm.graph.project import project

    dhf = Path(dhf_dir or "dhf")
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    for path in extra_shapes or []:
        if not Path(path).exists():
            print(f"Error: shapes file not found: {path}")
            return 2
    try:
        quads = project(dhf, allure_results_dir, checklists=checklists)
    except FileNotFoundError as error:
        print(f"Error: {error}")
        return 2
    results = validate(quads, extra_shapes)

    print("Graph validation (SHACL gate shapes)")
    print(f"DHF: {dhf.resolve()}\n")
    for r in results:
        print(f"  [{r.severity.upper():9}] {r.label}: {r.message}")
    violations = sum(r.severity == "Violation" for r in results)
    warnings = sum(r.severity == "Warning" for r in results)
    print()
    if violations:
        print(f"Graph validation FAILED: {violations} violation(s), {warnings} warning(s).")
        return 1
    print(f"Graph validation PASSED: no violations ({warnings} warning(s)).")
    return 0
