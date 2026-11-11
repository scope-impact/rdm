"""MkDocs build hook: publish RDM's design history file (``dhf/``) with the docs.

Every Markdown document of the record is added to the site under ``dhf/``, as
it is in git. The record's substance -- risks, the risk policy, the contexts -- is
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
    python docs/_hooks/dhf.py --dhf examples/loyly-pi-harness/dhf OUT   # another project's

A document that holds Jinja is a template: it is rendered with ``rdm render``
(the DHF's config and its generated ``data/*.yml``, from the project root)
before it becomes a page or a PDF, so a final document carries the spec it
``include``s and the evidence it reads.

-- and are otherwise built here when ``pandoc`` and ``typst`` are on PATH;
without either the pages carry no link.

RDM's own spec, written the Löyly way (``.pi/spec/<context>/``, outside
``dhf/``), is published beside it as ``dhf/spec/<context>.md``: the context's
user needs (event-model screens, with their Allium surfaces, rules,
guarantees and event-storm events), a table of its requirements with the
need each addresses, and its ``spec.feature`` and Allium as written.

The traceability matrix template is left out: the site draws traceability
from the graph (``docs/_hooks/traceability.py``). Nothing here changes the record.
"""

from __future__ import annotations

import html
import json
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
PLAN = ROOT / ".pi" / "plan"
TEMPLATES = ROOT / "rdm" / "specification" / "init_files"
log = logging.getLogger("mkdocs.hooks.dhf")
SKIP = {"documents/traceability_matrix.md"}  # a template; the site has the generated matrix
SECTIONS = {"risks": "Risks", "contexts": "Bounded contexts", "risk_policy": "Risk policy"}
_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


def _value(value) -> str:
    if isinstance(value, list):
        return ", ".join(_value(v) for v in value) if value else "none"
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


def _verification() -> dict:
    """The DHF's generated verification data, or nothing when it is not there."""
    path = DHF / "data" / "verification.yml"
    return (yaml.safe_load(path.read_text(encoding="utf-8")) or {}) if path.is_file() else {}


def _risks() -> list[str]:
    """The register as a reader takes it in: a table of every risk at a glance
    (its hazard, its level before and after its controls, the decision), then
    a card per risk: its story (hazard, situation, harm), its rating worked
    out against the policy, its controls by title with their verification,
    and its acceptance. Levels and decisions are RDM's own evaluation
    (``rdm.risk.register``); the marks are the templates' to draw."""
    from rdm.risk.register import policy_or_none, residual_decision, risks

    rows = {r["requirement"]: r for g in _verification().get("groups") or [] for r in g.get("requirements") or []}
    verified = {i for i, r in rows.items() if r.get("status") == "verified"}
    policy = policy_or_none(DHF)
    register = risks(DHF, policy)

    def level(value):
        return f"[{value}]{{.level}}" if value else "not rated"

    lines = ["| Risk | Hazard | Before | After | Decision |", "|---|---|---|---|---|"]
    for r in register:
        lines.append(f"| {r.id} | {_value(r.hazard)} | {level(r.level)} | {level(r.residual_level)} | "
                     f"{residual_decision(r, policy, verified)} |")
    for r in register:
        decision = residual_decision(r, policy, verified)
        tags = " ".join(f"[{_value(t)}]{{.tag}}" for t in (r.category, r.stride, r.status) if t)
        residual = (r.residual_severity or r.severity, r.residual_probability or r.probability)
        lines += ["", f'::: {{.risk-card level="{r.residual_level or ""}"}}',
                  f"**{r.id}** {tags} {level(r.level)} → {level(r.residual_level)}", "",
                  f"**Hazard** {_value(r.hazard)}  ", f"**Situation** {_value(r.situation)}  ",
                  f"**Harm** {_value(r.harm)}  ",
                  f"**Rating** {r.severity} × {r.probability} = {level(r.level)}; after its controls "
                  f"{residual[0]} × {residual[1]} = {level(r.residual_level)}: {decision}", ""]
        if r.controls:
            lines += ["**Controls**", ""]
            for c in r.controls:
                row = rows.get(c, {})
                status = row.get("status", "untested")
                mark = {"verified": "✓ verified", "failed": "✗ failed"}.get(status, status)
                lines.append(f"- `{c}` {_value(row.get('text', ''))} [{mark}]{{.{status}}}")
            lines.append("")
        else:
            lines += ["**Controls** none", ""]
        if r.accepted_by:
            lines += [f"> **Accepted** by {_value(r.accepted_by)}: {_value(r.acceptance_rationale)}", ""]
        lines.append(":::")
    return lines


def _policy(policy: dict) -> list[str]:
    """The levels as a severity-by-probability table, then each level's acceptability."""
    probabilities = policy.get("probabilities") or []
    lines = ["| Severity / probability | " + " | ".join(map(_value, probabilities)) + " |",
             "|" + "---|" * (len(probabilities) + 1)]
    levels = policy.get("levels") or {}
    lines += [f"| {_value(s)} | " + " | ".join(f"[{_value(v)}]{{.level}}" for v in levels.get(s, [])) + " |"
              for s in policy.get("severities") or []]
    lines += ["", *[f"- **{_value(level)}** — {_value(verdict)}" for level, verdict in
                    (policy.get("acceptability") or {}).items()]]
    return lines


def _section(value, key: str = "") -> list[str]:
    if key == "risks" and isinstance(value, list):
        try:
            return _risks()
        except Exception as error:  # a register RDM cannot evaluate is the gate's to report; show it as written
            log.warning("dhf: risks shown as written: %s", error)
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
            inserts.setdefault(end - 1, []).extend(["", *_section(value, key), ""])
        else:
            head += ["", f"## {name}", "", *_section(value, key)]
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


_MERMAID = re.compile(r"^```mermaid[ \t]*\n(.*?)^```[ \t]*$", re.M | re.S)


def mermaid_to_png(text: str, out: Path, stem: str) -> str:
    """Each ```mermaid block as a PNG the PDF shows: drawn with Mermaid's own
    command line (``mmdc``, or ``RDM_MMDC``), with the Chromium that
    ``PUPPETEER_EXECUTABLE_PATH`` names when it is set. Without ``mmdc`` the
    block stays as code and a warning says why; the docs site draws Mermaid
    itself."""
    mmdc = os.environ.get("RDM_MMDC") or shutil.which("mmdc")
    if not mmdc or "```mermaid" not in text:
        if "```mermaid" in text:
            log.warning("dhf: %s keeps its Mermaid as code (no mmdc on PATH)", stem)
        return text
    config = out / "puppeteer.json"
    if not config.exists():
        settings = {"args": ["--no-sandbox"]}
        if os.environ.get("PUPPETEER_EXECUTABLE_PATH"):
            settings["executablePath"] = os.environ["PUPPETEER_EXECUTABLE_PATH"]
        config.write_text(json.dumps(settings))
    count = 0

    def draw(match):
        nonlocal count
        count += 1
        source, png = out / f"{stem}-{count}.mmd", out / f"{stem}-{count}.png"
        source.write_text(match.group(1), encoding="utf-8")
        result = subprocess.run([mmdc, "-i", str(source), "-o", str(png), "-p", str(config), "-s", "3",
                                 "-b", "white", "-w", "1200"], capture_output=True, text=True)
        if result.returncode or not png.is_file():
            log.warning("dhf: a Mermaid diagram of %s stays as code: %s", stem, result.stderr.strip()[-300:])
            return match.group(0)
        return f"![]({png})"

    return _MERMAID.sub(draw, text)


def build_pdfs(documents: dict[str, Path], out: Path) -> dict[str, Path]:
    """Each document's PDF, built with the template ``rdm init`` ships; none
    without pandoc and typst on PATH. A document that fails is a warning."""
    pandoc = shutil.which("pandoc")
    if not (pandoc and shutil.which("typst")):
        log.info("dhf: no PDFs (pandoc and typst are not both on PATH)")
        return {}
    for name in ("template.typ", "pandoc_pdf.yml", "cards.lua"):
        shutil.copy(TEMPLATES / name, out / name)

    def one(rel: str, md: Path) -> tuple[str, Path | None]:
        source, pdf = out / "src" / rel, out / "pdf" / Path(rel).with_suffix(".pdf")
        source.parent.mkdir(parents=True, exist_ok=True)
        pdf.parent.mkdir(parents=True, exist_ok=True)
        stem = rel.replace("/", "-").removesuffix(".md")
        source.write_text(mermaid_to_png(pdf_source(text_of(md), md), out, stem), encoding="utf-8")
        result = subprocess.run([pandoc, "--defaults=pandoc_pdf.yml", f"--resource-path={md.parent}:{DHF}",
                                 str(source), "-o", str(pdf)], cwd=out, capture_output=True, text=True)
        if result.returncode:
            log.warning("dhf: no PDF of %s: %s", rel, result.stderr.strip()[-500:])
            return rel, None
        return rel, pdf

    with ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as pool:
        return {rel: pdf for rel, pdf in pool.map(lambda item: one(*item), documents.items()) if pdf}


def text_of(md: Path) -> str:
    """A document as published: as written, or, when it is a template (it holds
    Jinja), rendered with ``rdm render`` from its project's root (the DHF's
    parent: the paths its filters read are relative to it), with the DHF's
    ``config.yml`` and the generated data in ``data/*.yml`` when there is any.
    A final document carries its project's spec and evidence this way."""
    text = md.read_text(encoding="utf-8")
    if "{{" not in text and "{%" not in text:
        return text
    import contextlib

    from rdm.kernel.util import load_yaml
    from rdm.publishing.render import context_from_data_files, render_template_to_string

    project = DHF.parent
    config = load_yaml(str(DHF / "config.yml")) if (DHF / "config.yml").is_file() else {}
    data = sorted(str(p.relative_to(project)) for p in (DHF / "data").glob("*.yml")) if (DHF / "data").is_dir() else []
    with contextlib.chdir(project):
        return render_template_to_string(config or {}, str(md.relative_to(project)), context_from_data_files(data))


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


def spec_pages() -> dict[str, str]:
    """RDM's spec, one page per context, by its path on the site."""
    from rdm.specification.spec import read_spec

    spec = read_spec(PLAN, ROOT)
    pages = {}
    for entry, context in spec.contexts:
        folder = PLAN / entry
        title = context.replace("_", " ")
        lines = [f"# Spec — {title}", "",
                 f"Slice `{entry}` of RDM's spec, written the Löyly way in `.pi/spec/{context}/`, read as the "
                 "release gate reads it. Each user need is a screen of the event model, linked to its Allium "
                 "surface; each accepted scenario, stamped with an id, is a requirement.", ""]
        needs = [n for n in spec.needs if n.context == context]
        lines += ["## User needs", ""]
        if not needs:
            lines += ["This context is home to no user need: its requirements address needs of other contexts.", ""]
        for need in needs:
            lines += [f"### `{need.id}`", "", need.text, ""]
            facts = [("Surface", ", ".join(need.surfaces)), ("Facing", ", ".join(need.actors)),
                     ("Rules", ", ".join(need.rules)), ("Guarantees", ", ".join(need.guarantees)),
                     ("Event storm", "; ".join(need.storm_events))]
            lines += [f"- *{k}:* {_value(v)}" for k, v in facts if v] + [""]
        items = [i for i in spec.items if i.context == context]
        lines += ["## Requirements", "", "| Requirement | Scenario | Addresses | Rule |", "|---|---|---|---|"]
        lines += [f"| `{i.id}` | {_value(i.title)} | {', '.join(f'`{n}`' for n in spec.needs_of(i))} | "
                  f"{_value(i.rule or '')} |" for i in items]
        lines += [""]
        sources = [folder / "spec.feature", *sorted((folder / "allium").glob("*.allium"))]
        for path in sources:
            if path.is_file():
                language = "gherkin" if path.suffix == ".feature" else "text"
                body = path.read_text(encoding="utf-8").rstrip()
                lines += [f'??? note "{path.relative_to(folder).as_posix()}"', "", f"    ```{language}"]
                lines += [f"    {line}" if line else "" for line in body.splitlines()]
                lines += ["    ```", ""]
        pages[f"dhf/spec/{context}.md"] = "\n".join(lines) + "\n"
    return pages


_DIV_OPEN = re.compile(r"^(:{3,})\s*\{([^}]*)\}\s*$")
_DIV_CLOSE = re.compile(r"^:{3,}\s*$")
_SPAN = re.compile(r"\[((?:\\.|[^\]\\])*)\]\{\.([A-Za-z-]+)\}")
_ATTR = re.compile(r'([A-Za-z-]+)="([^"]*)"')


def html_marks(text: str) -> str:
    """The marks the render filters put in the Markdown (Pandoc's fenced divs
    and bracketed spans) as the docs site's HTML: ``<div class=...>`` with
    its Markdown kept (md_in_html) and ``<span class=...>``; the PDF template
    draws the same marks (cards.lua). Code blocks are left as they are."""
    out, code = [], [c for _, c in fenced(text.splitlines())]
    for line, is_code in zip(text.splitlines(), code):
        if is_code:
            out.append(line)
            continue
        opened = _DIV_OPEN.match(line)
        if opened:
            classes = " ".join(c[1:] for c in opened.group(2).split() if c.startswith("."))
            data = " ".join(f'data-{k}="{html.escape(v)}"' for k, v in _ATTR.findall(opened.group(2)))
            out += ["", f'<div class="{classes}" {data} markdown="1">'.replace("  ", " "), ""]
        elif _DIV_CLOSE.match(line):
            out += ["", "</div>", ""]
        else:
            out.append(_SPAN.sub(lambda m: f'<span class="{m.group(2)}" data-value="{html.escape(m.group(1))}">'
                                           f'{m.group(1)}</span>', line))
    return "\n".join(out) + "\n"


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
        files.append(File.generated(config, f"dhf/{rel}", content=html_marks(page(text_of(md),
                                                                                  pdf and name.name))))
        if pdf:
            files.append(File.generated(config, f"dhf/{name.as_posix()}", abs_src_path=str(pdf)))
    for path, content in spec_pages().items():
        files.append(File.generated(config, path, content=content))
    return files


if __name__ == "__main__":  # render every document's PDF into a directory, as CI does in the RDM image
    import sys

    logging.basicConfig(format="%(levelname)s %(message)s")
    args = sys.argv[1:]
    if args[:1] == ["--dhf"]:  # another project's DHF: the worked example's final document, say
        DHF = Path(args[1]).resolve()
        args = args[2:]
    out = Path(args[0]).resolve()
    documents = published()
    built = build_pdfs(documents, Path(tempfile.mkdtemp(prefix="rdm-dhf-")))
    for rel, pdf in built.items():
        target = out / Path(rel).with_suffix(".pdf")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(pdf, target)
    print(f"{len(built)} of {len(documents)} PDF(s) in {out}")
    sys.exit(0 if len(built) == len(documents) else 1)
