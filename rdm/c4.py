"""``rdm c4 draw``: the architecture workspace's model and views, drawn and
stamped (DI-70).

The architecture is one Structurizr workspace, ``<dhf>/c4/workspace.dsl``
(DI-66). Drawing exports it with Structurizr's CLI: the model as
``<dhf>/c4/workspace.json`` (what RDM reads), and each view as DOT, drawn by
Graphviz to ``<dhf>/c4/views/<view>.svg`` (what the design documents show, so
GitHub, the docs site and a PDF show one picture, and no browser draws it).
Every drawn file is stamped with the SHA-256 of the workspace; reading the
model and checking the stamps is the record's (rdm/record/c4.py), and needs
none of the tools below.

Drawing needs Java, Structurizr's CLI (``RDM_STRUCTURIZR``, or
``structurizr.sh`` / ``structurizr`` on the PATH) and Graphviz's ``dot``
(``RDM_DOT``, or on the PATH).
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from rdm.record.c4 import MODEL, STAMP_KEY, SVG_STAMP, VIEWS, WORKSPACE, view_keys, workspace_digest

# Structurizr's DOT export leaves a bare & in its HTML labels (e.g. "V&V"), which
# Graphviz rejects as malformed; escape it until the exporter does.
_BARE_AMPERSAND = re.compile(r"&(?!(?:[a-zA-Z]+|#\d+|#x[0-9a-fA-F]+);)")


class DrawError(RuntimeError):
    """The workspace could not be exported or drawn."""


def _tool(env: str, *names: str) -> list[str]:
    configured = os.environ.get(env)
    if configured:
        return [configured]
    for name in names:
        if (found := shutil.which(name)):
            return [found]
    raise DrawError(f"{names[0]} is not installed (or set {env})")


def _export(cli: list[str], workspace: Path, work: Path) -> None:
    """The workspace as JSON and as DOT, the two CLI runs side by side (each
    starts a JVM, and the CLI exports one format per run)."""
    runs = {fmt: subprocess.Popen([*cli, "export", "-w", str(workspace), "-f", fmt, "-o", str(work / fmt)],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            for fmt in ("json", "dot")}
    for fmt, run in runs.items():
        out, err = run.communicate()
        if run.returncode != 0:
            lines = [line for line in (err + out).splitlines() if line.strip()]
            raise DrawError(f"{workspace}: Structurizr could not export {fmt}: {lines[-1] if lines else 'no output'}")


def draw(dhf_dir: Path) -> list[str]:
    """Export the workspace's model and draw every view; return the view keys."""
    dhf_dir = Path(dhf_dir)
    workspace = dhf_dir / WORKSPACE
    if not workspace.is_file():
        raise DrawError(f"no architecture workspace: {workspace}")
    cli = _tool("RDM_STRUCTURIZR", "structurizr.sh", "structurizr")
    dot = _tool("RDM_DOT", "dot")
    stamp = workspace_digest(dhf_dir)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        _export(cli, workspace, work)
        model = json.loads(next((work / "json").glob("*.json")).read_text(encoding="utf-8"))
        (model.get("properties") or {}).pop("structurizr.dsl", None)  # the workspace again, base64; the stamp says it
        model[STAMP_KEY] = {"workspace_sha256": stamp}
        keys = view_keys(model)
        drawn = {}
        for key in keys:
            source = work / "dot" / f"structurizr-{key}.dot"
            if not source.is_file():
                raise DrawError(f"{workspace}: Structurizr exported no DOT for view {key}")
            text = _BARE_AMPERSAND.sub("&amp;", source.read_text(encoding="utf-8"))
            done = subprocess.run([*dot, "-Tsvg"], input=text, capture_output=True, text=True)
            if done.returncode != 0:
                raise DrawError(f"{workspace}: Graphviz could not draw view {key}: {done.stderr.strip()}")
            head, _, rest = done.stdout.partition("\n")  # the stamp goes after the XML declaration
            drawn[key] = f"{head}\n{SVG_STAMP.format(view=key, digest=stamp)}\n{rest}"
    (dhf_dir / MODEL).write_text(json.dumps(model, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    views = dhf_dir / VIEWS
    views.mkdir(parents=True, exist_ok=True)
    for old in views.glob("*.svg"):
        if old.stem not in drawn:
            old.unlink()
    for key, svg in drawn.items():
        (views / f"{key}.svg").write_text(svg, encoding="utf-8")
    return keys


def draw_command(args) -> int:
    try:
        keys = draw(Path(args.dhf))
    except DrawError as error:
        print(f"Error: {error}")
        return 1
    print(f"Drew {len(keys)} view(s) of {Path(args.dhf) / WORKSPACE}: {', '.join(keys)}")
    return 0
