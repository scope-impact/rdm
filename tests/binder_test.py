import json
from pathlib import Path

import pytest

from rdm.publishing.binder import build_binder


pytest.importorskip("pypdf")
pytest.importorskip("reportlab")


def _write_pdf(path: Path, text: str) -> None:
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    path.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(path), pagesize=A4)
    c.drawString(72, 720, text)
    c.showPage()
    c.save()


def _outline_titles(outline) -> list[str]:
    titles: list[str] = []
    for item in outline:
        if isinstance(item, list):
            titles.extend(_outline_titles(item))
        else:
            titles.append(item.title)
    return titles


def test_builds_bookmarked_binder_from_manifest(tmp_path: Path) -> None:
    from pypdf import PdfReader

    _write_pdf(tmp_path / "release" / "a.pdf", "A")
    _write_pdf(tmp_path / "release" / "b.pdf", "B")
    manifest = tmp_path / "binder.json"
    manifest.write_text(json.dumps({
        "title": "Example DHF Binder",
        "subtitle": "Release packet",
        "output": "release/binder.pdf",
        "sections": [{
            "title": "01 Design Records",
            "files": [
                {"title": "Document A", "path": "release/a.pdf"},
                {"title": "Document B", "path": "release/b.pdf"},
            ],
        }],
    }))

    output, pages = build_binder(manifest)

    reader = PdfReader(str(output))
    assert output == tmp_path / "release" / "binder.pdf"
    assert pages == 4
    assert len(reader.pages) == 4
    assert _outline_titles(reader.outline) == [
        "Example DHF Binder",
        "01 Design Records",
        "Document A",
        "Document B",
    ]


def test_missing_manifest_input_fails(tmp_path: Path) -> None:
    manifest = tmp_path / "binder.json"
    manifest.write_text(json.dumps({
        "title": "Example DHF Binder",
        "output": "release/binder.pdf",
        "sections": [{
            "title": "01 Design Records",
            "files": [{"title": "Missing", "path": "release/missing.pdf"}],
        }],
    }))

    with pytest.raises(FileNotFoundError, match="release/missing.pdf"):
        build_binder(manifest)


def test_style_sets_the_binders_own_pages(tmp_path):
    """A manifest's style: colours the cover and dividers; unknown fonts fall back."""
    from pypdf import PdfReader as Reader

    doc = tmp_path / "a.pdf"
    _write_pdf(doc, "A")
    manifest = tmp_path / "binder.json"
    manifest.write_text(json.dumps({
        "title": "A binder whose title is long enough to need a smaller size on the cover page",
        "output": "out.pdf",
        "style": {"background": "#55111F", "accent": "#F7F5F3", "text": "#FFFFFF",
                  "title_font": str(tmp_path / "missing.ttf"), "label": "COMPLIANCE RECORD"},
        "sections": [{"title": "One", "divider": True, "files": [{"title": "A", "path": "a.pdf"}]}],
    }))
    out, pages = build_binder(manifest)
    assert pages == 3
    cover = Reader(str(out)).pages[0].extract_text()
    assert "COMPLIANCE RECORD" in cover and "RELEASE BINDER" not in cover


def test_a_section_of_one_document_has_no_divider(tmp_path: Path) -> None:
    from pypdf import PdfReader

    _write_pdf(tmp_path / "release" / "history.pdf", "History")
    _write_pdf(tmp_path / "release" / "a.pdf", "A")
    _write_pdf(tmp_path / "release" / "b.pdf", "B")
    manifest = tmp_path / "binder.json"
    manifest.write_text(json.dumps({
        "title": "Example DHF Binder",
        "output": "release/binder.pdf",
        "sections": [
            {"title": "Revision history",
             "files": [{"title": "Revision history", "path": "release/history.pdf"}]},
            {"title": "Records", "files": [
                {"title": "Document A", "path": "release/a.pdf"},
                {"title": "Document B", "path": "release/b.pdf"},
            ]},
            {"title": "Forced", "divider": True,
             "files": [{"title": "Document A again", "path": "release/a.pdf"}]},
        ],
    }))

    output, pages = build_binder(manifest)

    # cover, history, divider + A + B, divider + A
    assert pages == 7
    assert "History" in PdfReader(str(output)).pages[1].extract_text()
    assert _outline_titles(PdfReader(str(output)).outline) == [
        "Example DHF Binder",
        "Revision history",
        "Records", "Document A", "Document B",
        "Forced", "Document A again",
    ]


def test_page_numbers_stamp_every_page_but_the_cover(tmp_path: Path) -> None:
    from pypdf import PdfReader

    _write_pdf(tmp_path / "release" / "a.pdf", "A")
    _write_pdf(tmp_path / "release" / "b.pdf", "B")
    manifest = tmp_path / "binder.json"
    manifest.write_text(json.dumps({
        "title": "Example Binder",
        "output": "release/binder.pdf",
        "style": {"page_numbers": True},
        "sections": [{"title": "Records", "files": [
            {"title": "Document A", "path": "release/a.pdf"},
            {"title": "Document B", "path": "release/b.pdf"},
        ]}],
    }))

    output, pages = build_binder(manifest)

    texts = [p.extract_text() for p in PdfReader(str(output)).pages]
    assert "page 1 of" not in texts[0]
    assert "Example Binder · page 3 of 4" in texts[2]
    assert "A" in texts[2]
