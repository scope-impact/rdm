"""The dependency rule of the system architecture holds in the code
(dhf/documents/architecture.md, "Dependency rule").

A context imports only contexts of a lower layer and the shared kernel; the
kernel imports only itself; the composition root may import anything. Every
module is named by a component of the architecture workspace, so no code
escapes the rule. Layers, kernel and composition root are read from the
architecture's frontmatter, components from the workspace: the test checks
the code against the record, never against a copy of it.
"""

from __future__ import annotations

import ast
from pathlib import Path

import yaml

from rdm.architecture.model import component_of, read_model

ROOT = Path(__file__).parents[1]
DHF = ROOT / "dhf"


def _architecture() -> dict:
    return yaml.safe_load((DHF / "documents" / "architecture.md").read_text().split("---", 2)[1])


def _module_file(module: str) -> str | None:
    base = ROOT / Path(*module.split("."))
    for candidate in (base.with_suffix(".py"), base / "__init__.py"):
        if candidate.is_file():
            return candidate.relative_to(ROOT).as_posix()
    return None


def _imports(path: Path) -> set[str]:
    """The rdm modules a file imports, anywhere in it, as repo-relative files."""
    package = path.relative_to(ROOT).with_suffix("").parts[:-1]
    found = set()
    for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
        if isinstance(node, ast.Import):
            modules = [alias.name for alias in node.names]
        elif isinstance(node, ast.ImportFrom):
            base = ".".join(package[: len(package) - node.level + 1]) if node.level else ""
            stem = ".".join(p for p in (base, node.module or "") if p)
            modules = [stem] + [f"{stem}.{alias.name}" for alias in node.names]
        else:
            continue
        for module in modules:
            file = _module_file(module) if module.startswith("rdm") else None
            if file is not None and file != "rdm/__init__.py":  # `from rdm import x` names x, not rdm
                found.add(file)
    return found


def _owners() -> tuple[dict[str, str], list[str]]:
    """Each module's context ('kernel' and 'root' for the two that are not
    contexts), and the modules no component names."""
    arch = _architecture()
    kernel, root = arch["kernel"], arch["composition_root"]
    components = [c for c in read_model(DHF, ROOT).components if c.link]
    owners, unowned = {}, []
    for path in sorted((ROOT / "rdm").rglob("*.py")):
        rel = path.relative_to(ROOT).as_posix()
        if rel == root or rel == "rdm/__init__.py":
            owners[rel] = "root"
        elif rel.startswith(kernel):
            owners[rel] = "kernel"
        elif (component := component_of(rel, components)) is not None:
            owners[rel] = component.context
        else:
            unowned.append(rel)
    # A package's __init__ belongs to the one context that owns its modules.
    for rel in [u for u in unowned if u.endswith("/__init__.py")]:
        package = rel.removesuffix("__init__.py")
        contexts = {c for m, c in owners.items() if m.startswith(package)}
        if len(contexts) == 1:
            owners[rel] = contexts.pop()
            unowned.remove(rel)
    return owners, unowned


def test_every_module_is_named_by_a_component() -> None:
    _, unowned = _owners()
    assert unowned == [], f"modules no component of the workspace names: {unowned}"


def test_no_import_breaks_the_dependency_rule() -> None:
    layer = {c["id"]: c["layer"] for c in _architecture()["contexts"]}
    owners, _ = _owners()
    breaks = []
    for src, src_ctx in owners.items():
        for dst in sorted(_imports(ROOT / src)):
            dst_ctx = owners.get(dst)
            if src_ctx == "root" or dst_ctx in (None, "kernel", src_ctx):
                continue
            if src_ctx == "kernel" or dst_ctx == "root" or layer[dst_ctx] >= layer[src_ctx]:
                breaks.append(f"{src} ({src_ctx}) -> {dst} ({dst_ctx})")
    assert breaks == [], "imports that break the dependency rule:\n  " + "\n  ".join(breaks)
