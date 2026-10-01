"""Acceptance test for duplicate requirement ids (DI-46, see dhf/).

Tagged `@allure.story("DI-46")`, over the real design gate, release gate,
projection and shapes. Skips cleanly if allure-pytest is not installed; the
graph half skips without the `graph` extra.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from rdm.gates.design_gate import run_design_gate, run_release_gate
from tests.acceptance.test_graph_shapes import _dhf, _results

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, clause  # noqa: E402

DESIGN = "documents/design/core.md"


def _ids_check(dhf: Path):
    return next(a for a in run_design_gate(dhf).artifacts if a.name == "Requirement ids")


@allure.story("DI-46")
@allure.label("output", "rdm/gates/design_gate.py")
def test_a_duplicated_id_fails_the_design_gate_and_shows_in_the_graph(tmp_path: Path) -> None:
    """DI-46: a user-need or design-input id declared twice — in one document
    or in several — fails the design gate naming every declaring document; a
    reference is not a declaration; the graph counts declarations and its
    shapes report a repeat."""
    # Unique ids: the check passes; references (traces_to, realises) do not count.
    dhf = _dhf(tmp_path / "unique", extra_frontmatter="realises: [DI-1]\n")
    assert _ids_check(dhf).ok and run_design_gate(dhf).passed

    # A design input declared twice in one document: both declarations named.
    with clause("a design input declared twice in one document fails the design gate"):
        dhf = _dhf(tmp_path / "same-doc", inputs=(("DI-1", "UN-1"), ("DI-2", "UN-2"), ("DI-1", "UN-2")))
        check = _ids_check(dhf)
        attach("design gate reasons", check.reasons)
        assert not check.ok and not run_design_gate(dhf).passed
        assert check.reasons == [f"DI-1 is declared 2 times: {DESIGN}, {DESIGN}"]

    # A design input declared in two documents: each named.
    dhf = _dhf(tmp_path / "two-docs")
    (dhf / "documents" / "design" / "other.md").write_text(
        "---\nid: SDS-2\nkind: design\ncontext: other\ndesign_inputs:\n"
        "  - {id: DI-2, text: 'again', traces_to: [UN-1]}\n---\n# Other\n")
    assert _ids_check(dhf).reasons == [f"DI-2 is declared 2 times: {DESIGN}, documents/design/other.md"]

    # A user need registered in two documents.
    dhf = _dhf(tmp_path / "need")
    (dhf / "documents" / "plan2.md").write_text("---\nid: VVP-2\nuser_needs:\n  - {id: UN-2, text: 'twice'}\n---\n")
    assert _ids_check(dhf).reasons == ["UN-2 is declared 2 times: documents/plan2.md, documents/vv.md"]

    # The release gate blocks on it, through the design gate.
    results = _results(tmp_path / "need", {"DI-1": ["passed"], "DI-2": ["passed"]})
    blocking = run_release_gate(dhf, results).blocking
    assert any("Requirement ids" in m and "UN-2 is declared 2 times" in m for m in blocking)

    # The graph: each id's declaration count, and a violation for the repeat —
    # the same ids the release gate blocks.
    pytest.importorskip("pyshacl")
    from rdm.graph.project import project
    from rdm.graph.validate import validate

    counts = {q.subject.value.rsplit("/", 1)[-1]: int(q.object.value) for q in project(dhf, results)
              if q.predicate.value.endswith("#declarationCount")}
    assert counts == {"UN-1": 1, "UN-2": 2, "DI-1": 1, "DI-2": 1}
    flagged = {r.label for r in validate(project(dhf, results))
               if r.message == "id is declared more than once in the record"}
    assert flagged == {"UN-2"} == {m for msg in blocking for m in re.findall(r"\b(?:DI|UN)-\d+(?= is declared)", msg)}
    clean = _dhf(tmp_path / "clean")
    assert not [r for r in validate(project(clean, _results(tmp_path / "clean", {"DI-1": ["passed"],
                                                                                 "DI-2": ["passed"]})))
                if r.message == "id is declared more than once in the record"]
