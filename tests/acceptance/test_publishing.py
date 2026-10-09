"""Acceptance tests for the publishing context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest or pypdf is not installed, or rdm-typst is not built.
"""

from __future__ import annotations

import io
import shutil
import struct
import zlib
from pathlib import Path

import pytest

from rdm.publishing import report as report_module
from rdm.publishing.report import LAYOUT, RdmTypst, ReportUnavailable, render_pdf

allure = pytest.importorskip("allure")
pypdf = pytest.importorskip("pypdf")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]


def rdm_typst() -> str:
    """The typeset provider, or a skip: CI builds it before the tests."""
    found = shutil.which("rdm-typst") or str(ROOT / "providers" / "typst" / "target" / "release" / "rdm-typst")
    if not Path(found).is_file():
        pytest.skip("rdm-typst is not built: cargo build --release --manifest-path providers/typst/Cargo.toml")
    return found


def _png() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = zlib.compress(b"\x00\xff\x00\x00")
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", raw) + chunk(b"IEND", b"")


LAYOUT_TEXT = """#let d = json("data.json")
#set text(font: "Nunito Sans", size: 10pt)
= #d.title
#text(font: "JetBrains Mono")[mono #d.id]
#image("attachments/dot.png", width: 1cm)
"""


@allure.story("DI-88")
@allure.label("component", "Verification report")
def test_the_report_is_typeset_through_one_interface(tmp_path: Path, monkeypatch) -> None:
    """DI-88: the layout, its data and attachments go to one typeset interface and
    a PDF comes back; the provider is the same Typst library on both sides with
    the layout's fonts embedded, so the report is the same wherever it is made;
    with no provider the report says so."""
    provider = RdmTypst(rdm_typst())
    files = {"main.typ": LAYOUT_TEXT.encode(), "data.json": b'{"title": "Report", "id": "DI-88"}',
             "attachments/dot.png": _png()}

    with verification_step("the layout, its data and its attachments in; a PDF out"):
        pdf = provider.typeset("main.typ", files)
        assert pdf.startswith(b"%PDF")
        pages = pypdf.PdfReader(io.BytesIO(pdf)).pages
        text = "".join(p.extract_text() for p in pages)
        attach("text", text)
        assert "Report" in text and "mono DI-88" in text and len(pages) == 1

    with verification_step("the fonts the layout names are the provider's own, embedded, never the host's"):
        fonts = {str(f["/BaseFont"]) for p in pypdf.PdfReader(io.BytesIO(pdf)).pages
                 for f in p["/Resources"]["/Font"].values()}
        attach("fonts", sorted(fonts))
        assert any("NunitoSans" in f for f in fonts) and any("JetBrainsMono" in f for f in fonts)
        monkeypatch.setenv("TYPST_FONT_PATHS", str(tmp_path / "nowhere"))
        assert provider.typeset("main.typ", files) == pdf

    with verification_step("the same input gives the same bytes, made again"):
        assert provider.typeset("main.typ", dict(files)) == pdf

    with verification_step("a layout Typst rejects is refused with Typst's message, and nothing is written"):
        with pytest.raises(ReportUnavailable, match="could not compile the report.*unknown variable"):
            provider.typeset("main.typ", {"main.typ": b"#nonsense\n"})
        with pytest.raises(ReportUnavailable, match="no file"):
            provider.typeset("missing.typ", files)

    with verification_step("the report asks the interface only: another provider's PDF is the report's, "
                           "and with none the report says so"):
        class Stub:
            name = "stub"

            def typeset(self, main, given):
                assert main == LAYOUT and "report.json" in given and main in given
                return b"%PDF-stub"

        monkeypatch.setattr(report_module, "_typesetter", lambda: Stub())
        results = tmp_path / "results"
        results.mkdir()
        out = render_pdf({"design_inputs": []}, results, tmp_path / "stub.pdf")
        assert out.read_bytes() == b"%PDF-stub"
        monkeypatch.setattr(report_module, "_typesetter", lambda: None)
        with pytest.raises(ReportUnavailable, match="needs rdm-typst"):
            render_pdf({"design_inputs": []}, results, tmp_path / "none.pdf")


@allure.story("DI-89")
@allure.label("component", "TODO")
def test_di_89_not_implemented() -> None:
    """DI-89: RDM shall typeset a rendered document to PDF with one command, converting its
    Markdown — headings, paragraphs, emphasis, links, inline and fenced code, bullet and
    numbered lists at any depth, tables, images, block quotes, footnotes and rules — to
    Typst markup with RDM's own converter, setting the document's id, revision, title, date
    and status from its frontmatter into the project's Typst template, and compiling through
    the typeset interface, so that the PDF is the same on the command line and inside the
    component; a construct the converter does not carry, an image it cannot find or a
    template Typst rejects is refused, naming it, and nothing is written."""
    pytest.fail("DI-89 acceptance test not implemented -- replace this stub with real assertions")
