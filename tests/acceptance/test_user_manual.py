"""Acceptance tests for the user manual in the graph (DI-77, DI-78) and for
RDM's own manual (DI-79), see dhf/.

Tagged `@allure.story`, over the real projection, gate shapes and agent
trace. Skips cleanly if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
ox = pytest.importorskip("pyoxigraph")

from rdm.graph.agent import Record, trace  # noqa: E402
from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_graph import _ask, _record, _store  # noqa: E402

ROOT = Path(__file__).parents[2]
NO_COMPONENT = "writes no component label (DI-78)"
MISSING = "the user manual lists a page that does not exist"

EXAMPLE = '''```python
@allure.story("DI-1")
@allure.label("component", "alarms")
def test_alarm(): ...
```
'''
FILE_PATH_EXAMPLE = '''```python
@allure.story("DI-2")
@allure.label("output", "src/log.py")
def test_log(): ...
```
'''
DIAGRAM = '''```
| Acceptance test @allure.story("DI-n") |
```
'''


def _manual(tmp_path: Path) -> tuple[Path, Path]:
    """The acme record with a manual of three listed pages, one missing: a
    guide naming DI-1 (not DI-12) with a component example and a diagram, that
    includes a file whole (naming DI-2, with a file-path example) and a
    section of another (naming nothing it reads)."""
    dhf, results = _record(tmp_path)
    repo = dhf.parent
    (repo / "docs").mkdir()
    (repo / "docs" / "guide.md").write_text(
        "# Guide\n\nDI-1 alarms; DI-12 is not declared.\n\n" + EXAMPLE + DIAGRAM
        + '--8<-- "docs/included.md"\n--8<-- "docs/sectioned.md:part"\n')
    (repo / "docs" / "included.md").write_text("DI-2 logs.\n\n" + FILE_PATH_EXAMPLE)
    (repo / "docs" / "sectioned.md").write_text("<!-- --8<-- [start:part] -->\nDI-1\n<!-- --8<-- [end:part] -->\n")
    (repo / "docs" / "plain.md").write_text("# No examples\n")
    (dhf / "documents" / "manual.md").write_text(
        "---\nid: IFU-1\nkind: manual\nrevision: 1\ntitle: Manual\n"
        "pages: [docs/guide.md, docs/plain.md, docs/gone.md]\n---\n# Manual\n")
    return dhf, results


@allure.story("DI-77")
@allure.label("component", "manual_reader")
@allure.label("component", "agent_server")
def test_the_manual_is_projected_into_the_graph(tmp_path: Path) -> None:
    """DI-77: the pages a manual document lists, with the files they include
    whole, linked to it; the design inputs each names; each tagged-test example
    with its label names; a warning for a missing page; the pages in a design
    input's trace."""
    dhf, results = _manual(tmp_path)
    quads = project(dhf, results)
    store = _store(quads)
    page = "<urn:dhf:acme:page/docs/{}>".format
    attach("manual graph", sorted(str(q) for q in quads if q.graph_name.value.endswith("graph/manual")))

    with verification_step("each page the manual document lists is linked to that document; an unlisted page is not"):
        assert _ask(store, f"{page('guide.md')} a rdm:ManualPage ; rdm:pageOf <urn:dhf:acme:doc/IFU-1> ; "
                           "rdm:path 'docs/guide.md'")
        assert _ask(store, f"{page('plain.md')} rdm:pageOf <urn:dhf:acme:doc/IFU-1>")
        assert not _ask(store, f"{page('included.md')} a rdm:ManualPage")
    with verification_step("a file the page includes whole is read as the page's; a sectioned include is not"):
        assert _ask(store, f"{page('guide.md')} rdm:includesFile 'docs/included.md'")
        assert not _ask(store, f"{page('guide.md')} rdm:includesFile ?f . FILTER(CONTAINS(?f, 'sectioned'))")
    with verification_step("the page links to each declared design input it names by its id, and to no other"):
        named = {r["i"].value for r in store.query(
            "PREFIX rdm: <https://github.com/scope-impact/rdm/ns#> "
            f"SELECT ?i WHERE {{ {page('guide.md')} rdm:namesInput ?i }}", use_default_graph_as_union=True)}
        assert named == {"urn:dhf:acme:input/DI-1", "urn:dhf:acme:input/DI-2"}  # DI-2 from the included file
        assert not _ask(store, f"{page('plain.md')} rdm:namesInput ?i")
    with verification_step("each tagged-test example is projected with the names of the labels it writes; a fenced "
                           "block whose story text starts no line is no example"):
        examples = {r["e"].value: sorted(r["l"].value.split()) for r in store.query(
            "PREFIX rdm: <https://github.com/scope-impact/rdm/ns#> "
            f"SELECT ?e (GROUP_CONCAT(?n) AS ?l) WHERE {{ ?e rdm:onPage {page('guide.md')} ; rdm:writesLabel ?n }} "
            "GROUP BY ?e", use_default_graph_as_union=True)}
        assert sorted(examples.values()) == [["component", "story"], ["output", "story"]]
    with verification_step("a listed page that does not exist is a warning"):
        missing = [r for r in validate(quads) if r.message.startswith(MISSING)]
        assert [(r.severity, r.label) for r in missing] == [("Warning", "IFU-1")]
        assert "docs/gone.md" in missing[0].message
    with verification_step("the agent server's trace lists, for a design input, the manual pages that name it"):
        assert trace(Record(dhf, results), "DI-1")["design_input"]["manual_pages"] == ["docs/guide.md"]
        assert trace(Record(dhf, results), "DI-2")["design_input"]["manual_pages"] == ["docs/guide.md"]


@allure.story("DI-78")
@allure.label("component", "gate_shapes")
@allure.label("component", "manual_reader")
def test_an_example_without_a_component_label_is_a_warning(tmp_path: Path) -> None:
    """DI-78: a tagged-test example in the manual that writes no component
    label is a warning; one that writes it is not."""
    dhf, results = _manual(tmp_path)
    report = validate(project(dhf, results))
    flagged = [r for r in report if r.message.endswith(NO_COMPONENT)]
    attach("flagged examples", [(r.severity, r.label, r.message) for r in flagged])

    with verification_step("the example writing only an output label is warned on, the one with a component label "
                           "is not"):
        assert [(r.severity, r.label) for r in flagged] == [("Warning", "docs/guide.md example 2")]
        assert "docs/guide.md" in flagged[0].message


@allure.story("DI-79")
@allure.label("component", "user_manual")
def test_rdm_manual_examples_name_their_component() -> None:
    """DI-79: every tagged-test example in RDM's own user manual names the C4
    component it exercises by a component label."""
    quads = project(ROOT / "dhf")
    store = ox.Store()
    store.extend(quads)
    examples = [(r["e"].value, sorted(r["l"].value.split())) for r in store.query(
        "PREFIX rdm: <https://github.com/scope-impact/rdm/ns#> "
        "SELECT ?e (GROUP_CONCAT(?n) AS ?l) WHERE { ?e a rdm:TestExample ; rdm:writesLabel ?n } GROUP BY ?e",
        use_default_graph_as_union=True)]
    attach("RDM's manual examples", examples)

    with verification_step("RDM's manual shows tagged-test examples, and each writes a component label"):
        assert examples, "the manual shows no tagged-test example to check"
        assert [e for e, labels in examples if "component" not in labels] == []
    with verification_step("the gate shapes raise no example warning on RDM's manual"):
        assert [r.label for r in validate(quads) if r.message.endswith(NO_COMPONENT)] == []


CHAPTERS = ["RDM — Instructions for use", "Intended use", "How RDM works", "Safety and limitations", "Installation",
            "Getting started", "Operating instructions", "Troubleshooting", "Reference", "Revision history"]


def _ifu() -> tuple[dict, list[tuple[str, str]]]:
    """RDM's IFU-001: its frontmatter, and each listed page with its title (the
    first level-one heading of the page and the files it includes whole)."""
    from rdm.graph.manual import manual_pages, page_text
    from rdm.graph.project import controlled_documents

    front = next(e["front"] for e in controlled_documents(ROOT / "dhf", ROOT) if e["id"] == "IFU-001")
    titled = []
    for page in manual_pages(front):
        text, _ = page_text(ROOT, page)
        title = next((line[2:].strip() for line in text.splitlines() if line.startswith("# ")), "")
        titled.append((page, title))
    return front, titled


@allure.story("DI-80")
@allure.label("component", "user_manual")
def test_the_ifu_is_one_manual_in_reading_order() -> None:
    """DI-80: IFU-001 lists RDM's manual in the reading order of instructions
    for use, behind a cover naming the product, its release and the manual's
    id and revision."""
    from rdm.kernel.version import release_version

    front, titled = _ifu()
    attach("IFU-001 pages and titles", titled)
    titles = [title for _, title in titled]

    with verification_step("the chapters appear in the order of instructions for use"):
        found = [t for t in titles if t in CHAPTERS]
        assert found == CHAPTERS, found
    with verification_step("the first page is the cover, naming the product, the release, and the manual's id and "
                           "revision"):
        cover = (ROOT / titled[0][0]).read_text()
        assert titles[0] == CHAPTERS[0]
        assert f"v{release_version()}" in cover
        assert f"IFU-001, revision {front['revision']}" in cover


@allure.story("DI-81")
@allure.label("component", "user_manual")
def test_the_ifu_discloses_each_residual_risk() -> None:
    """DI-81: the IFU's safety chapter names every risk of RDM's register, each
    with what the user must do about its residual risk."""
    from rdm.risk.register import risks

    _, titled = _ifu()
    safety = (ROOT / next(page for page, title in titled if title == "Safety and limitations")).read_text()
    rows = {cells[1]: cells for cells in ([c.strip() for c in line.split("|")] for line in safety.splitlines()
                                          if line.startswith("| RISK-"))}
    ids = sorted(r.id for r in risks(ROOT / "dhf"))
    attach("register risks and disclosed rows", {"register": ids, "disclosed": sorted(rows)})

    with verification_step("every risk of the register is disclosed by its id, and no other"):
        assert ids and sorted(rows) == ids
    with verification_step("each disclosure says what the user must do"):
        assert all(len(cells) >= 6 and cells[-2] for cells in rows.values()), rows
