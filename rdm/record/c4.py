"""
The C4 model, read from the architecture workspace (DI-66).

The architecture is one Structurizr workspace, ``<dhf>/c4/workspace.dsl``: the
model (people, software systems, containers, components grouped by bounded
context) and its views. ``rdm c4 draw`` exports it as ``<dhf>/c4/workspace.json``
(DI-70), and this module reads that JSON, so reading needs neither Java nor a
parser. One identifier is one element. The code level is the code a component
names with its ``code`` property (a file or a directory): the code is the
authority on itself.

The graph (rdm/graph) is where the model is checked against the record and the
code (DI-67, DI-68).
"""

from __future__ import annotations

import ast
import json
from dataclasses import dataclass, field
from pathlib import Path

MODEL = Path("c4") / "workspace.json"
_KINDS = (("people", "person"), ("softwareSystems", "system"), ("containers", "container"),
          ("components", "component"))
_IDENTIFIER = "structurizr.dsl.identifier"


@dataclass
class Element:
    """A person, software system, container or component of the workspace."""

    alias: str                   # the workspace's identifier for it
    kind: str                    # person | system | container | component
    name: str = ""
    technology: str = ""
    description: str = ""
    external: bool = False
    shape: str = ""              # "" | db | queue
    link: str = ""               # the code a component names (its "code" property)
    parent: str | None = None    # the system or container it sits in
    document: str = ""           # repo-relative path of the workspace
    context: str = ""            # a component's bounded context: its group


@dataclass
class Relationship:
    source: str
    target: str
    label: str = ""
    technology: str = ""
    document: str = ""


@dataclass
class Model:
    """The architecture the workspace declares."""

    elements: dict[str, Element] = field(default_factory=dict)    # alias -> the element
    relationships: list[Relationship] = field(default_factory=list)
    views: list[str] = field(default_factory=list)                 # every view's key

    @property
    def components(self) -> list[Element]:
        return [e for e in self.elements.values() if e.kind == "component"]


def _tags(item: dict) -> set[str]:
    return {t.strip() for t in str(item.get("tags") or "").split(",") if t.strip()}


def _walk(items: list[dict], kind_index: int, parent: str | None, ids: dict, out: list) -> None:
    """Every element below ``items`` (people, systems, their containers, their
    components), each with its parent's alias."""
    key, kind = _KINDS[kind_index]
    for item in items or []:
        props = item.get("properties") or {}
        alias = props.get(_IDENTIFIER) or str(item.get("id"))
        ids[str(item.get("id"))] = alias
        tags = _tags(item)
        out.append((item, Element(
            alias=alias, kind=kind, name=str(item.get("name") or ""), technology=str(item.get("technology") or ""),
            description=str(item.get("description") or ""), external="External" in tags,
            shape="db" if "Database" in tags else "queue" if "Queue" in tags else "",
            link=str(props.get("code") or ""), parent=parent, context=str(item.get("group") or ""))))
        for child_index in range(kind_index + 1, len(_KINDS)):
            child_key = _KINDS[child_index][0]
            if item.get(child_key):
                _walk(item[child_key], child_index, alias, ids, out)


def read_model(dhf_dir: Path, root: Path | None = None) -> Model:
    """The C4 model of the DHF's architecture workspace (empty when it has none)."""
    from rdm.c4 import view_keys

    dhf_dir = Path(dhf_dir)
    root = Path(root) if root is not None else dhf_dir.parent
    path = dhf_dir / MODEL
    model = Model()
    if not path.is_file():
        return model
    workspace = json.loads(path.read_text(encoding="utf-8"))
    try:
        document = (dhf_dir / "c4" / "workspace.dsl").resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        document = (dhf_dir / "c4" / "workspace.dsl").as_posix()
    data = workspace.get("model") or {}
    ids: dict[str, str] = {}
    found: list[tuple[dict, Element]] = []
    _walk(data.get("people"), 0, None, ids, found)
    _walk(data.get("softwareSystems"), 1, None, ids, found)
    for item, element in found:
        element.document = document
        model.elements[element.alias] = element
    for item, element in found:
        for rel in item.get("relationships") or []:
            if rel.get("linkedRelationshipId"):
                continue  # implied by a relationship below it, not declared
            target = ids.get(str(rel.get("destinationId")))
            if target is not None:
                model.relationships.append(Relationship(
                    source=element.alias, target=target, label=str(rel.get("description") or ""),
                    technology=str(rel.get("technology") or ""), document=document))
    model.views = view_keys(workspace)
    return model


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
