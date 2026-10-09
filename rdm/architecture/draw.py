"""``rdm c4 draw``: the architecture workspace's model and views, drawn and
stamped (DI-70), through one c4 interface (DI-84).

The architecture is one Structurizr workspace, ``<dhf>/c4/workspace.dsl``
(DI-66). Drawing hands the workspace's text, every file it includes inlined,
to the c4 port and receives the exported model, written as
``<dhf>/c4/workspace.json`` (what RDM reads), and each view's SVG, written to
``<dhf>/c4/views/<view>.svg`` (what the design documents show, so GitHub, the
docs site and a PDF show one picture, and no browser draws it). Every drawn
file is stamped with the SHA-256 of the workspace; reading the model and
checking the stamps is the record's (rdm/architecture/model.py), and needs no
provider.

Providers: ``rdm-c4`` (``RDM_C4``, or on the PATH), structurizrx compiled as
RDM's c4 provider, the same code the component carries; and, for the views
it cannot draw yet (dynamic views), Structurizr's command line with Java and
Graphviz (``RDM_STRUCTURIZR`` / ``RDM_DOT``, or ``structurizr.sh``,
``structurizr`` and ``dot`` on the PATH), which also draws everything when
``rdm-c4`` is absent. A view no provider draws is refused, by name, and
nothing is written.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Protocol

from rdm.architecture.model import MODEL, STAMP_KEY, SVG_STAMP, VIEWS, WORKSPACE, inlined, view_keys, workspace_digest

# Structurizr's DOT export leaves a bare & in its HTML labels (e.g. "V&V"), which
# Graphviz rejects as malformed; escape it until the exporter does.
_BARE_AMPERSAND = re.compile(r"&(?!(?:[a-zA-Z]+|#\d+|#x[0-9a-fA-F]+);)")

class DrawError(RuntimeError):
    """The workspace could not be exported or drawn."""


@dataclass
class Drawing:
    """What a provider returns: the exported model and the SVG of each view it drew."""

    model: dict
    views: dict[str, str] = field(default_factory=dict)


class C4(Protocol):
    """The c4 port: the workspace's DSL text, every include inlined, exported and drawn."""

    name: str

    def draw(self, dsl: str) -> Drawing: ...


class RdmC4:
    """structurizrx as RDM's c4 provider, the native program ``rdm-c4``."""

    name = "rdm-c4"

    def __init__(self, program: str) -> None:
        self.program = program

    def draw(self, dsl: str) -> Drawing:
        done = subprocess.run([self.program], input=dsl, capture_output=True, text=True)
        if done.returncode != 0:
            raise DrawError(f"{self.name} could not export the workspace: {done.stderr.strip() or 'no output'}")
        drawn = json.loads(done.stdout)
        return Drawing(model=drawn["workspace"], views=dict(drawn["views"]))


class StructurizrCli:
    """Structurizr's command line (Java) exporting JSON and DOT, Graphviz drawing the DOT."""

    name = "Structurizr"

    def __init__(self, cli: list[str], dot: list[str]) -> None:
        self.cli, self.dot = cli, dot

    def draw(self, dsl: str, only: list[str] | None = None) -> Drawing:
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            workspace = work / "workspace.dsl"
            workspace.write_text(dsl, encoding="utf-8")
            self._export(workspace, work)
            model = json.loads(next((work / "json").glob("*.json")).read_text(encoding="utf-8"))
            views = {}
            for key in view_keys(model) if only is None else only:
                source = work / "dot" / f"structurizr-{key}.dot"
                if not source.is_file():
                    raise DrawError(f"{self.name} exported no DOT for view {key}")
                text = _BARE_AMPERSAND.sub("&amp;", source.read_text(encoding="utf-8"))
                done = subprocess.run([*self.dot, "-Tsvg"], input=text, capture_output=True, text=True)
                if done.returncode != 0:
                    raise DrawError(f"Graphviz could not draw view {key}: {done.stderr.strip()}")
                views[key] = done.stdout
        return Drawing(model=model, views=views)

    def _export(self, workspace: Path, work: Path) -> None:
        """The workspace as JSON and as DOT, the two CLI runs side by side (each
        starts a JVM, and the CLI exports one format per run)."""
        runs = {fmt: subprocess.Popen([*self.cli, "export", "-w", str(workspace), "-f", fmt, "-o", str(work / fmt)],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                for fmt in ("json", "dot")}
        for fmt, run in runs.items():
            out, err = run.communicate()
            if run.returncode != 0:
                lines = [line for line in (err + out).splitlines() if line.strip()]
                raise DrawError(f"{self.name} could not export {fmt}: {lines[-1] if lines else 'no output'}")


def _tool(env: str, *names: str) -> list[str] | None:
    configured = os.environ.get(env)
    if configured:
        return [configured]
    for name in names:
        if (found := shutil.which(name)):
            return [found]
    return None


def providers() -> tuple[C4 | None, StructurizrCli | None]:
    """The providers found: rdm-c4, and Structurizr's command line with Graphviz."""
    program = _tool("RDM_C4", "rdm-c4")
    cli, dot = _tool("RDM_STRUCTURIZR", "structurizr.sh", "structurizr"), _tool("RDM_DOT", "dot")
    return (RdmC4(program[0]) if program else None), (StructurizrCli(cli, dot) if cli and dot else None)


_providers: Callable[[], tuple[C4 | None, StructurizrCli | None]] = providers


def use(find: Callable[[], tuple[C4 | None, StructurizrCli | None]]) -> None:
    """Choose how the providers are found, once, when RDM starts (the component)."""
    global _providers
    _providers = find


def workspace_text(dhf_dir: Path) -> str:
    """The workspace's DSL with every local file it includes inlined: what the port is given."""
    return inlined(Path(dhf_dir) / WORKSPACE)


def draw(dhf_dir: Path) -> list[str]:
    """Export the workspace's model and draw every view; return the view keys."""
    dhf_dir = Path(dhf_dir)
    workspace = dhf_dir / WORKSPACE
    if not workspace.is_file():
        raise DrawError(f"no architecture workspace: {workspace}")
    first, legacy = _providers()
    if first is None and legacy is None:
        raise DrawError("rdm-c4 is not installed (or set RDM_C4), nor Structurizr's command line with Graphviz "
                        "(RDM_STRUCTURIZR, RDM_DOT)")
    stamp = workspace_digest(dhf_dir)
    dsl = workspace_text(dhf_dir)
    try:
        drawing = (first or legacy).draw(dsl)
        keys = view_keys(drawing.model)
        missing = [key for key in keys if key not in drawing.views]
        if missing and first is not None and legacy is not None:
            drawing.views.update(legacy.draw(dsl, only=missing).views)
            missing = [key for key in keys if key not in drawing.views]
        if missing:
            raise DrawError(f"{(first or legacy).name} cannot draw view(s) {', '.join(missing)}")
    except DrawError as error:
        raise DrawError(f"{workspace}: {error}") from None
    model = drawing.model
    (model.get("properties") or {}).pop("structurizr.dsl", None)  # the workspace again, base64; the stamp says it
    model[STAMP_KEY] = {"workspace_sha256": stamp}
    (dhf_dir / MODEL).write_text(json.dumps(model, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    views = dhf_dir / VIEWS
    views.mkdir(parents=True, exist_ok=True)
    for old in views.glob("*.svg"):
        if old.stem not in drawing.views:
            old.unlink()
    for key, svg in drawing.views.items():
        head, _, rest = svg.partition("\n")  # the stamp goes after the XML declaration
        (views / f"{key}.svg").write_text(f"{head}\n{SVG_STAMP.format(view=key, digest=stamp)}\n{rest}",
                                          encoding="utf-8")
    return keys


def draw_command(args) -> int:
    try:
        keys = draw(Path(args.dhf))
    except DrawError as error:
        print(f"Error: {error}")
        return 1
    print(f"Drew {len(keys)} view(s) of {Path(args.dhf) / WORKSPACE}: {', '.join(keys)}")
    return 0
