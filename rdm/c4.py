"""The architecture workspace's model and views, drawn and stamped (DI-70).

The architecture is one Structurizr workspace, ``<dhf>/c4/workspace.dsl``
(DI-66). ``rdm c4 draw`` exports it with Structurizr's CLI: the model as
``<dhf>/c4/workspace.json`` (what RDM reads, so reading needs no Java), and
each view as DOT, drawn by Graphviz to ``<dhf>/c4/views/<view>.svg`` (what the
design documents show, so GitHub, the docs site and a PDF show one picture,
and no browser draws it). Every drawn file is stamped with the SHA-256 of the
workspace; ``stale`` says which are not the current workspace's, and the
design gate fails on any (a derived file in git must not drift from its
source).

Drawing needs Java, Structurizr's CLI (``RDM_STRUCTURIZR``, or
``structurizr.sh`` / ``structurizr`` on the PATH) and Graphviz's ``dot``.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

WORKSPACE = Path("c4") / "workspace.dsl"
MODEL = Path("c4") / "workspace.json"
VIEWS = Path("c4") / "views"
STAMP_KEY = "rdm"
_SVG_STAMP = "<!-- rdm c4 draw: view {view}, workspace sha256:{digest} -->"
_SVG_STAMP_RE = re.compile(r"<!-- rdm c4 draw: view (\S+), workspace sha256:([0-9a-f]{64}) -->")
# Structurizr's DOT export leaves a bare & in its HTML labels, which Graphviz rejects.
_BARE_AMPERSAND = re.compile(r"&(?!(?:[a-zA-Z]+|#\d+|#x[0-9a-fA-F]+);)")


class DrawError(RuntimeError):
    """The workspace could not be exported or drawn."""


def digest(dhf_dir: Path) -> str | None:
    workspace = Path(dhf_dir) / WORKSPACE
    return hashlib.sha256(workspace.read_bytes()).hexdigest() if workspace.is_file() else None


def view_keys(model: dict) -> list[str]:
    """The keys of every view the exported workspace declares, in order."""
    views = model.get("views") or {}
    return [view["key"] for kind, items in sorted(views.items()) if kind.endswith("Views") for view in items]


def _tool(env: str, *names: str) -> list[str]:
    configured = os.environ.get(env)
    if configured:
        return [configured]
    for name in names:
        if (found := shutil.which(name)):
            return [found]
    raise DrawError(f"{names[0]} is not installed (or set {env})")


def _export(cli: list[str], workspace: Path, fmt: str, out: Path) -> None:
    done = subprocess.run([*cli, "export", "-w", str(workspace), "-f", fmt, "-o", str(out)],
                          capture_output=True, text=True)
    if done.returncode != 0:
        lines = [line for line in (done.stderr + done.stdout).splitlines() if line.strip()]
        raise DrawError(f"{workspace}: Structurizr could not export {fmt}: {lines[-1] if lines else 'no output'}")


def draw(dhf_dir: Path) -> list[str]:
    """Export the workspace's model and draw every view; return the view keys."""
    dhf_dir = Path(dhf_dir)
    workspace = dhf_dir / WORKSPACE
    if not workspace.is_file():
        raise DrawError(f"no architecture workspace: {workspace}")
    cli = _tool("RDM_STRUCTURIZR", "structurizr.sh", "structurizr")
    dot = _tool("RDM_DOT", "dot")
    stamp = digest(dhf_dir)
    with tempfile.TemporaryDirectory() as work:
        _export(cli, workspace, "json", Path(work) / "json")
        _export(cli, workspace, "dot", Path(work) / "dot")
        model = json.loads(next((Path(work) / "json").glob("*.json")).read_text(encoding="utf-8"))
        (model.get("properties") or {}).pop("structurizr.dsl", None)  # the workspace itself, base64
        model[STAMP_KEY] = {"workspace_sha256": stamp}
        keys = view_keys(model)
        drawn = {}
        for key in keys:
            source = Path(work) / "dot" / f"structurizr-{key}.dot"
            if not source.is_file():
                raise DrawError(f"{workspace}: Structurizr exported no DOT for view {key}")
            fixed = source.with_suffix(".fixed.dot")
            fixed.write_text(_BARE_AMPERSAND.sub("&amp;", source.read_text(encoding="utf-8")), encoding="utf-8")
            done = subprocess.run([*dot, "-Tsvg", str(fixed)], capture_output=True, text=True)
            if done.returncode != 0:
                raise DrawError(f"{workspace}: Graphviz could not draw view {key}: {done.stderr.strip()}")
            svg = done.stdout
            head, _, rest = svg.partition("\n")  # the stamp goes after the XML declaration
            drawn[key] = f"{head}\n{_SVG_STAMP.format(view=key, digest=stamp)}\n{rest}"
    (dhf_dir / MODEL).write_text(json.dumps(model, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    views = dhf_dir / VIEWS
    views.mkdir(parents=True, exist_ok=True)
    for old in views.glob("*.svg"):
        if old.stem not in drawn:
            old.unlink()
    for key, svg in drawn.items():
        (views / f"{key}.svg").write_text(svg, encoding="utf-8")
    return keys


def stale(dhf_dir: Path) -> list[str]:
    """Why the drawn files are not the current workspace's ([] when they are,
    or when the DHF has no architecture workspace)."""
    dhf_dir = Path(dhf_dir)
    current = digest(dhf_dir)
    if current is None:
        return []
    model_file = dhf_dir / MODEL
    if not model_file.is_file():
        return [f"{MODEL} is not drawn: run rdm c4 draw"]
    try:
        model = json.loads(model_file.read_text(encoding="utf-8"))
    except ValueError:
        return [f"{MODEL} is not valid JSON: run rdm c4 draw"]
    problems = []
    if (model.get(STAMP_KEY) or {}).get("workspace_sha256") != current:
        problems.append(f"{MODEL} was not drawn from the current {WORKSPACE.name}: run rdm c4 draw")
    keys = view_keys(model)
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


def draw_command(args) -> int:
    try:
        keys = draw(Path(args.dhf))
    except DrawError as error:
        print(f"Error: {error}")
        return 1
    print(f"Drew {len(keys)} view(s) of {Path(args.dhf) / WORKSPACE}: {', '.join(keys)}")
    return 0
