"""Build bookmarked release binder PDFs from a manifest."""

from __future__ import annotations

import json
from datetime import date
from io import BytesIO
from pathlib import Path
from typing import Any

import yaml


def _require_pdf_dependencies():
    try:
        from pypdf import PdfReader, PdfWriter
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.units import cm
        from reportlab.pdfgen import canvas
    except ImportError as exc:
        raise RuntimeError(
            "The binder command requires PDF dependencies. "
            "Install them with: pip install rdm[pdf]"
        ) from exc

    return PdfReader, PdfWriter, colors, A4, cm, canvas


def load_manifest(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() in {".yml", ".yaml"}:
        data = yaml.safe_load(text)
    else:
        data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError("Binder manifest must be a JSON/YAML object")
    return data


def _page(title: str, subtitle: str, kind: str):
    _, _, colors, A4, cm, canvas = _require_pdf_dependencies()

    deep_teal = colors.HexColor("#00414D")
    teal_mid = colors.HexColor("#22565E")
    orange = colors.HexColor("#ED8023")

    buffer = BytesIO()
    c = canvas.Canvas(buffer, pagesize=A4)
    width, height = A4

    c.setFillColor(deep_teal)
    c.rect(0, 0, width, height, fill=1, stroke=0)
    c.setFillColor(colors.white)

    if kind == "cover":
        c.setFillColor(orange)
        c.setFont("Helvetica-Bold", 11)
        c.drawCentredString(width / 2, height - 4.6 * cm, "RELEASE BINDER")
        c.setFillColor(colors.white)
        c.setFont("Times-Bold", 28)
        c.drawCentredString(width / 2, height - 6.2 * cm, title)
        if subtitle:
            c.setFont("Helvetica", 14)
            c.drawCentredString(width / 2, height - 7.4 * cm, subtitle)
        c.setFont("Helvetica", 10)
        c.drawCentredString(width / 2, 3.2 * cm, f"Generated {date.today().isoformat()}")
    else:
        c.setFillColor(orange)
        c.setFont("Helvetica-Bold", 10)
        c.drawString(2.6 * cm, height - 6.5 * cm, "BINDER SECTION")
        c.setFillColor(colors.white)
        c.setFont("Times-Bold", 25)
        c.drawString(2.6 * cm, height - 8.1 * cm, title)
        if subtitle:
            c.setFont("Helvetica", 12)
            c.drawString(2.6 * cm, height - 9.0 * cm, subtitle)
        c.setStrokeColor(teal_mid)
        c.setLineWidth(1)
        c.line(2.6 * cm, height - 9.8 * cm, width - 2.6 * cm, height - 9.8 * cm)

    c.showPage()
    c.save()
    buffer.seek(0)
    PdfReader, _, _, _, _, _ = _require_pdf_dependencies()
    return PdfReader(buffer)


def _append_reader(writer, reader) -> int:
    start = len(writer.pages)
    for page in reader.pages:
        writer.add_page(page)
    return start


def build_binder(
    manifest_path: Path,
    *,
    root: Path | None = None,
    output: Path | None = None,
    dividers: bool = True,
) -> tuple[Path, int]:
    """Build a PDF binder and return ``(output_path, page_count)``."""
    PdfReader, PdfWriter, _, _, _, _ = _require_pdf_dependencies()

    manifest_path = manifest_path.resolve()
    manifest = load_manifest(manifest_path)
    base = (root or manifest_path.parent).resolve()
    output_path = output or Path(manifest["output"])
    if not output_path.is_absolute():
        output_path = base / output_path
    output_path.parent.mkdir(parents=True, exist_ok=True)

    missing = _missing_inputs(manifest, base)
    if missing:
        raise FileNotFoundError("Missing binder inputs:\n" + "\n".join(missing))

    writer = PdfWriter()

    title = str(manifest.get("title") or "Release Binder")
    cover_start = _append_reader(
        writer,
        _page(title, str(manifest.get("subtitle") or ""), "cover"),
    )
    writer.add_outline_item(title, cover_start)

    for section in manifest.get("sections", []):
        section_title = str(section["title"])
        if dividers:
            section_start = _append_reader(
                writer,
                _page(section_title, str(section.get("subtitle") or ""), "divider"),
            )
        else:
            section_start = len(writer.pages)
        section_bookmark = writer.add_outline_item(section_title, section_start)

        for entry in section.get("files", []):
            input_path = Path(entry["path"])
            if not input_path.is_absolute():
                input_path = base / input_path
            document_start = _append_reader(writer, PdfReader(str(input_path)))
            writer.add_outline_item(str(entry["title"]), document_start, parent=section_bookmark)

    with output_path.open("wb") as stream:
        writer.write(stream)

    return output_path, len(writer.pages)


def _missing_inputs(manifest: dict[str, Any], base: Path) -> list[str]:
    missing: list[str] = []
    for section in manifest.get("sections", []):
        for entry in section.get("files", []):
            input_path = Path(entry["path"])
            if not input_path.is_absolute():
                input_path = base / input_path
            if not input_path.exists():
                missing.append(str(input_path))
    return missing


def binder_command(
    manifest: str = "binder.json",
    *,
    root: str | None = None,
    output: str | None = None,
    no_dividers: bool = False,
) -> int:
    try:
        output_path, pages = build_binder(
            Path(manifest),
            root=Path(root) if root else None,
            output=Path(output) if output else None,
            dividers=not no_dividers,
        )
    except (FileNotFoundError, KeyError, RuntimeError, ValueError) as exc:
        print(f"Error: {exc}")
        return 1

    print(output_path)
    print(f"pages={pages}")
    return 0
