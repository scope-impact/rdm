"""The user manual in the graph (DI-77): the pages a ``kind: manual``
controlled document lists, each with the design inputs it names and the
labels of each tagged-test example it shows, in the ``manual`` named graph.

A page's text is its own and that of each file it includes whole with a
snippet line (``--8<-- "file"``); a sectioned include (``"file:section"``) is
not read. Page and include paths are from the project root (the DHF's parent).
"""

from __future__ import annotations

import re
from pathlib import Path

MANUAL_KIND = "manual"

_INCLUDE = re.compile(r'^[ \t]*--8<--[ \t]+"([^":]+)"[ \t]*$', re.MULTILINE)
_FENCE = re.compile(r"^([ \t]*)(`{3,}|~{3,})[^\n]*\n(.*?)^\1\2[ \t]*$", re.MULTILINE | re.DOTALL)
_STORY = re.compile(r"^[ \t]*@allure\.story\(", re.MULTILINE)  # a decorator starts its line
_LABEL = re.compile(r"""@allure\.label\(\s*["']([^"']+)["']""")


def manual_pages(front: dict) -> list[str]:
    """The pages a manual document's ``pages`` frontmatter lists."""
    pages = front.get("pages") or []
    return [str(p).strip() for p in ([pages] if isinstance(pages, str) else pages) if str(p).strip()]


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def page_text(project: Path, page: str) -> tuple[str, list[str]]:
    """The page's text with the files it includes whole, and those files."""
    text = _read(project / page)
    included = [name.strip() for name in _INCLUDE.findall(text) if (project / name.strip()).is_file()]
    return "\n".join([text, *(_read(project / name) for name in included)]), included


def examples(text: str) -> list[list[str]]:
    """Each tagged-test example in the text, a fenced block with an Allure
    story decorator: the names of the labels it writes, ``story`` first."""
    return [["story", *_LABEL.findall(body)] for _, _, body in _FENCE.findall(text) if _STORY.search(body)]


def project_manual(ds, documents: list[dict], project: Path, inputs: set[str], rdm) -> None:
    """Add each manual document's pages to the ``manual`` graph: ``documents``
    are the controlled documents (``controlled_documents``), ``inputs`` the
    declared design input ids."""
    g = "manual"
    alternatives = "|".join(sorted(map(re.escape, inputs), key=len, reverse=True))
    named = re.compile(rf"(?<![\w-])({alternatives})(?![\w-])") if inputs else None
    for entry in documents:
        if str(entry["front"].get("kind", "")).strip() != MANUAL_KIND:
            continue
        doc = ds.node("doc", entry["id"])
        for page in manual_pages(entry["front"]):
            if not (project / page).is_file():
                ds.add(doc, rdm("missingPage"), page, g)  # a warning (DI-77)
                continue
            node = ds.thing(ds.node("page", page), rdm("ManualPage"), page, g)
            ds.add(node, rdm("path"), page, g)
            ds.add(node, rdm("pageOf"), doc, g)
            text, included = page_text(project, page)
            for name in included:
                ds.add(node, rdm("includesFile"), name, g)
            for ident in sorted(set(named.findall(text))) if named else []:
                ds.add(node, rdm("namesInput"), ds.node("input", ident), g)
            for n, labels in enumerate(examples(text), start=1):
                example = ds.thing(ds.node("example", f"{page}#{n}"), rdm("TestExample"), f"{page} example {n}", g)
                ds.add(example, rdm("onPage"), node, g)
                for name in labels:
                    ds.add(example, rdm("writesLabel"), name, g)
