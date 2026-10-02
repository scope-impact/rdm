"""
The C4 model, read from the architecture workspace (DI-66), and whether its
drawn files are current (DI-70).

The architecture is one Structurizr workspace, ``<dhf>/c4/workspace.dsl``: the
model (people, software systems, containers, components grouped by bounded
context) and its views. ``rdm c4 draw`` (rdm/c4.py) exports it as
``<dhf>/c4/workspace.json`` and draws each view to ``<dhf>/c4/views/<view>.svg``,
each stamped with the workspace's SHA-256. This module reads the JSON, so
reading needs neither Java nor a parser, and checks the stamps, so checking
needs neither. One identifier is one element. The code level is the code a
component names with its ``code`` property (a file or a directory).

The graph (rdm/graph) is where the model is checked against the record and the
code (DI-67, DI-68).
"""

from __future__ import annotations

import ast
import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

WORKSPACE = Path("c4") / "workspace.dsl"
MODEL = Path("c4") / "workspace.json"
VIEWS = Path("c4") / "views"
STAMP_KEY = "rdm"
SVG_STAMP = "<!-- rdm c4 draw: view {view}, workspace sha256:{digest} -->"
_SVG_STAMP_RE = re.compile(r"<!-- rdm c4 draw: view (\S+), workspace sha256:([0-9a-f]{64}) -->")
_IDENTIFIER = "structurizr.dsl.identifier"
# What each kind of element contains, as Structurizr's JSON nests it.
_CHILDREN = {"system": ("containers", "container"), "container": ("components", "component")}


@dataclass
class Element:
    """A person, software system, container or component of the workspace."""

    alias: str                   # the workspace's identifier for it
    kind: str                    # person | system | container | component
    name: str = ""
    technology: str = ""
    description: str = ""
    external: bool = False
    link: str = ""               # the code a component names (its "code" property)
    parent: str | None = None    # the system or container it sits in
    context: str = ""            # a component's bounded context: its group


@dataclass
class Relationship:
    source: str
    target: str
    label: str = ""
    technology: str = ""


@dataclass
class Model:
    """The architecture the workspace declares."""

    document: str = ""                                             # the workspace, repo-relative
    elements: dict[str, Element] = field(default_factory=dict)    # alias -> the element
    relationships: list[Relationship] = field(default_factory=list)
    views: list[str] = field(default_factory=list)                 # every view's key

    @property
    def components(self) -> list[Element]:
        return [e for e in self.elements.values() if e.kind == "component"]


def workspace_digest(dhf_dir: Path) -> str | None:
    workspace = Path(dhf_dir) / WORKSPACE
    return hashlib.sha256(workspace.read_bytes()).hexdigest() if workspace.is_file() else None


def view_keys(workspace: dict) -> list[str]:
    """The keys of every view the exported workspace declares, in order."""
    views = workspace.get("views") or {}
    return [view["key"] for kind, items in sorted(views.items()) if kind.endswith("Views") for view in items]


def _walk(items: list[dict] | None, kind: str, parent: str | None, model: Model, edges: list) -> None:
    """Every element of ``items`` and what it contains, into the model; each
    one's relationships, by Structurizr id, into ``edges``."""
    for item in items or []:
        props = item.get("properties") or {}
        alias = props.get(_IDENTIFIER) or str(item.get("id"))
        tags = {t.strip() for t in str(item.get("tags") or "").split(",")}
        model.elements[alias] = Element(
            alias=alias, kind=kind, name=str(item.get("name") or ""), technology=str(item.get("technology") or ""),
            description=str(item.get("description") or ""), external="External" in tags,
            link=str(props.get("code") or ""), parent=parent, context=str(item.get("group") or ""))
        edges.append((str(item.get("id")), alias, item.get("relationships") or []))
        if kind in _CHILDREN:
            key, child = _CHILDREN[kind]
            _walk(item.get(key), child, alias, model, edges)


def read_model(dhf_dir: Path, root: Path | None = None) -> Model:
    """The C4 model of the DHF's architecture workspace (empty when it has none)."""
    dhf_dir = Path(dhf_dir)
    root = Path(root) if root is not None else dhf_dir.parent
    path = dhf_dir / MODEL
    if not path.is_file():
        return Model()
    workspace = json.loads(path.read_text(encoding="utf-8"))
    try:
        document = (dhf_dir / WORKSPACE).resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        document = (dhf_dir / WORKSPACE).as_posix()
    model = Model(document=document, views=view_keys(workspace))
    data = workspace.get("model") or {}
    edges: list[tuple[str, str, list]] = []
    _walk(data.get("people"), "person", None, model, edges)
    _walk(data.get("softwareSystems"), "system", None, model, edges)
    aliases = {ident: alias for ident, alias, _ in edges}
    for _, source, relationships in edges:
        for rel in relationships:
            target = aliases.get(str(rel.get("destinationId")))
            if target is not None and not rel.get("linkedRelationshipId"):  # an implied one is not declared
                model.relationships.append(Relationship(
                    source=source, target=target, label=str(rel.get("description") or ""),
                    technology=str(rel.get("technology") or "")))
    return model


def stale(dhf_dir: Path) -> list[str]:
    """Why the drawn files are not the current workspace's ([] when they are,
    or when the DHF has no architecture workspace)."""
    dhf_dir = Path(dhf_dir)
    current = workspace_digest(dhf_dir)
    if current is None:
        return []
    model_file = dhf_dir / MODEL
    if not model_file.is_file():
        return [f"{MODEL} is not drawn: run rdm c4 draw"]
    try:
        workspace = json.loads(model_file.read_text(encoding="utf-8"))
    except ValueError:
        return [f"{MODEL} is not valid JSON: run rdm c4 draw"]
    problems = []
    if (workspace.get(STAMP_KEY) or {}).get("workspace_sha256") != current:
        problems.append(f"{MODEL} was not drawn from the current {WORKSPACE.name}: run rdm c4 draw")
    keys = view_keys(workspace)
    views = dhf_dir / VIEWS
    for key in keys:
        svg = views / f"{key}.svg"
        found = _SVG_STAMP_RE.search(svg.read_text(encoding="utf-8")) if svg.is_file() else None
        if found is None:
            problems.append(f"view {key} has no image: run rdm c4 draw")
        elif found.groups() != (key, current):
            problems.append(f"view {key}'s image was not drawn from the current {WORKSPACE.name}: run rdm c4 draw")
    for svg in sorted(views.glob("*.svg")) if views.is_dir() else []:
        if svg.stem not in keys:
            problems.append(f"{VIEWS / svg.name} is the image of no view: run rdm c4 draw")
    return problems


def component_of(path: str, components: list[Element]) -> Element | None:
    """The component whose code path is the longest match for a repo-relative
    file path: a link to the file itself, or to a directory above it."""
    best = None
    for component in components:
        link = component.link.rstrip("/")
        if link and (path == link or path.startswith(link + "/")):
            if best is None or len(link) > len(best.link.rstrip("/")):
                best = component
    return best


def _module_file(root: Path, module: str) -> str | None:
    """The repo-relative file a Python module name resolves to, if it is in the repo."""
    base = root / Path(*module.split("."))
    for candidate in (base.with_suffix(".py"), base / "__init__.py"):
        if candidate.is_file():
            return candidate.relative_to(root).as_posix()
    return None


def _imported_files(root: Path, path: Path) -> set[str]:
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"))
    except (OSError, SyntaxError, ValueError):
        return set()
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            modules = [alias.name for alias in node.names]
        elif isinstance(node, ast.ImportFrom) and node.module and not node.level:
            modules = [node.module] + [f"{node.module}.{alias.name}" for alias in node.names]
        else:
            continue
        for module in modules:
            if (file := _module_file(root, module)) is not None:
                found.add(file)
    return found


def component_dependencies(model: Model, root: Path) -> dict[tuple[str, str], tuple[str, str]]:
    """Every Python import from one component's code into another component's:
    ``(source alias, target alias) -> (importing file, imported file)``, one
    example per pair."""
    root = Path(root)
    components = [c for c in model.components if not c.external and c.link]
    dependencies: dict[tuple[str, str], tuple[str, str]] = {}
    for component in components:
        target = root / component.link
        files = sorted(target.rglob("*.py")) if target.is_dir() else [target] if target.suffix == ".py" else []
        for file in files:
            rel = file.relative_to(root).as_posix()
            if component_of(rel, components) is not component:
                continue  # a more specific component owns this file
            for imported in sorted(_imported_files(root, file)):
                other = component_of(imported, components)
                if other is not None and other is not component:
                    dependencies.setdefault((component.alias, other.alias), (rel, imported))
    return dependencies
