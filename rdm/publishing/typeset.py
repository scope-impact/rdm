"""``rdm typeset``: a rendered document's PDF (DI-89).

The document's Markdown is converted to Typst markup by RDM's own converter
(markdown-it-py parses it; every character Typst would read as markup is
escaped, so text stays text), its frontmatter's id, revision, title, date and
status go into the project's Typst template (``template.typ``, a module whose
``template`` function the generated document shows with), the images it
names are read beside it, and the whole is compiled through the typeset port
(``rdm.publishing.report``). A Markdown construct the converter has no rule
for, an image it cannot find or a template Typst rejects is refused by name,
and nothing is written: nothing silently vanishes from a controlled document.
"""

from __future__ import annotations

import re
from pathlib import Path

from markdown_it import MarkdownIt
from markdown_it.token import Token
from mdit_py_plugins.footnote import footnote_plugin

from rdm.kernel.frontmatter import parse_frontmatter
from rdm.publishing.report import ReportUnavailable, typeset_files

MAIN = "main.typ"
TEMPLATE = "template.typ"
_FIELDS = ("id", "revision", "title", "date", "status")


class TypesetError(ValueError):
    """The document cannot be typeset; the message names why."""


# --- Markdown to Typst

_ESCAPE = re.compile(r"([\\#*_`$<>@\[\]~])")
_LINE_START = re.compile(r"^(\s*)([-+=/])", re.MULTILINE)


def _text(value: str) -> str:
    """Text as Typst markup that reads back as the same text."""
    out = _ESCAPE.sub(r"\\\1", value)
    out = _LINE_START.sub(lambda m: m.group(1) + "\\" + m.group(2), out)  # a line's first -, +, = or /
    return out.replace("//", "/\u200b/")  # never a comment


def _string(value: str) -> str:
    """A Typst string literal."""
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ") + '"'


class _Converter:
    def __init__(self, footnotes: dict[int, str]) -> None:
        self.footnotes = footnotes
        self.images: list[str] = []

    def inline(self, tokens: list[Token]) -> str:
        out: list[str] = []
        for token in tokens:
            kind = token.type
            if kind == "text":
                out.append(_text(token.content))
            elif kind == "softbreak":
                out.append(" ")
            elif kind == "hardbreak":
                out.append(" \\\n")
            elif kind == "em_open" or kind == "em_close":
                out.append("_")
            elif kind == "strong_open" or kind == "strong_close":
                out.append("*")
            elif kind == "s_open":
                out.append("#strike[")
            elif kind == "s_close":
                out.append("]")
            elif kind == "code_inline":
                fence = "`" * (max((len(m) for m in re.findall(r"`+", token.content)), default=0) + 1)
                pad = " " if token.content.startswith("`") or token.content.endswith("`") else ""
                out.append(f"{fence}{pad}{token.content}{pad}{fence}")
            elif kind == "link_open":
                out.append(f"#link({_string(token.attrGet('href') or '')})[")
            elif kind == "link_close":
                out.append("]")
            elif kind == "image":
                self.images.append(token.attrGet("src") or "")
                alt = self.inline(token.children or [])
                out.append(f"#image({_string(token.attrGet('src') or '')}, alt: {_string(token.content)})" if alt else
                           f"#image({_string(token.attrGet('src') or '')})")
            elif kind == "footnote_ref":
                out.append(f"#footnote[{self.footnotes[token.meta['id']]}]")
            elif kind == "html_inline":
                raise TypesetError(f"inline HTML is not carried: {token.content.strip()}")
            else:
                raise TypesetError(f"a construct the converter does not carry: {kind}")
        return "".join(out)

    def blocks(self, tokens: list[Token], indent: str = "") -> str:
        """Block tokens to Typst, each block ending with a blank line."""
        out: list[str] = []
        i = 0
        while i < len(tokens):
            token = tokens[i]
            kind = token.type
            if kind in ("heading_open", "paragraph_open"):
                # Between the open and its close: the inline token, and a footnote's anchor, which is not drawn.
                end = self._matching(tokens, i, kind.replace("_open", "_close"))
                children = [c for t in tokens[i + 1:end] if t.type == "inline" for c in (t.children or [])]
                text = self.inline(children).replace("\n", "\n" + indent)
                lead = "=" * int(token.tag[1]) + " " if kind == "heading_open" else ""
                out.append(indent + lead + text + "\n\n")
                i = end + 1
            elif kind in ("fence", "code_block"):
                fence = "`" * max(3, max((len(m) for m in re.findall(r"`+", token.content)), default=0) + 1)
                lang = (token.info or "").strip().split()[0] if (token.info or "").strip() else ""
                body = token.content.rstrip("\n").replace("\n", "\n" + indent)
                out.append(f"{indent}{fence}{lang}\n{indent}{body}\n{indent}{fence}\n\n")
                i += 1
            elif kind == "hr":
                out.append(indent + "#line(length: 100%, stroke: 0.5pt)\n\n")
                i += 1
            elif kind in ("bullet_list_open", "ordered_list_open"):
                marker = "-" if kind == "bullet_list_open" else "+"
                close = kind.replace("_open", "_close")
                end = self._matching(tokens, i, close)
                out.append(self._list(tokens[i + 1:end], marker, indent) + "\n")
                i = end + 1
            elif kind == "blockquote_open":
                end = self._matching(tokens, i, "blockquote_close")
                inner = self.blocks(tokens[i + 1:end], indent).rstrip("\n")
                out.append(f"{indent}#quote(block: true)[\n{inner}\n{indent}]\n\n")
                i = end + 1
            elif kind == "table_open":
                end = self._matching(tokens, i, "table_close")
                out.append(self._table(tokens[i + 1:end], indent) + "\n")
                i = end + 1
            elif kind == "footnote_block_open":
                i = self._matching(tokens, i, "footnote_block_close") + 1  # inlined at each reference
            elif kind == "html_block":
                raise TypesetError(f"HTML is not carried: {token.content.strip().splitlines()[0]}")
            else:
                raise TypesetError(f"a construct the converter does not carry: {kind}")
        return "".join(out)

    def _matching(self, tokens: list[Token], start: int, close: str) -> int:
        depth = 0
        open_ = tokens[start].type
        for j in range(start, len(tokens)):
            if tokens[j].type == open_:
                depth += 1
            elif tokens[j].type == close:
                depth -= 1
                if depth == 0:
                    return j
        raise TypesetError(f"unbalanced {open_}")

    def _list(self, tokens: list[Token], marker: str, indent: str) -> str:
        out: list[str] = []
        i = 0
        while i < len(tokens):
            assert tokens[i].type == "list_item_open", tokens[i].type
            end = self._matching(tokens, i, "list_item_close")
            item = self.blocks(tokens[i + 1:end], indent + "  ").rstrip("\n")
            first, _, rest = item.partition("\n")
            out.append(f"{indent}{marker} {first.lstrip()}" + (f"\n{rest}" if rest else "") + "\n")
            i = end + 1
        return "".join(out)

    def _table(self, tokens: list[Token], indent: str) -> str:
        rows: list[list[str]] = []
        header = 0
        aligns: list[str] = []
        i = 0
        while i < len(tokens):
            kind = tokens[i].type
            if kind == "tr_open":
                end = self._matching(tokens, i, "tr_close")
                cells = []
                for j in range(i + 1, end):
                    if tokens[j].type in ("th_open", "td_open"):
                        style = tokens[j].attrGet("style") or ""
                        if len(aligns) < 64 and tokens[j].type == "th_open":
                            aligns.append("right" if "right" in style else "center" if "center" in style else "left")
                        cells.append("[" + self.inline(tokens[j + 1].children or []) + "]")
                rows.append(cells)
                i = end + 1
            elif kind == "thead_open":
                header = 1
                i += 1
            else:
                i += 1
        columns = max(len(r) for r in rows)
        aligns = (aligns + ["left"] * columns)[:columns]
        lines = [f"{indent}#table(columns: {columns}, align: ({', '.join(aligns)},),"]
        if header and rows:
            lines.append(f"{indent}  table.header({', '.join(rows[0])}),")
            rows = rows[1:]
        for row in rows:
            lines.append(f"{indent}  {', '.join(row + ['[]'] * (columns - len(row)))},")
        lines.append(f"{indent})\n")
        return "\n".join(lines)


def markdown_to_typst(markdown: str) -> tuple[str, list[str]]:
    """The Markdown as Typst markup, and the image paths it names."""
    parser = MarkdownIt("commonmark").enable(["table", "strikethrough"]).use(footnote_plugin)
    tokens = parser.parse(markdown)
    converter = _Converter({})
    footnotes: dict[int, str] = {}
    for i, token in enumerate(tokens):  # the footnotes' bodies first, to inline them at their references
        if token.type == "footnote_open":
            end = converter._matching(tokens, i, "footnote_close")
            footnotes[token.meta["id"]] = converter.blocks(tokens[i + 1:end]).strip()
    converter.footnotes = footnotes
    return converter.blocks(tokens), converter.images


# --- the document

def _find(image: str, places: list[Path]) -> Path | None:
    """The first file ``image`` names in the places searched, in order."""
    for place in places:
        candidate = (place / image).resolve()
        if candidate.is_file():
            return candidate
    return None


def typeset_document(markdown_file: Path, template: Path, resource_path: list[Path] | None = None) -> bytes:
    """The PDF of a rendered document, through the typeset port. Images are
    found beside the document, then in each directory of ``resource_path`` in
    order, as Pandoc's ``--resource-path`` finds them."""
    markdown_file, template = Path(markdown_file), Path(template)
    places = [markdown_file.parent, *(Path(p) for p in resource_path or [])]
    if not template.is_file():
        raise TypesetError(f"no Typst template: {template}")
    text = markdown_file.read_text(encoding="utf-8")
    front = parse_frontmatter(text) or {}
    body = text.split("---", 2)[2] if text.startswith("---") and text.count("---") >= 2 else text
    typst, images = markdown_to_typst(body)
    files: dict[str, bytes] = {TEMPLATE: template.read_bytes()}
    for n, image in enumerate(dict.fromkeys(images)):
        source = None if "://" in image else _find(image, places)
        if source is None:
            searched = ", ".join(str(p) for p in places)
            raise TypesetError(f"image not found: {image} (searched {searched})")
        name = f"images/{n}-{source.name}"
        files[name] = source.read_bytes()
        typst = typst.replace(f"#image({_string(image)}", f"#image({_string(name)}")
    given = {field: str(front[field]) for field in _FIELDS if front.get(field) not in (None, "")}
    arguments = ", ".join(f"{field}: [{_text(value)}]" if field == "title" else f"{field}: {_string(value)}"
                          for field, value in given.items())
    files[MAIN] = (f'#import "{TEMPLATE}": template\n#show: template.with({arguments})\n\n{typst}').encode("utf-8")
    return typeset_files(MAIN, files)


def typeset_command(document: str, output: str, template: str | None, resource_path: str | None = None) -> int:
    template_path = Path(template) if template else Path(TEMPLATE)
    places = [Path(p) for p in (resource_path or "").split(":") if p]
    try:
        pdf = typeset_document(Path(document), template_path, places)
    except (TypesetError, ReportUnavailable, OSError) as error:
        print(f"Error: cannot typeset {document}: {error}")
        return 2
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_bytes(pdf)
    print(f"Wrote {output}")
    return 0
