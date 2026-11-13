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


# The binder's own pages (cover, section dividers) by default; a manifest's
# style: overrides any of these, so a binder can match its documents' template.
DEFAULT_STYLE = {
    "background": "#00414D",  # page fill
    "accent": "#ED8023",      # the small label above the title
    "text": "#FFFFFF",        # title and subtitle
    "rule": "#22565E",        # the line under a section's title
    "title_font": None,       # a TrueType file; none: Times-Bold
    "text_font": None,        # a TrueType file; none: Helvetica
    "label": "RELEASE BINDER",
    "section_label": "BINDER SECTION",
    "page_numbers": False,    # the binder's own page number on every page
    "page_number_color": "#8A8A8A",  # legible on light and dark pages
}


def _font(path, fallback: str) -> str:
    """A TrueType font registered once under its file's stem, or the fallback
    built-in font when none is given or the file is missing."""
    if not path or not Path(path).is_file():
        return fallback
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont

    name = Path(path).stem
    if name not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(name, str(path)))
    return name


def _page(title: str, subtitle: str, kind: str, style: dict | None = None):
    _, _, colors, A4, cm, canvas = _require_pdf_dependencies()
    style = {**DEFAULT_STYLE, **(style or {})}

    deep_teal = colors.HexColor(style["background"])
    teal_mid = colors.HexColor(style["rule"])
    orange = colors.HexColor(style["accent"])
    ink = colors.HexColor(style["text"])
    title_font = _font(style["title_font"], "Times-Bold")
    text_font = _font(style["text_font"], "Helvetica")
    label_font = text_font if style["text_font"] and text_font != "Helvetica" else "Helvetica-Bold"

    def fitted(text: str, font: str, size: float, room: float) -> float:
        """The size, shrunk until the text fits the room (a long title in a wide font)."""
        from reportlab.pdfbase.pdfmetrics import stringWidth

        while size > 10 and stringWidth(text, font, size) > room:
            size -= 1
        return size

    buffer = BytesIO()
    c = canvas.Canvas(buffer, pagesize=A4)
    width, height = A4

    c.setFillColor(deep_teal)
    c.rect(0, 0, width, height, fill=1, stroke=0)
    c.setFillColor(ink)

    if kind == "cover":
        c.setFillColor(orange)
        c.setFont(label_font, 11)
        c.drawCentredString(width / 2, height - 4.6 * cm, style["label"])
        c.setFillColor(ink)
        c.setFont(title_font, fitted(title, title_font, 28, width - 4 * cm))
        c.drawCentredString(width / 2, height - 6.2 * cm, title)
        if subtitle:
            c.setFont(text_font, 14)
            c.drawCentredString(width / 2, height - 7.4 * cm, subtitle)
        c.setFont(text_font, 10)
        c.drawCentredString(width / 2, 3.2 * cm, f"Generated {date.today().isoformat()}")
    else:
        # Centred, as the cover and the documents' own covers are.
        c.setFillColor(orange)
        c.setFont(label_font, 10)
        c.drawCentredString(width / 2, height - 4.6 * cm, style["section_label"])
        c.setFillColor(ink)
        c.setFont(title_font, fitted(title, title_font, 25, width - 4 * cm))
        c.drawCentredString(width / 2, height - 6.2 * cm, title)
        if subtitle:
            c.setFont(text_font, 12)
            c.drawCentredString(width / 2, height - 7.2 * cm, subtitle)
        c.setStrokeColor(teal_mid)
        c.setLineWidth(1)
        c.line(width / 2 - 3 * cm, height - 8.0 * cm, width / 2 + 3 * cm, height - 8.0 * cm)

    c.showPage()
    c.save()
    buffer.seek(0)
    PdfReader, _, _, _, _, _ = _require_pdf_dependencies()
    return PdfReader(buffer)


def _number_pages(writer, title: str, style: dict) -> None:
    """Stamp each page but the cover with the binder's own page number, in the
    bottom margin under the documents' footers, so a page can be cited in the
    binder (each document numbers its pages from 1).

    The stamp is a few operators appended to each page's content, in a
    built-in font shared by every page. Merging a stamp page instead
    (pypdf's merge_page) gives each page its own copy of the resources it
    shares with its document's other pages: a 260-page binder grew from
    3 MB to 13 MB.
    """
    from pypdf.generic import ArrayObject, DecodedStreamObject, DictionaryObject, NameObject
    from reportlab.lib import colors
    from reportlab.lib.units import cm
    from reportlab.pdfbase.pdfmetrics import stringWidth

    style = {**DEFAULT_STYLE, **(style or {})}
    colour = colors.HexColor(style["page_number_color"])
    font_name = NameObject("/RdmBinderPageNumber")
    font = writer._add_object(DictionaryObject({
        NameObject("/Type"): NameObject("/Font"),
        NameObject("/Subtype"): NameObject("/Type1"),
        NameObject("/BaseFont"): NameObject("/Helvetica"),
        NameObject("/Encoding"): NameObject("/WinAnsiEncoding"),
    }))

    def stream(data: bytes):
        s = DecodedStreamObject()
        s.set_data(data)
        return writer._add_object(s)

    # The page's own content runs in a saved graphics state, so whatever
    # state it leaves cannot move or recolour the stamp.
    save = stream(b"q\n")
    total = len(writer.pages)
    for number, page in enumerate(writer.pages, start=1):
        if number == 1:
            continue
        text = f"{title} \u00b7 page {number} of {total}"
        encoded = text.encode("cp1252", "replace")
        escaped = encoded.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")
        x0, _, x1, _ = (float(v) for v in page.mediabox)
        x = x1 - 2 * cm - stringWidth(text, "Helvetica", 7)
        y = float(page.mediabox[1]) + 0.7 * cm
        stamp = stream(
            b"Q\nq BT /RdmBinderPageNumber 7 Tf "
            + f"{colour.red:.3f} {colour.green:.3f} {colour.blue:.3f} rg {x:.2f} {y:.2f} Td (".encode()
            + escaped + b") Tj ET Q\n"
        )
        contents = page.get("/Contents")
        if contents is None:
            parts = []
        elif isinstance(contents.get_object(), ArrayObject):
            parts = list(contents.get_object())
        else:
            parts = [page.raw_get("/Contents")]
        page[NameObject("/Contents")] = ArrayObject([save, *parts, stamp])

        if "/Resources" not in page:
            page[NameObject("/Resources")] = DictionaryObject()
        resources = page["/Resources"].get_object()
        if "/Font" not in resources:
            resources[NameObject("/Font")] = DictionaryObject()
        resources["/Font"].get_object()[font_name] = font


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
    style = manifest.get("style") or {}
    cover_start = _append_reader(
        writer,
        _page(title, str(manifest.get("subtitle") or ""), "cover", style),
    )
    writer.add_outline_item(title, cover_start)

    for section in manifest.get("sections", []):
        section_title = str(section["title"])
        files = section.get("files", [])
        # A section of one document opens on that document's own cover: a
        # divider before it would be a second cover. A section may say so
        # itself with divider: true or false.
        section_bookmark = None
        if dividers and section.get("divider", len(files) != 1):
            section_start = _append_reader(
                writer,
                _page(section_title, str(section.get("subtitle") or ""), "divider", style),
            )
            section_bookmark = writer.add_outline_item(section_title, section_start)

        for entry in files:
            input_path = Path(entry["path"])
            if not input_path.is_absolute():
                input_path = base / input_path
            document_start = _append_reader(writer, PdfReader(str(input_path)))
            # Without a divider the section's bookmark is its first document's
            # first page, which exists only now.
            if section_bookmark is None:
                section_bookmark = writer.add_outline_item(section_title, document_start)
            # One document under a section of its own title: the section's
            # bookmark is enough.
            if len(files) != 1 or str(entry["title"]) != section_title:
                writer.add_outline_item(str(entry["title"]), document_start, parent=section_bookmark)

    if style.get("page_numbers"):
        _number_pages(writer, title, style)

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
