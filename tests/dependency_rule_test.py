"""The dependency rule of the system architecture holds in the code
(dhf/documents/architecture.md, "Dependency rule").

A context imports only contexts of a lower layer and the shared kernel; the
kernel imports only itself; the composition root may import anything. Every
module belongs to a context's code, so no code escapes the rule. Layers,
code, kernel and composition root are read from the architecture's
frontmatter: the test checks the code against the record, never against a
copy of it.
"""

from __future__ import annotations

import ast
from pathlib import Path

import yaml

ROOT = Path(__file__).parents[1]
DHF = ROOT / "dhf"


def _architecture() -> dict:
    return yaml.safe_load((DHF / "documents" / "architecture.md").read_text().split("---", 2)[1])


def _module_file(name: str) -> str | None:
    """The repo-relative file of a dotted module name inside the repository, if any."""
    base = ROOT.joinpath(*name.split("."))
    for candidate in (base.with_suffix(".py"), base / "__init__.py"):
        if candidate.is_file():
            return candidate.relative_to(ROOT).as_posix()
    return None


def imported_files(path: Path) -> set[str]:
    """The repo-relative files a Python file imports, anywhere in it, relative
    imports included."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    package = path.relative_to(ROOT).with_suffix("").parts[:-1]
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            files = [_module_file(alias.name) for alias in node.names]
        elif isinstance(node, ast.ImportFrom):
            base = ".".join(package[: len(package) - node.level + 1]) if node.level else ""
            stem = ".".join(p for p in (base, node.module or "") if p)
            files = [_module_file(f"{stem}.{alias.name}") or _module_file(stem) for alias in node.names]
        else:
            continue
        found.update(f for f in files if f is not None)
    return found


def _owners() -> tuple[dict[str, str], list[str]]:
    """Each module's context ('kernel' and 'root' for the two that are not
    contexts), and the modules no context's code holds."""
    arch = _architecture()
    kernel, root = arch["kernel"], arch["composition_root"]
    code = [(path, c["id"]) for c in arch["contexts"] for path in c.get("code") or []]
    owners, unowned = {}, []
    for path in sorted((ROOT / "rdm").rglob("*.py")):
        rel = path.relative_to(ROOT).as_posix()
        if rel == root or rel == "rdm/__init__.py":
            owners[rel] = "root"
        elif rel.startswith(kernel):
            owners[rel] = "kernel"
        elif matches := [(len(p), ctx) for p, ctx in code if rel == p or (p.endswith("/") and rel.startswith(p))]:
            owners[rel] = max(matches)[1]
        else:
            unowned.append(rel)
    return owners, unowned


def test_every_module_belongs_to_a_context() -> None:
    _, unowned = _owners()
    assert unowned == [], f"modules no context's code holds: {unowned}"


def test_no_import_breaks_the_dependency_rule() -> None:
    layer = {c["id"]: c["layer"] for c in _architecture()["contexts"]}
    owners, _ = _owners()
    breaks = []
    for src, src_ctx in owners.items():
        for dst in sorted(imported_files(ROOT / src)):
            dst_ctx = owners.get(dst)
            if src_ctx == "root" or dst_ctx in (None, "kernel", src_ctx):
                continue
            if src_ctx == "kernel" or dst_ctx == "root" or layer[dst_ctx] >= layer[src_ctx]:
                breaks.append(f"{src} ({src_ctx}) -> {dst} ({dst_ctx})")
    assert breaks == [], "imports that break the dependency rule:\n  " + "\n  ".join(breaks)
