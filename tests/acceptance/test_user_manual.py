"""Acceptance tests for RDM's own manual, IFU-001 (item-07-005, item-07-007, item-07-006), see dhf/.

Tagged `@allure.tag`. Skips cleanly if allure-pytest or the `graph` extra is
not installed.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
ox = pytest.importorskip("pyoxigraph")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]
_INCLUDE = re.compile(r'^[ \t]*--8<--[ \t]+"([^":]+)"[ \t]*$', re.MULTILINE)


def _pages(front: dict) -> list[str]:
    """The pages a manual document's ``pages`` frontmatter lists."""
    pages = front.get("pages") or []
    return [str(p).strip() for p in ([pages] if isinstance(pages, str) else pages) if str(p).strip()]


def _page_text(page: str) -> str:
    """The page's text with the files it includes whole (``--8<-- "file"``)."""
    text = (ROOT / page).read_text(encoding="utf-8")
    included = [name.strip() for name in _INCLUDE.findall(text) if (ROOT / name.strip()).is_file()]
    return "\n".join([text, *((ROOT / name).read_text(encoding="utf-8") for name in included)])


CHAPTERS = ["RDM documentation", "What RDM is for", "How it works", "Limits and risks", "Install", "Quick start",
            "Guides", "Troubleshooting", "Reference", "Changelog"]


def _ifu() -> tuple[dict, list[tuple[str, str]]]:
    """RDM's IFU-001: its frontmatter, and each listed page with its title (the
    first level-one heading of the page and the files it includes whole)."""
    from rdm.graph.project import controlled_documents

    front = next(e["front"] for e in controlled_documents(ROOT / "dhf", ROOT) if e["id"] == "IFU-001")
    titled = []
    for page in _pages(front):
        text = _page_text(page)
        title = next((line[2:].strip() for line in text.splitlines() if line.startswith("# ")), "")
        titled.append((page, title))
    return front, titled


@allure.tag("item-07-005")
def test_the_ifu_is_one_manual_in_reading_order() -> None:
    """item-07-005: IFU-001 lists RDM's manual in the reading order of instructions
    for use, behind a cover naming the product, its release and the manual's
    id and revision."""
    front, titled = _ifu()
    attach("IFU-001 pages and titles", titled)
    titles = [title for _, title in titled]

    with verification_step("the chapters appear in the order of instructions for use"):
        found = [t for t in titles if t in CHAPTERS]
        assert found == CHAPTERS, found
    with verification_step("every other page sits inside its chapter: in the folder of the chapter page before it"):
        chapter_folder, misplaced = None, []
        for page, title in titled:
            if title in CHAPTERS:
                chapter_folder = Path(page).parent
            elif Path(page).parent != chapter_folder:
                misplaced.append((page, title))
        assert misplaced == [], misplaced
    with verification_step("the site's docs tab shows exactly these pages, in this order"):
        nav = (ROOT / "mkdocs.yml").read_text()
        tab = nav[nav.index("  - Docs:"):nav.index("  - Design history")]
        shown = ["docs/" + p for p in re.findall(r"([\w./-]+\.md)\s*$", tab, re.M)]
        assert shown == [page for page, _ in titled], shown
    with verification_step("the first page is the cover, naming the product, the release, and the manual's id and "
                           "revision"):
        cover = (ROOT / titled[0][0]).read_text()
        assert titles[0] == CHAPTERS[0]
        assert "{{ rdm.release }}" in cover
    with verification_step("the build fills in the release: the tag of a release build, the development version "
                           "otherwise; install commands take the same ref"):
        version = _version_hook()
        assert version.fill("{{ rdm.release }} @{{ rdm.ref }}", {"RDM_DOCS_VERSION": "v9.1.0"}) == "v9.1.0 @v9.1.0"
        development = version.fill("{{ rdm.release }} @{{ rdm.ref }}", {"RDM_DOCS_VERSION": "dev"})
        assert development.startswith("development version") and development.endswith("@main")
        stale = [str(p) for p in (ROOT / "docs").rglob("*.md") if "_hooks" not in p.parts
                 and re.search(r"@v\d+\.\d+\.\d+", p.read_text())]
        assert stale == [], stale  # no page pins a release by hand
        assert f"IFU-001, revision {front['revision']}" in cover


def _version_hook():
    import importlib.util

    spec = importlib.util.spec_from_file_location("docs_version", ROOT / "docs" / "_hooks" / "version.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@allure.tag("item-07-006")
def test_the_docs_are_published_once_per_release() -> None:
    """item-07-006: the docs are published once per release tag and once for the
    development version, every release kept and selectable, the newest release
    the default."""
    import yaml

    workflow = yaml.safe_load((ROOT / ".github" / "workflows" / "docs.yml").read_text())
    nav = (ROOT / "mkdocs.yml").read_text()
    deploy = workflow["jobs"]["deploy"]
    script = "\n".join(step.get("run", "") for step in deploy["steps"])
    attach("deploy job", deploy)

    with verification_step("a release tag and a push to main both publish"):
        assert "v*" in workflow[True]["push"]["tags"] and workflow[True]["push"]["branches"] == ["main"]
        assert "refs/tags/v" in deploy["if"] and "refs/heads/main" in deploy["if"]
    with verification_step("a tag publishes under its own name and as latest; main publishes the development version"):
        assert "mike deploy --push --update-aliases \"$RDM_DOCS_VERSION\" latest" in script
        assert "mike deploy --push dev" in script
        assert "github.ref_type == 'tag' && github.ref_name || 'dev'" in deploy["env"]["RDM_DOCS_VERSION"]
    with verification_step("published versions are kept (pushed to one branch, never replaced) and selectable; the "
                           "newest release is the default"):
        assert "--push" in script and "rm -rf" not in script and "gh-pages" in script
        assert "provider: mike" in nav and "default: latest" in nav
        assert "mike set-default --push latest" in script


@allure.tag("item-07-007")
def test_the_ifu_discloses_each_residual_risk() -> None:
    """item-07-007: the IFU's safety chapter names every risk of RDM's register, each
    with what the user must do about its residual risk."""
    from rdm.risk.register import risks

    _, titled = _ifu()
    safety = (ROOT / next(page for page, title in titled if title == "Limits and risks")).read_text()
    rows = {cells[1]: cells for cells in ([c.strip() for c in line.split("|")] for line in safety.splitlines()
                                          if line.startswith("| RISK-"))}
    ids = sorted(r.id for r in risks(ROOT / "dhf"))
    attach("register risks and disclosed rows", {"register": ids, "disclosed": sorted(rows)})

    with verification_step("every risk of the register is disclosed by its id, and no other"):
        assert ids and sorted(rows) == ids
    with verification_step("each disclosure says what the user must do"):
        assert all(len(cells) >= 6 and cells[-2] for cells in rows.values()), rows
