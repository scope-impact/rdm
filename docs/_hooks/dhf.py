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

The traceability matrix template is left out: the site carries the generated
matrix (``docs/_hooks/evidence.py``). Nothing here changes the record.
"""

from __future__ import annotations

import html
import re
from pathlib import Path

import yaml
from mkdocs.structure.files import File

from rdm.kernel.frontmatter import parse_frontmatter
from rdm.md_extensions.code import fenced

ROOT = Path(__file__).resolve().parents[2]
DHF = ROOT / "dhf"
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
    lines = ["| Severity \\ Probability | " + " | ".join(map(_value, probabilities)) + " |",
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


def page(text: str) -> str:
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


def on_files(files, config):
    for md in sorted(DHF.rglob("*.md")):
        rel = md.relative_to(DHF).as_posix()
        if rel in SKIP or rel.startswith("allure-results/"):
            continue
        files.append(File.generated(config, f"dhf/{rel}", content=page(md.read_text(encoding="utf-8"))))
    for pattern in ASSETS:
        for asset in sorted(DHF.glob(pattern)):
            files.append(File.generated(config, f"dhf/{asset.relative_to(DHF).as_posix()}", abs_src_path=str(asset)))
    return files
