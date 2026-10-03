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
  as a dependency between the two: the coupling the code actually has.
"""

from __future__ import annotations

from pathlib import Path

import pyoxigraph as ox

from rdm.graph.ns import DCTERMS, RDF, XSD
from rdm.architecture.model import component_dependencies, component_of, read_model

_TYPE = ox.NamedNode(RDF + "type")
_BOOLEAN = ox.NamedNode(XSD + "boolean")
_CLASSES = {"person": "Person", "system": "SoftwareSystem", "container": "Container", "component": "Component"}


def project_architecture(ds, dhf: Path, root: Path, rdm) -> None:
    """Add the architecture and code graphs for the DHF's workspace, if it has one."""
    model = read_model(dhf, root)
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

    components = model.code_components
    source_file, path = rdm("SourceFile"), rdm("path")
    files = {q.subject for q in ds.quads if q.predicate == _TYPE and q.object == source_file}
    for subject, value in sorted({(q.subject, q.object.value) for q in ds.quads
                                  if q.predicate == path and q.subject in files}, key=lambda x: x[1]):
        owner = component_of(value, components)
        if owner is not None:
            ds.add(subject, rdm("inComponent"), node[owner.alias], g)

    for (source, target), (file, imported) in sorted(component_dependencies(model, project).items()):
        ds.add(node[source], rdm("dependsOn"), node[target], "code")
        dep = ds.node("dependency", f"{source}/{target}")
        ds.thing(dep, rdm("Dependency"), f"{source} imports {target}", "code")
        ds.add(dep, rdm("source"), node[source], "code")
        ds.add(dep, rdm("target"), node[target], "code")
        ds.add(dep, rdm("importedBy"), file, "code")
        ds.add(dep, rdm("imports"), imported, "code")
