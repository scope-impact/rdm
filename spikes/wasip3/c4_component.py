"""
Spike: ``rdm c4 draw`` through the ``rdm:component/c4`` import (rdm-c4,
structurizrx), in place of Structurizr's CLI and Graphviz, which the
component cannot start.

RDM's own ``draw()`` still does everything else: it stamps the model and each
view with the workspace digest, writes them, and removes images of views the
workspace no longer has. Only its two programs are stood in for: the export
(``_export``) asks the import, and Graphviz (``dot -Tsvg``) passes through the
SVG the import already drew.
"""

import subprocess
from pathlib import Path
from types import SimpleNamespace

from wit_world.imports import c4
from componentize_py_types import Err

import rdm.architecture.draw as draw


def _export(_cli, workspace: Path, work: Path) -> None:
    try:
        drawing = c4.draw(str(Path(workspace).resolve()))
    except Err as error:
        raise draw.DrawError(f"{workspace}: rdm-c4 could not draw the workspace: {error.value}") from None
    (work / "json").mkdir()
    (work / "json" / "workspace.json").write_text(drawing.workspace_json, encoding="utf-8")
    (work / "dot").mkdir()
    for view in drawing.views:  # already SVG: the stand-in for dot passes it through
        (work / "dot" / f"structurizr-{view.key}.dot").write_text(view.svg, encoding="utf-8")


def _passthrough(command, input=None, **_):
    return subprocess.CompletedProcess(command, 0, stdout=input, stderr="")


def install() -> None:
    draw._tool = lambda _env, *names: [names[0]]  # no program is looked for on a PATH
    draw._export = _export
    draw.subprocess = SimpleNamespace(run=_passthrough)
