"""Mermaid diagrams as images (DI-70).

A ```` ```mermaid ```` block in a rendered document becomes an image reference,
drawn by Mermaid's own command-line renderer (``mmdc``, from
``@mermaid-js/mermaid-cli``) in a headless browser, so a PDF shows the diagram
GitHub and the docs site draw. An image is named by a hash of its diagram, so
an unchanged diagram is not drawn again. A diagram the renderer rejects, or a
missing renderer, fails the render with the document's name: shipping the
source as a code block would pass silently.

Settings, from the environment:

``RDM_MERMAID_CLI``        the renderer command (default ``mmdc``)
``RDM_MERMAID_DIR``        where images are written, relative to the working
                           directory (default ``tmp/mermaid``)
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


def _command() -> list[str]:
    command = shlex.split(os.environ.get("RDM_MERMAID_CLI", "mmdc"))
    if not shutil.which(command[0]):
        raise MermaidError(
            f"the Mermaid renderer `{command[0]}` is not installed: use the RDM image, or "
            "`npm install -g @mermaid-js/mermaid-cli` (or set RDM_MERMAID_CLI)")
    return command


def draw(diagram: str, document: str) -> Path:
    """The image of one diagram, drawn now or found from an earlier render."""
    out_dir = Path(os.environ.get("RDM_MERMAID_DIR", "tmp/mermaid"))
    image = out_dir / f"{hashlib.sha256(diagram.encode()).hexdigest()[:16]}.{FORMAT}"
    if image.is_file():
        return image
    command = _command()
    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        source, config = Path(work) / "diagram.mmd", Path(work) / "config.json"
        source.write_text(diagram, encoding="utf-8")
        config.write_text(json.dumps(CONFIG), encoding="utf-8")
        args = [*command, "--quiet", "-i", str(source), "-o", str(Path(work) / f"diagram.{FORMAT}"),
                "-c", str(config), "-b", "white"]
        if os.environ.get("RDM_MERMAID_PUPPETEER"):
            args += ["-p", os.environ["RDM_MERMAID_PUPPETEER"]]
        done = subprocess.run(args, capture_output=True, text=True)
        drawn = Path(work) / f"diagram.{FORMAT}"
        if done.returncode != 0 or not drawn.is_file():
            first = diagram.strip().splitlines()[0] if diagram.strip() else "(empty)"
            reason = (done.stderr or done.stdout).strip().splitlines()
            raise MermaidError(f"{document}: Mermaid diagram `{first}` could not be drawn: "
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
