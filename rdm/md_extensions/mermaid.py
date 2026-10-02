"""Mermaid diagrams as images (DI-70).

A ```` ```mermaid ```` block in a rendered document becomes an image reference,
drawn by Mermaid's own renderer (``mmdc``, from ``@mermaid-js/mermaid-cli``), so
a PDF shows the diagram GitHub and the docs site draw. An image is named by a
hash of its diagram, so an unchanged diagram is not drawn again, and a renderer
run elsewhere (Mermaid's official image, beside the RDM image) draws the same
files the render reads:

1. render with ``RDM_MERMAID_COLLECT=1``: each undrawn diagram is written to
   ``<hash>.mmd``, with the renderer config ``config.json``;
2. Mermaid's image draws each ``<hash>.mmd`` to ``<hash>.svg``
   (``mmdc -c config.json -b white -i <hash>.mmd -o <hash>.svg``);
3. render again: each block becomes its image.

Where ``mmdc`` is installed, the render draws a diagram itself. A diagram not
drawn, or one the renderer rejects, fails the render with the document's name:
shipping the source as a code block would pass silently.

Settings, from the environment:

``RDM_MERMAID_DIR``        where diagrams and images live, relative to the
                           working directory (default ``tmp/mermaid``)
``RDM_MERMAID_COLLECT``    ``1``: write undrawn diagrams instead of drawing them
``RDM_MERMAID_CLI``        the renderer command (default ``mmdc``)
``RDM_MERMAID_PUPPETEER``  a puppeteer config file for the renderer, e.g. one
                           passing ``--no-sandbox`` in a container
"""

from __future__ import annotations

import hashlib
import json
import os
import shlex
import shutil
import subprocess
import tempfile
from pathlib import Path

from rdm.md_extensions.base import RdmExtension

FORMAT = "svg"
# Strict: the diagram is data from the record, and nothing in it runs. SVG
# text, not HTML labels: Typst draws SVG text, not embedded HTML.
CONFIG = {"securityLevel": "strict", "htmlLabels": False, "flowchart": {"htmlLabels": False}}


class MermaidError(RuntimeError):
    """A diagram could not be drawn."""


def _command() -> list[str] | None:
    command = shlex.split(os.environ.get("RDM_MERMAID_CLI", "mmdc"))
    return command if shutil.which(command[0]) else None


def _first_line(diagram: str) -> str:
    return diagram.strip().splitlines()[0] if diagram.strip() else "(empty)"


def _write_source(out_dir: Path, key: str, diagram: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "config.json").write_text(json.dumps(CONFIG), encoding="utf-8")
    (out_dir / f"{key}.mmd").write_text(diagram, encoding="utf-8")


def draw(diagram: str, document: str) -> Path:
    """The image of one diagram: found from an earlier draw, drawn now, or (in
    collect mode) left for Mermaid's image to draw."""
    out_dir = Path(os.environ.get("RDM_MERMAID_DIR", "tmp/mermaid"))
    key = hashlib.sha256((json.dumps(CONFIG, sort_keys=True) + diagram).encode()).hexdigest()[:16]
    image = out_dir / f"{key}.{FORMAT}"
    if image.is_file():
        return image
    if os.environ.get("RDM_MERMAID_COLLECT") == "1":
        _write_source(out_dir, key, diagram)
        return image
    command = _command()
    if command is None:
        _write_source(out_dir, key, diagram)
        raise MermaidError(
            f"{document}: Mermaid diagram `{_first_line(diagram)}` is not drawn: draw {out_dir}/*.mmd with "
            "Mermaid's image (RDM's PDF action does) or install mmdc (npm install -g "
            "@mermaid-js/mermaid-cli), then render again")
    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        source, config = Path(work) / "diagram.mmd", Path(work) / "config.json"
        source.write_text(diagram, encoding="utf-8")
        config.write_text(json.dumps(CONFIG), encoding="utf-8")
        drawn = Path(work) / f"diagram.{FORMAT}"
        args = [*command, "--quiet", "-i", str(source), "-o", str(drawn), "-c", str(config), "-b", "white"]
        if os.environ.get("RDM_MERMAID_PUPPETEER"):
            args += ["-p", os.environ["RDM_MERMAID_PUPPETEER"]]
        done = subprocess.run(args, capture_output=True, text=True)
        if done.returncode != 0 or not drawn.is_file():
            reason = (done.stderr or done.stdout).strip().splitlines()
            raise MermaidError(f"{document}: Mermaid diagram `{_first_line(diagram)}` could not be drawn: "
                               f"{reason[0] if reason else 'no output'}")
        shutil.move(str(drawn), image)
    return image


def replace_diagrams(lines, document: str):
    """Lines with each ```mermaid block replaced by its image."""
    block: list[str] | None = None
    for line in lines:
        if block is None:
            if line.strip() == "```mermaid":
                block = []
            else:
                yield line
        elif line.strip() == "```":
            yield f"![]({draw(''.join(block), document).as_posix()})\n"
            block = None
        else:
            block.append(line)
    if block is not None:
        raise MermaidError(f"{document}: a Mermaid block is not closed")


class MermaidExtension(RdmExtension):
    def post_process_filter(self, generator):
        return replace_diagrams(generator, getattr(self.environment, "rdm_document", "a document"))
