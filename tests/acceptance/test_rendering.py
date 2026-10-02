"""Acceptance tests for the rendering context's design inputs (see dhf/).

Each test ("live BDD") verifies a rendering design input,
tagged with `@allure.story` and its DI id. They exercise the real render engine / filters /
markdown extensions, so a passing tag is evidence the requirement is met.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

from rdm.md_extensions.mermaid import MermaidError
from rdm.render import invert_dependencies, join_to, md_indent
from tests.util import render_from_string

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]


@allure.story("DI-7")
@allure.label("output", "rdm/render.py")
def test_renders_template_against_data_context() -> None:
    """DI-7: a Markdown template renders against a supplied data context."""
    out = render_from_string(
        "Device: {{ device.name }} v{{ device.version }}",
        context={"device": {"name": "Acme Monitor", "version": "1.2"}},
    )
    assert "Device: Acme Monitor v1.2" in out


@allure.story("DI-8")
@allure.label("output", "rdm/render.py")
def test_traceability_filters() -> None:
    """DI-8: the invert_dependencies / join_to / md_indent filters behave."""
    with verification_step("invert_dependencies: edge A→B inverts to B being depended-on by {A}"):
        assert invert_dependencies([{"id": "A", "deps": ["B"]}], "id", "deps") == [("B", {"A"})]
    with verification_step("join_to: resolve foreign keys against a table by primary key"):
        table = [{"id": "r1", "v": 1}, {"id": "r2", "v": 2}]
        assert join_to(["r2"], table) == [{"id": "r2", "v": 2}]
    with verification_step("md_indent: shift headings deeper by header_shift"):
        assert md_indent("# Title", header_shift=1) == "## Title"


@allure.story("DI-9")
@allure.label("output", "rdm/md_extensions/")
def test_markdown_post_processing() -> None:
    """DI-9: section numbering, vocabulary expansion, and auditor-note exclusion."""
    numbered = render_from_string(
        "## hello", config={"md_extensions": ["rdm.md_extensions.SectionNumberExtension"]}
    )
    assert numbered == "## 1.1 hello\n"

    excluded = render_from_string(
        "Spec [[1234:9.8.7.6]].",
        config={"md_extensions": ["rdm.md_extensions.AuditNoteExclusionExtension"]},
    )
    assert excluded == "Spec.\n"

    vocab = render_from_string(
        "apple\nbanana\n{% for v in first_pass_output.words | sort %}[{{ v }}]{% endfor %}",
        config={"md_extensions": ["rdm.md_extensions.VocabularyExtension"]},
    )
    assert "[apple][banana]" in vocab


FAKE_MMDC = """\
import json, sys
args = sys.argv[1:]
src, out, config = (args[args.index(flag) + 1] for flag in ("-i", "-o", "-c"))
with open(sys.argv[0] + ".log", "a") as log:
    log.write(json.dumps({"args": args, "config": json.load(open(config))}) + "\\n")
diagram = open(src).read()
if "BROKEN" in diagram:  # a renderer can fail after writing part of an image
    open(out, "w").write("<svg")
    sys.exit("Parse error on line 2: BROKEN")
open(out, "w").write("<svg xmlns='http://www.w3.org/2000/svg'><text>" + diagram.split()[0] + "</text></svg>")
"""

DIAGRAM = "C4Context\n  Person(author, \"Author\")\n"
DOCUMENT = "# Architecture\n\n```mermaid\n" + DIAGRAM + "```\n\nAfter the diagram.\n"
CONFIG = {"md_extensions": ["rdm.md_extensions.MermaidExtension"]}


def _fake_mmdc(tmp_path, monkeypatch) -> Path:
    fake = tmp_path / "mmdc.py"
    fake.write_text(FAKE_MMDC)
    monkeypatch.setenv("RDM_MERMAID_CLI", f"{sys.executable} {fake}")
    return fake


def _calls(fake: Path) -> list[dict]:
    log = Path(str(fake) + ".log")
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


@allure.story("DI-70")
@allure.label("output", "rdm/md_extensions/mermaid.py")
@allure.label("output", "rdm/init_files/render-pdfs.sh")
def test_mermaid_diagrams_render_as_images_drawn_by_mermaid(tmp_path, monkeypatch) -> None:
    """DI-70: each Mermaid diagram becomes an image drawn by Mermaid's own
    renderer at a pinned version, and a diagram that cannot be drawn fails the
    render, naming the document."""
    monkeypatch.setenv("RDM_MERMAID_DIR", str(tmp_path / "drawn"))
    monkeypatch.delenv("RDM_MERMAID_COLLECT", raising=False)
    fake = _fake_mmdc(tmp_path, monkeypatch)

    with verification_step("a Mermaid block becomes an image, drawn by the renderer with Mermaid's strict config"):
        out = render_from_string(DOCUMENT, config=CONFIG)
        attach("rendered", out)
        images = re.findall(r"!\[\]\((.+?\.svg)\)", out)
        assert len(images) == 1 and "```mermaid" not in out and "After the diagram." in out
        assert Path(images[0]).read_text().startswith("<svg") and "C4Context" in Path(images[0]).read_text()
        call = _calls(fake)[0]
        attach("renderer call", call)
        assert call["config"]["securityLevel"] == "strict" and call["args"][-2:] == ["-b", "white"]
    with verification_step("an unchanged diagram is not drawn again"):
        assert render_from_string(DOCUMENT, config=CONFIG) == out
        assert len(_calls(fake)) == 1

    shared = tmp_path / "shared"
    monkeypatch.setenv("RDM_MERMAID_DIR", str(shared))
    with verification_step("collect mode leaves the diagram and the config for Mermaid's image, drawing nothing"):
        monkeypatch.setenv("RDM_MERMAID_COLLECT", "1")
        render_from_string(DOCUMENT, config=CONFIG)
        sources = sorted(shared.glob("*.mmd"))
        assert len(sources) == 1 and sources[0].read_text() == DIAGRAM
        assert json.loads((shared / "config.json").read_text())["securityLevel"] == "strict"
        assert len(_calls(fake)) == 1
    with verification_step("drawn beside the render, as render-pdfs.sh does, the image is what the render uses"):
        source = sources[0]
        subprocess.run([sys.executable, str(fake), "-c", "config.json", "-b", "white", "-i", source.name,
                        "-o", source.with_suffix(".svg").name], cwd=shared, check=True)
        monkeypatch.delenv("RDM_MERMAID_COLLECT")
        monkeypatch.setenv("RDM_MERMAID_CLI", str(tmp_path / "no-such-renderer"))
        out = render_from_string(DOCUMENT, config=CONFIG)
        assert f"![]({source.with_suffix('.svg').as_posix()})" in out

    with verification_step("a diagram not drawn, with no renderer, fails the render naming the document"):
        monkeypatch.setenv("RDM_MERMAID_DIR", str(tmp_path / "empty"))
        with pytest.raises(MermaidError) as missing:
            render_from_string(DOCUMENT, config=CONFIG, template_name="architecture.md")
        attach("error, not drawn", str(missing.value))
        assert str(missing.value).startswith("architecture.md: Mermaid diagram `C4Context` is not drawn")
    with verification_step("a diagram the renderer rejects fails the render naming the document"):
        _fake_mmdc(tmp_path, monkeypatch)
        broken = DOCUMENT.replace(DIAGRAM, "C4Context\n  BROKEN\n")
        with pytest.raises(MermaidError) as rejected:
            render_from_string(broken, config=CONFIG, template_name="design/graph.md")
        attach("error, rejected", str(rejected.value))
        assert "design/graph.md: Mermaid diagram `C4Context` could not be drawn: Parse error" in str(rejected.value)

    with verification_step("Mermaid's image is pinned by version and digest and draws with the render's config"):
        script = (ROOT / "rdm/init_files/render-pdfs.sh").read_text()
        attach("render-pdfs.sh", script)
        assert re.search(r"mermaid-cli/mermaid-cli:\d+\.\d+\.\d+@sha256:[0-9a-f]{64}", script)
        assert "-e RDM_MERMAID_COLLECT=1" in script and "-c config.json -b white" in script
        assert "render-pdfs.sh" in (ROOT / "action.yml").read_text()
        assert "rdm.md_extensions.MermaidExtension" in (ROOT / "rdm/init_files/config.yml").read_text()
