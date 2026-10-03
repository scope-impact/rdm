"""MkDocs build hook: publish RDM's design history file (``dhf/``) with the docs.

Every Markdown document of the record is added to the site under ``dhf/``, as
it is in git, with the C4 views and workspace its documents link to. The
record's substance -- user needs, design inputs, risks, the risk policy -- is
the documents' YAML frontmatter, which a Markdown page would drop, so each
page shows its frontmatter as sections under the title: a list of entries
(each id with its text and fields) for a list of records, a table for short
ones, and the risk policy's levels as a severity-by-probability table. A
section whose name a heading of the document already has goes under that
heading instead.

Each page also links a PDF of the document, built with the template ``rdm
init`` ships (``rdm/specification/init_files``: Pandoc to Typst). In the PDF
the title moves to the cover and the sections up one level. The PDFs come
from the directory ``RDM_DHF_PDFS`` names when it is set -- CI renders them
once, in the RDM image, and hands them to the docs build as an artifact:

    python docs/_hooks/dhf.py OUT     # every document's PDF, OUT/<path>.pdf

-- and are otherwise built here when ``pandoc`` and ``typst`` are on PATH;
without either the pages carry no link.

The traceability matrix template is left out: the site carries the generated
matrix (``docs/_hooks/evidence.py``). Nothing here changes the record.
"""

from __future__ import annotations

import html
import logging
import os
import re
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import yaml

from rdm.kernel.frontmatter import parse_frontmatter
from rdm.md_extensions.code import fenced

ROOT = Path(__file__).resolve().parents[2]
DHF = ROOT / "dhf"
TEMPLATES = ROOT / "rdm" / "specification" / "init_files"
log = logging.getLogger("mkdocs.hooks.dhf")
SKIP = {"documents/traceability_matrix.md"}  # a template; the site has the generated matrix
ASSETS = ("c4/views/*.svg", "c4/workspace.dsl")
SECTIONS = {"user_needs": "User needs", "design_inputs": "Design inputs", "risks": "Risks",
            "contexts": "Bounded contexts", "risk_policy": "Risk policy"}
_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


def _value(value) -> str:
    if isinstance(value, list):
        return ", ".join(_value(v) for v in value)
    if isinstance(value, dict):
        return "; ".join(f"{k} {_value(v)}" for k, v in value.items())
    return html.escape(str(value), quote=False)


def _label(key: str) -> str:
    return key.replace("_", " ").capitalize()


def _entries(items: list) -> list[str]:
    """Records with ids: a table when every value is short, else a list."""
    keys = list(dict.fromkeys(k for item in items for k in item))
    if all(len(_value(v)) <= 60 for item in items for v in item.values()):
        rows = ["| " + " | ".join(_label(k) for k in keys) + " |", "|" + "---|" * len(keys)]
        rows += ["| " + " | ".join(_value(item.get(k, "")) for k in keys) + " |" for item in items]
        return rows
    lines = []
    for item in items:
        head = f"- **{_value(item.get('id', ''))}**"
        if "text" in item:
            head += f" — {_value(item['text'])}"
        lines.append(head)
        lines += [f"    - *{_label(k)}:* {_value(v)}" for k, v in item.items() if k not in ("id", "text")]
    return lines


def _policy(policy: dict) -> list[str]:
    """The levels as a severity-by-probability table, then each level's acceptability."""
    probabilities = policy.get("probabilities") or []
    lines = ["| Severity / probability | " + " | ".join(map(_value, probabilities)) + " |",
             "|" + "---|" * (len(probabilities) + 1)]
    lines += [f"| {_value(s)} | " + " | ".join(map(_value, (policy.get("levels") or {}).get(s, []))) + " |"
              for s in policy.get("severities") or []]
    lines += ["", *[f"- **{_value(level)}** — {_value(verdict)}" for level, verdict in
                    (policy.get("acceptability") or {}).items()]]
    return lines


def _section(value) -> list[str]:
    if isinstance(value, dict) and "levels" in value:
        return _policy(value)
    if isinstance(value, list) and value and all(isinstance(v, dict) for v in value):
        return _entries(value)
    return ["```yaml", yaml.safe_dump(value, sort_keys=False, allow_unicode=True).rstrip(), "```"]


def _body(text: str) -> list[str]:
    """The document's lines after its frontmatter block."""
    lines = text.lstrip("\ufeff").splitlines()
    if lines and lines[0].rstrip() == "---":
        for end, line in enumerate(lines[1:], 1):
            if line.rstrip() == "---":
                return lines[end + 1:]
    return lines


def _is_section(key: str, value) -> bool:
    return key in SECTIONS or isinstance(value, dict) or (
        isinstance(value, list) and any(isinstance(v, dict) for v in value))


def page(text: str, pdf: str | None = None) -> str:
    """A record document as a page: its title, the frontmatter's facts and
    sections under it, then the body. A document with no single top-level
    heading -- sections written as headings of their own, the rendered-
    document style -- gets its title (or id) above them, the rest one level down."""
    data = parse_frontmatter(text)
    lines = _body(text)
    headings = [(i, *m.groups()) for i, (line, code) in enumerate(fenced(lines)) if not code
                for m in [_HEADING.match(line)] if m]
    if sum(1 for _, level, _ in headings if level == "#") != 1:
        for i, level, heading in headings:
            lines[i] = f"#{level} {heading}"
        title = str(data.get("title") or data.get("id") or "Record")
        lines[:0] = [f"# {title}", ""]
        headings = [(0, "#", title)] + [(i + 2, "#" + level, h) for i, level, h in headings]
    top = next(i for i, level, _ in headings if level == "#")

    facts = [f"{_label(k)}: `{_value(v)}`" for k, v in data.items() if k != "title" and not _is_section(k, v)]
    if pdf:
        facts.append(f"[Download as PDF]({pdf})")
    head = ["", " · ".join(facts)] if facts else []
    inserts: dict[int, list[str]] = {}
    for key, value in data.items():
        if not _is_section(key, value):
            continue
        name = SECTIONS.get(key, _label(key))
        own = [(i, level) for i, level, h in headings if h.lower() == name.lower()]
        if own:  # at the end of the document's own section of that name
            i, level = own[0]
            end = next((j for j, lv, _ in headings if j > i and len(lv) <= len(level)), len(lines))
            inserts.setdefault(end - 1, []).extend(["", *_section(value), ""])
        else:
            head += ["", f"## {name}", "", *_section(value)]
    inserts.setdefault(top, [])[:0] = head
    out = []
    for i, line in enumerate(lines):
        out.append(line)
        out.extend(inserts.get(i, []))
    return "\n".join(out).rstrip() + "\n"


def _git(path: Path, fmt: str) -> str:
    result = subprocess.run(["git", "log", "-1", f"--format={fmt}", "--", str(path)],
                            cwd=ROOT, capture_output=True, text=True)
    return result.stdout.strip()


def pdf_source(text: str, path: Path) -> str:
    """The page as Pandoc input for the template: the title, id, revision,
    date and status as metadata (the cover), the sections one level up."""
    data = parse_frontmatter(text)
    lines = page(text).splitlines()
    code = [c for _, c in fenced(lines)]
    top = next(i for i, line in enumerate(lines) if not code[i] and line.startswith("# "))
    title = lines[top][2:].strip()
    body = [line[1:] if not code[i] and re.match(r"##+ ", line) else line
            for i, line in enumerate(lines) if i != top]
    meta = {"id": str(data.get("id", path.stem)), "title": title,
            "revision": str(data.get("revision") or _git(path, "%h") or "1"),
            "date": _git(path, "%cs") or "", "status": str(data.get("status") or "Controlled in git")}
    return "---\n" + yaml.safe_dump(meta, sort_keys=False, allow_unicode=True) + "---\n\n" + "\n".join(body) + "\n"


def build_pdfs(documents: dict[str, Path], out: Path) -> dict[str, Path]:
    """Each document's PDF, built with the template ``rdm init`` ships; none
    without pandoc and typst on PATH. A document that fails is a warning."""
    pandoc = shutil.which("pandoc")
    if not (pandoc and shutil.which("typst")):
        log.info("dhf: no PDFs (pandoc and typst are not both on PATH)")
        return {}
    for name in ("template.typ", "pandoc_pdf.yml"):
        shutil.copy(TEMPLATES / name, out / name)

    def one(rel: str, md: Path) -> tuple[str, Path | None]:
        source, pdf = out / "src" / rel, out / "pdf" / Path(rel).with_suffix(".pdf")
        source.parent.mkdir(parents=True, exist_ok=True)
        pdf.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(pdf_source(md.read_text(encoding="utf-8"), md), encoding="utf-8")
        result = subprocess.run([pandoc, "--defaults=pandoc_pdf.yml", f"--resource-path={md.parent}:{DHF}",
                                 str(source), "-o", str(pdf)], cwd=out, capture_output=True, text=True)
        if result.returncode:
            log.warning("dhf: no PDF of %s: %s", rel, result.stderr.strip()[-500:])
            return rel, None
        return rel, pdf

    with ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as pool:
        return {rel: pdf for rel, pdf in pool.map(lambda item: one(*item), documents.items()) if pdf}


def published() -> dict[str, Path]:
    """The documents the site publishes, by their path under the DHF."""
    documents = {md.relative_to(DHF).as_posix(): md for md in sorted(DHF.rglob("*.md"))}
    return {rel: md for rel, md in documents.items() if rel not in SKIP and not rel.startswith("allure-results/")}


def prebuilt(documents: dict[str, Path], directory: Path) -> dict[str, Path]:
    """The PDFs CI rendered into ``directory``; a document without one is a warning."""
    pdfs = {rel: directory / Path(rel).with_suffix(".pdf") for rel in documents}
    for rel in [rel for rel, pdf in pdfs.items() if not pdf.is_file()]:
        log.warning("dhf: no PDF of %s in %s", rel, directory)
        del pdfs[rel]
    return pdfs


def on_files(files, config):
    from mkdocs.structure.files import File

    documents = published()
    given = os.environ.get("RDM_DHF_PDFS")
    if given:
        pdfs = prebuilt(documents, Path(given))
    else:
        pdfs = build_pdfs(documents, Path(tempfile.mkdtemp(prefix="rdm-dhf-")))
    for rel, md in documents.items():
        pdf, name = pdfs.get(rel), Path(rel).with_suffix(".pdf")
        files.append(File.generated(config, f"dhf/{rel}", content=page(md.read_text(encoding="utf-8"),
                                                                       pdf and name.name)))
        if pdf:
            files.append(File.generated(config, f"dhf/{name.as_posix()}", abs_src_path=str(pdf)))
    for pattern in ASSETS:
        for asset in sorted(DHF.glob(pattern)):
            files.append(File.generated(config, f"dhf/{asset.relative_to(DHF).as_posix()}", abs_src_path=str(asset)))
    return files


if __name__ == "__main__":  # render every document's PDF into a directory, as CI does in the RDM image
    import sys

    logging.basicConfig(format="%(levelname)s %(message)s")
    out = Path(sys.argv[1]).resolve()
    documents = published()
    built = build_pdfs(documents, Path(tempfile.mkdtemp(prefix="rdm-dhf-")))
    for rel, pdf in built.items():
        target = out / Path(rel).with_suffix(".pdf")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(pdf, target)
    print(f"{len(built)} of {len(documents)} PDF(s) in {out}")
    sys.exit(0 if len(built) == len(documents) else 1)
