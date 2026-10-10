"""Acceptance tests for the publishing context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest or pypdf is not installed, or rdm-typst is not built.
"""

from __future__ import annotations

import io
import shutil
from importlib.resources import files
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
@allure.label("component", "Document typesetter")
def test_a_document_is_typeset_to_pdf_with_one_command(tmp_path: Path, monkeypatch, capsys) -> None:
    """DI-89: a rendered document's Markdown goes to Typst with RDM's converter,
    its frontmatter into the project's template, the PDF through the typeset
    interface, the same on both sides; what cannot be carried is refused by name."""
    from rdm.main import cli
    from rdm.publishing.typeset import TypesetError, markdown_to_typst, typeset_document

    monkeypatch.setenv("RDM_TYPST", rdm_typst())
    template = tmp_path / "template.typ"
    template.write_bytes((files("rdm.specification") / "init_files" / "template.typ").read_bytes())
    (tmp_path / "img").mkdir()
    (tmp_path / "img" / "dot.png").write_bytes(_png())
    document = tmp_path / "doc.md"
    document.write_text("""---
id: SOP-7
revision: 3
title: Software *Plan*
status: Approved
---

# Purpose

Plain text with _emphasis_, **strong**, `code`, a [link](https://example.org) and a footnote[^n].
Special characters stay text: # * _ $ @ < > [ ] ~ // \\ and more.

= not a heading, and / not a term, when a paragraph starts so.

## Lists

- one
  - nested a
    1. deep first
    2. deep second
  - nested b
- two

## Table

| Clause | Document | Status |
|-------:|:--------:|--------|
| 5.1 | SOP-7 | done |
| 5.2 | SOP-8 | open |

```python
print("fenced ``` inside")
```

> A quoted paragraph.

![A dot](img/dot.png)

---

[^n]: The note's text.
""")

    with verification_step("the Markdown becomes Typst markup carrying every construct, every character as text"):
        typst, images = markdown_to_typst(document.read_text().split("---", 2)[2])
        attach("typst", typst)
        assert images == ["img/dot.png"]
        for piece in ("= Purpose", "_emphasis_", "*strong*", "`code`", '#link("https://example.org")[link]',
                      "#footnote[The note's text.]", r"\# \* \_ \$ \@ \< \> \[ \] \~", "- one", "  - nested a",
                      "    + deep first", "#table(columns: 3, align: (right, center, left,),",
                      "table.header([Clause], [Document], [Status]),", "````python", "#quote(block: true)[",
                      '#image("img/dot.png", alt: "A dot")', "#line(length: 100%"):
            assert piece in typst, piece
        assert r"\= not a heading, and / not a term" in typst and "/\u200b/" in typst

    with verification_step("the frontmatter fills the template and the PDF is written by the command"):
        monkeypatch.chdir(tmp_path)
        assert cli(["typeset", "doc.md", "-o", "out/doc.pdf"]) == 0
        pdf = (tmp_path / "out" / "doc.pdf").read_bytes()
        pages = pypdf.PdfReader(io.BytesIO(pdf)).pages
        text = "\n".join(p.extract_text() for p in pages).replace("\u200b", "")  # the template's break points
        attach("pdf text", text)
        assert "SOP-7" in text and "Approved" in text and "Software" in text and "Plan" in text
        assert "deep second" in text and "SOP-8" in text and "The note’s text." in text  # Typst's quote
        assert "stay text:" in text and all(ch in text for ch in "#*_$@<>[]~") and "= not a heading" in text

    with verification_step("made again, the same bytes: the PDF depends on the document and the template only"):
        assert typeset_document(document, template) == pdf

    with verification_step("a document rendered apart from its images finds them on the resource path, in order"):
        elsewhere = tmp_path / "release" / "doc.md"  # as the project Makefile renders into release/
        elsewhere.parent.mkdir()
        elsewhere.write_text(document.read_text())
        assert cli(["typeset", "release/doc.md", "--resource-path", "nowhere:.", "-o", "out/release.pdf"]) == 0
        assert (tmp_path / "out" / "release.pdf").read_bytes() == pdf

    with verification_step("a construct the converter does not carry, a missing image, a bad template: "
                           "refused by name"):
        html = tmp_path / "html.md"
        html.write_text("---\nid: X\n---\n\n<div>raw</div>\n")
        with pytest.raises(TypesetError, match="HTML is not carried: <div>raw</div>"):
            typeset_document(html, template)
        gone = tmp_path / "gone.md"
        gone.write_text("---\nid: X\n---\n\n![x](img/none.png)\n")
        with pytest.raises(TypesetError, match="image not found: img/none.png"):
            typeset_document(gone, template)
        with pytest.raises(TypesetError, match="image not found: img/dot.png"):
            typeset_document(elsewhere, template)
        template.write_text("#let template(..args, body) = { nonsense }\n")
        with pytest.raises(ReportUnavailable, match="unknown variable: nonsense"):
            typeset_document(document, template)
        assert cli(["typeset", "doc.md", "-o", "out/none.pdf"]) == 2 and not (tmp_path / "out" / "none.pdf").exists()
        assert "cannot typeset doc.md" in capsys.readouterr().out
