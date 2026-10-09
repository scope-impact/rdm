"""The C4 model in the graph (DI-67).

Two named graphs, one per source, so a query can tell what the architecture
claims from what the code does:

- ``architecture`` — the workspace's model (rdm/architecture/model.py): each person,
  software system, container and component, typed, with its name, technology,
  description and external flag, and the element that contains it; each
  component's bounded context and code; each relationship as a node with its
  source, target, description and technology; and the component each source
  file in the graph belongs to (the longest matching code path).
- ``code`` — for Python, each import from one component's code into another's,
  as a dependency between the two: the coupling the code actually has; a
  component whose code holds no Python is marked as one whose dependencies
  were not read.
- ``unit-coverage`` — given the unit tests' coverage report, each measured
  component's lines its unit tests ran and lines measured: the component's own
  properties, linked to no run and no design input, for coverage is unit-test
  evidence, never acceptance evidence (Design Review 63).

And, in ``executions``, the components each run names (DI-56): by a
``component`` label's key, or as the owner of a file an ``output`` label
names. A key the model does not declare is recorded, for a shape to warn on.
What a run reaches from them is derived, never stored (DI-73).
"""

from __future__ import annotations

from pathlib import Path

from rdm.graph import rdf as ox

from rdm.graph.ns import DCTERMS, RDF, XSD
from rdm.architecture.model import component_dependencies, component_of, python_files, read_model

_TYPE = ox.NamedNode(RDF + "type")
_BOOLEAN = ox.NamedNode(XSD + "boolean")
_INTEGER = ox.NamedNode(XSD + "integer")
_CLASSES = {"person": "Person", "system": "SoftwareSystem", "container": "Container", "component": "Component"}


def project_architecture(ds, dhf: Path, root: Path, rdm, coverage: dict | None = None) -> None:
    """Add the architecture and code graphs for the DHF's workspace, if it has
    one, and the unit-coverage graph from ``coverage`` (release.verify's
    ``unit_coverage``) when given."""
    model = read_model(dhf, root)
    declared = {alias for alias, e in model.elements.items() if e.kind == "component"}
    for run, key in ds.component_labels:  # DI-56: a component label names a component by its key
        if key in declared:
            ds.add(run, rdm("namesComponent"), ds.node("element", key), "executions")
        else:
            ds.add(run, rdm("unknownComponent"), key, "executions")
    if not model.elements:
        return
    # A component's code path is relative to its project, the DHF's parent:
    # the repository root for most, not for a project nested in another one.
    project = Path(dhf).parent
    g = "architecture"
    node = {alias: ds.node("element", alias) for alias in model.elements}
    for alias, e in model.elements.items():
        el = ds.thing(node[alias], rdm(_CLASSES[e.kind]), e.name or alias, g)
        ds.add(el, ox.NamedNode(DCTERMS + "identifier"), alias, g)
        if e.technology:
            ds.add(el, rdm("technology"), e.technology, g)
        if e.description:
            ds.add(el, ox.NamedNode(DCTERMS + "description"), e.description, g)
        ds.add(el, rdm("external"), ox.Literal("true" if e.external else "false", datatype=_BOOLEAN), g)
        if e.parent in node:
            ds.add(el, rdm("containedIn"), node[e.parent], g)
        if e.context:
            ds.add(el, rdm("inContext"), ds.node("context", e.context), g)
        if e.link:
            ds.add(el, rdm("code"), e.link, g)
            if not e.external and not (project / e.link).exists():  # DI-68: a shape sees only the graph
                ds.add(el, rdm("missingCode"), e.link, g)
    for n, r in enumerate(model.relationships, 1):
        rel = ds.thing(ds.node("relationship", f"{r.source}/{r.target}/{n}"), rdm("Relationship"),
                       f"{r.source} {r.label or '->'} {r.target}", g)
        ds.add(rel, rdm("source"), node[r.source], g)
        ds.add(rel, rdm("target"), node[r.target], g)
        if r.label:
            ds.add(rel, ox.NamedNode(DCTERMS + "description"), r.label, g)
        if r.technology:
            ds.add(rel, rdm("technology"), r.technology, g)

    for entry in (coverage or {}).get("components", []):  # DI-67: unit-test evidence, the component's alone
        el = node[entry["component"]]
        ds.add(el, rdm("unitLinesRun"), ox.Literal(str(entry["executed"]), datatype=_INTEGER), "unit-coverage")
        ds.add(el, rdm("unitLinesMeasured"), ox.Literal(str(entry["measured"]), datatype=_INTEGER), "unit-coverage")

    components = model.code_components
    source_file, path = rdm("SourceFile"), rdm("path")
    files = {q.subject for q in ds.quads if q.predicate == _TYPE and q.object == source_file}
    owners = {}
    for subject, value in sorted({(q.subject, q.object.value) for q in ds.quads
                                  if q.predicate == path and q.subject in files}, key=lambda x: x[1]):
        owner = component_of(value, components)
        if owner is not None:
            ds.add(subject, rdm("inComponent"), node[owner.alias], g)
            owners[subject] = node[owner.alias]
    exercises = rdm("exercisesOutput")
    for run, source in sorted({(q.subject, q.object) for q in ds.quads if q.predicate == exercises}, key=str):
        if source in owners:  # DI-56: an output label names the component that holds its file
            ds.add(run, rdm("namesComponent"), owners[source], "executions")

    for c in components:  # DI-67: imports are read from Python only; elsewhere the check did not run
        if (project / c.link).exists() and not python_files(c, project):
            ds.add(node[c.alias], rdm("dependenciesNotRead"), ox.Literal("true", datatype=_BOOLEAN), "code")
    for (source, target), (file, imported) in sorted(component_dependencies(model, project).items()):
        ds.add(node[source], rdm("dependsOn"), node[target], "code")
        dep = ds.node("dependency", f"{source}/{target}")
        ds.thing(dep, rdm("Dependency"), f"{source} imports {target}", "code")
        ds.add(dep, rdm("source"), node[source], "code")
        ds.add(dep, rdm("target"), node[target], "code")
        ds.add(dep, rdm("importedBy"), file, "code")
        ds.add(dep, rdm("imports"), imported, "code")
