"""
The C4 model, read from the record's Mermaid C4 diagrams (DI-66).

The architecture lives where the design does: the system context (C1) and
the containers (C2) in the architecture document, each bounded context's
components (C3, with dynamic and deployment views where useful) in its own
design document, all as ```mermaid blocks. This module reads every view into
one model. One alias is one element across views; an element drawn ``_Ext``
in a view is shown there, not declared. The code level is the code a
component names with ``$link`` (a file or a directory): Mermaid has no code
diagram, and the code is the authority on itself.

Mermaid is the notation, not the model; the graph (rdm/graph) is where the
model is checked against the record and the code (DI-67, DI-68).
"""

from __future__ import annotations

import ast
import re
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record.sdd import context_of, parse_frontmatter

# A view's first statement: its C4 level.
LEVELS = {"C4Context": "context", "C4Container": "container", "C4Component": "component",
          "C4Dynamic": "dynamic", "C4Deployment": "deployment"}

# Element functions: (kind, positional fields after alias and name).
_ELEMENTS = {}
for _base, _kind, _fields in (("Person", "person", ("description",)), ("System", "system", ("description",)),
                              ("Container", "container", ("technology", "description")),
                              ("Component", "component", ("technology", "description"))):
    for _shape in ("", "Db", "Queue"):
        if _base == "Person" and _shape:
            continue
        for _ext in ("", "_Ext"):
            _ELEMENTS[_base + _shape + _ext] = (_kind, _shape.lower(), bool(_ext), _fields)

# Boundary functions: the kind of element the boundary is (None: a grouping).
_BOUNDARIES = {"System_Boundary": "system", "Container_Boundary": "container",
               "Enterprise_Boundary": None, "Boundary": None}
# Deployment nodes group container instances without changing what contains them.
_NODES = {"Deployment_Node", "Node", "Node_L", "Node_R"}
_RELATIONS = {"Rel", "BiRel", "Rel_U", "Rel_Up", "Rel_D", "Rel_Down", "Rel_L", "Rel_Left", "Rel_R", "Rel_Right",
              "Rel_Back"}

_FENCE = re.compile(r"^```mermaid[^\n]*\n(.*?)^```", re.M | re.S)
_CALL = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\((.*)\)\s*(\{)?\s*$")


@dataclass
class Element:
    """A person, software system, container or component, as one view declares it."""

    alias: str
    kind: str                    # person | system | container | component
    name: str = ""
    technology: str = ""
    description: str = ""
    external: bool = False
    shape: str = ""              # "" | db | queue
    link: str = ""               # the code a component names ($link)
    parent: str | None = None    # the boundary element (system or container) it sits in
    document: str = ""           # repo-relative path of the declaring document
    context: str = ""            # the bounded context of that document
    level: str = ""              # the view's level


@dataclass
class Relationship:
    source: str
    target: str
    label: str = ""
    technology: str = ""
    document: str = ""
    level: str = ""


@dataclass
class Model:
    """The architecture the record's views declare together."""

    elements: dict[str, Element] = field(default_factory=dict)    # alias -> its declaration
    mentions: list[Element] = field(default_factory=list)         # every drawing, external ones too
    boundaries: list[Element] = field(default_factory=list)       # every boundary drawn (its kind and parent)
    relationships: list[Relationship] = field(default_factory=list)
    declared_by: dict[str, list[str]] = field(default_factory=dict)  # alias -> documents declaring it (not _Ext)

    @property
    def components(self) -> list[Element]:
        return [e for e in self.elements.values() if e.kind == "component"]


def _arguments(text: str) -> tuple[list[str], dict[str, str]]:
    """A call's positional and ``$key=value`` arguments; quotes keep commas."""
    parts, current, quoted = [], "", False
    for char in text:
        if char == '"':
            quoted = not quoted
            current += char
        elif char == "," and not quoted:
            parts.append(current)
            current = ""
        else:
            current += char
    parts.append(current)
    positional, named = [], {}
    for part in (p.strip() for p in parts):
        if not part:
            continue
        match = re.fullmatch(r'\$(\w+)\s*=\s*(.*)', part)
        if match:
            named[match.group(1)] = match.group(2).strip().strip('"')
        else:
            positional.append(part.strip('"'))
    return positional, named


def parse_views(text: str) -> list[tuple[str, list[tuple[str, list[str], dict[str, str], list[str]]]]]:
    """Each Mermaid C4 view in ``text``: its level and its statements, as
    ``(function, positional, named, enclosing boundaries)``."""
    views = []
    for block in _FENCE.findall(text):
        lines = [line.split("%%", 1)[0].rstrip() for line in block.splitlines()]
        lines = [line for line in lines if line.strip()]
        if not lines or lines[0].strip() not in LEVELS:
            continue
        statements, stack = [], []
        for line in lines[1:]:
            if line.strip() == "}":
                if stack:
                    stack.pop()
                continue
            match = _CALL.match(line)
            if not match:
                continue
            function, (positional, named), opens = match.group(1), _arguments(match.group(2)), match.group(3)
            statements.append((function, positional, named, list(stack)))
            if opens:
                stack.append(f"{function}:{positional[0] if positional else ''}")
        views.append((LEVELS[lines[0].strip()], statements))
    return views


def _parent(stack: list[str], kinds: set[str]) -> str | None:
    """The innermost enclosing boundary that is a system or container."""
    for entry in reversed(stack):
        function, _, alias = entry.partition(":")
        if _BOUNDARIES.get(function) in kinds:
            return alias
    return None


def read_document(path: Path, document: str, context: str, model: Model) -> None:
    """Add the views of one Markdown document to ``model``."""
    for level, statements in parse_views(path.read_text(encoding="utf-8")):
        for function, positional, named, stack in statements:
            if function in _ELEMENTS and positional:
                kind, shape, external, fields = _ELEMENTS[function]
                values = dict(zip(fields, positional[2:]))
                element = Element(
                    alias=positional[0], kind=kind, name=positional[1] if len(positional) > 1 else positional[0],
                    technology=named.get("techn", values.get("technology", "")),
                    description=named.get("descr", values.get("description", "")),
                    external=external, shape=shape, link=named.get("link", "").strip(),
                    parent=_parent(stack, {"container"} if kind == "component" else {"system"}),
                    document=document, context=context, level=level)
                model.mentions.append(element)
                if not external:
                    model.declared_by.setdefault(element.alias, [])
                    if document not in model.declared_by[element.alias]:
                        model.declared_by[element.alias].append(document)
                current = model.elements.get(element.alias)
                if current is None or (current.external and not external):
                    model.elements[element.alias] = element
            elif function in _BOUNDARIES and positional:
                model.boundaries.append(Element(
                    alias=positional[0], kind=_BOUNDARIES[function] or "group",
                    name=positional[1] if len(positional) > 1 else positional[0],
                    parent=_parent(stack, {"system"}), document=document, context=context, level=level))
            elif function in _RELATIONS and len(positional) >= 2:
                source, target = positional[0], positional[1]
                if function == "Rel_Back":
                    source, target = target, source
                label = positional[2] if len(positional) > 2 else named.get("label", "")
                technology = positional[3] if len(positional) > 3 else named.get("techn", "")
                model.relationships.append(Relationship(source, target, label, technology, document, level))
                if function == "BiRel":
                    model.relationships.append(Relationship(target, source, label, technology, document, level))


def read_model(dhf_dir: Path, root: Path | None = None) -> Model:
    """The C4 model every document of the DHF declares, in path order."""
    dhf_dir = Path(dhf_dir)
    root = Path(root) if root is not None else dhf_dir.parent
    model = Model()
    for path in sorted(dhf_dir.rglob("*.md")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if "```mermaid" not in text:
            continue
        front = parse_frontmatter(text)
        context = str(front.get("context") or "").strip() or context_of(path)
        try:
            document = path.resolve().relative_to(root.resolve()).as_posix()
        except ValueError:
            document = path.as_posix()
        read_document(path, document, context, model)
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
