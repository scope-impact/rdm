import json
from pathlib import Path

import pytest

from rdm.binder import build_binder


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
