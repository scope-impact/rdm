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

from pathlib import Path

import yaml

from rdm.architecture.model import component_of, imported_files, read_model

ROOT = Path(__file__).parents[1]
DHF = ROOT / "dhf"


def _architecture() -> dict:
    return yaml.safe_load((DHF / "documents" / "architecture.md").read_text().split("---", 2)[1])


def _owners() -> tuple[dict[str, str], list[str]]:
    """Each module's context ('kernel' and 'root' for the two that are not
    contexts), and the modules no component names."""
    arch = _architecture()
    kernel, roots = arch["kernel"], arch["composition_root"]
    roots = [roots] if isinstance(roots, str) else roots  # a file, or a package, each
    components = read_model(DHF, ROOT).code_components
    owners, unowned = {}, []
    for path in sorted((ROOT / "rdm").rglob("*.py")):
        rel = path.relative_to(ROOT).as_posix()
        if rel == "rdm/__init__.py" or any(rel == r or rel.startswith(r) for r in roots):
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
        for dst in sorted(imported_files(ROOT, ROOT / src)):
            dst_ctx = owners.get(dst)
            if src_ctx == "root" or dst_ctx in (None, "kernel", src_ctx):
                continue
            if src_ctx == "kernel" or dst_ctx == "root" or layer[dst_ctx] >= layer[src_ctx]:
                breaks.append(f"{src} ({src_ctx}) -> {dst} ({dst_ctx})")
    assert breaks == [], "imports that break the dependency rule:\n  " + "\n  ".join(breaks)
