"""Acceptance tests for DI-46 (see dhf/): duplicate requirement ids, and the
declarations and frontmatter the record reader cannot read.

Tagged `@allure.story("DI-46")`, over the real design gate, release gate,
projection and shapes. Skips cleanly if allure-pytest is not installed; the
graph half skips without the `graph` extra.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from rdm.specification.design_gate import (
    CONTEXT_REPEATED,
    MALFORMED_DECLARATION,
    UNREADABLE_FRONTMATTER,
    run_design_gate,
)
from rdm.release.gate import run_release_gate
from tests.acceptance.test_graph_shapes import _dhf, _results
from tests.util import git_run as _git
from tests.util import write_design_doc

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.acceptance.test_user_needs import _approved_dhf  # noqa: E402

DESIGN = "documents/design/core.md"


def _ids_check(dhf: Path):
    return next(a for a in run_design_gate(dhf).artifacts if a.name == "Requirement ids")


@allure.story("DI-46")
@allure.label("output", "rdm/specification/design_gate.py")
def test_a_duplicated_id_fails_the_design_gate_and_shows_in_the_graph(tmp_path: Path) -> None:
    """DI-46: a user-need or design-input id declared twice — in one document
    or in several — fails the design gate naming every declaring document; a
    reference is not a declaration; the graph counts declarations and its
    shapes report a repeat."""
    with verification_step("unique ids pass: a reference (traces_to, realises) is not a declaration"):
        dhf = _dhf(tmp_path / "unique", extra_frontmatter="realises: [DI-1]\n")
        assert _ids_check(dhf).ok and run_design_gate(dhf).passed

    # A design input declared twice in one document: both declarations named.
    with verification_step("a design input declared twice in one document fails the design gate"):
        dhf = _dhf(tmp_path / "same-doc", inputs=(("DI-1", "UN-1"), ("DI-2", "UN-2"), ("DI-1", "UN-2")))
        check = _ids_check(dhf)
        attach("design gate reasons", check.reasons)
        assert not check.ok and not run_design_gate(dhf).passed
        assert check.reasons == [f"DI-1 is declared 2 times: {DESIGN}, {DESIGN}"]

    with verification_step("a design input declared in two documents fails, naming each"):
        dhf = _dhf(tmp_path / "two-docs")
        (dhf / "documents" / "design" / "other.md").write_text(
            "---\nid: SDS-2\nkind: design\ncontext: other\ndesign_inputs:\n"
            "  - {id: DI-2, text: 'again', traces_to: [UN-1]}\n---\n# Other\n")
        assert _ids_check(dhf).reasons == [f"DI-2 is declared 2 times: {DESIGN}, documents/design/other.md"]
    with verification_step("a user need declared in two documents fails, and the release gate blocks on it"):
        dhf = _dhf(tmp_path / "need")
        (dhf / "documents" / "plan2.md").write_text("---\nid: VVP-2\nuser_needs:\n  - {id: UN-2, text: 'twice'}\n"
                                                    "---\n")
        assert _ids_check(dhf).reasons == ["UN-2 is declared 2 times: documents/plan2.md, documents/vv.md"]
        results = _results(tmp_path / "need", {"DI-1": ["passed"], "DI-2": ["passed"]})
        blocking = run_release_gate(dhf, results).blocking
        assert any("Requirement ids" in m and "UN-2 is declared 2 times" in m for m in blocking)

    with verification_step("the graph counts each id's declarations and its shapes report the repeat the release "
                           "gate blocks"):
        pytest.importorskip("pyshacl")
        from rdm.graph.project import project
        from rdm.graph.validate import validate

        counts = {q.subject.value.rsplit("/", 1)[-1]: int(q.object.value) for q in project(dhf, results)
                  if q.predicate.value.endswith("#declarationCount")}
        assert counts == {"UN-1": 1, "UN-2": 2, "DI-1": 1, "DI-2": 1}
        flagged = {r.label for r in validate(project(dhf, results))
                   if r.message == "id is declared more than once in the record"}
        blocked = {m for msg in blocking for m in re.findall(r"\b(?:DI|UN)-\d+(?= is declared)", msg)}
        assert flagged == {"UN-2"} == blocked
        clean = _dhf(tmp_path / "clean")
        assert not [r for r in validate(project(clean, _results(tmp_path / "clean", {"DI-1": ["passed"],
                                                                                     "DI-2": ["passed"]})))
                    if r.message == "id is declared more than once in the record"]


@allure.story("DI-46")
@allure.label("output", "rdm/specification/design_gate.py")
@allure.label("output", "rdm/specification/sdd.py")
def test_what_the_record_reader_cannot_read_fails_the_design_gate(tmp_path: Path) -> None:
    """DI-46: a declaration the record reader cannot read, or a Markdown
    document of the DHF whose frontmatter cannot be read, fails the design
    gate naming the document; two design documents for one context warn."""
    dhf = _approved_dhf(tmp_path, ["UN-002"])
    with verification_step("a Markdown document whose frontmatter cannot be read fails the gate, named"):
        for name, block in (("not-yaml", "id: X\ntext: shall: alarm\n  bad"), ("not-a-mapping", "- a\n- b"),
                            ("unclosed", "id: X\n")):
            broken = dhf / "documents" / f"{name}.md"
            broken.write_text(f"---\n{block}\n" + ("" if name == "unclosed" else "---\n") + "\nbody\n")
            _git(dhf.parent, "add", "-A")
            _git(dhf.parent, "commit", "-m", name)
            gate = run_design_gate(dhf)
            attach(f"design gate on {name}", [str(e) for e in gate.events if e.blocking])
            assert not gate.passed and any(e.name == UNREADABLE_FRONTMATTER and name in e.message
                                           for e in gate.events)
            broken.unlink()
    _git(dhf.parent, "add", "-A")
    _git(dhf.parent, "commit", "-m", "tidy")

    def gate_on(name: str, text: str | bytes, where: str = "documents") -> list[str]:
        doc = dhf / where / f"{name}.md"
        doc.write_bytes(text) if isinstance(text, bytes) else doc.write_text(text)
        _git(dhf.parent, "add", "-A")
        _git(dhf.parent, "commit", "-m", name)
        gate = run_design_gate(dhf)
        found = [f"{e.name}: {e.message}" for e in gate.events if e.blocking]
        attach(f"design gate on {name}", found)
        doc.unlink()
        _git(dhf.parent, "add", "-A")
        _git(dhf.parent, "commit", "-m", f"drop {name}")
        return found

    with verification_step("a declaration the record reader cannot read fails the gate, naming its document"):
        entry = "kind: design\ncontext: m\ndesign_inputs:\n  - "
        for name, front in (
                ("mapping", "kind: design\ncontext: m\ndesign_inputs: {id: DI-9, text: x}"),
                ("string", "kind: design\ncontext: m\ndesign_inputs: 'DI-9 The system shall x'"),
                ("bare-ids", "kind: design\ncontext: m\ndesign_inputs: [DI-9]"),
                ("no-id", entry + "{text: x}"), ("wrong-key", entry + "{ID: DI-9, text: x}"),
                ("null-id", entry + "{id: null, text: x}"), ("list-id", entry + "{id: [DI-9], text: x}"),
                ("not-design", "id: X\ndesign_inputs:\n  - {id: DI-9, text: x}"),
                ("need-no-id", "id: X\nuser_needs:\n  - {text: x}"),
                ("need-blank", "id: X\nuser_needs:\n  - '  '")):
            found = gate_on(name, f"---\n{front}\n---\n\nbody\n", "documents/design")
            assert any(f.startswith(MALFORMED_DECLARATION) and f"{name}.md" in f for f in found), (name, found)
    with verification_step("a repeated frontmatter key, or a document that is not UTF-8, cannot be read"):
        twice = ("---\nkind: design\ncontext: t\ndesign_inputs:\n  - {id: DI-8, text: a, traces_to: [UN-002]}\n"
                 "design_inputs:\n  - {id: DI-9, text: b, traces_to: [UN-002]}\n---\n\nbody\n")
        assert any(f.startswith(UNREADABLE_FRONTMATTER) and "twice.md" in f
                   for f in gate_on("twice", twice, "documents/design"))
        latin = "---\nid: L\ntitle: caf\xe9\n---\n\nbody\n".encode("latin-1")
        assert any(f.startswith(UNREADABLE_FRONTMATTER) and "latin.md" in f for f in gate_on("latin", latin))
    with verification_step("two design documents for one context are a warning naming both"):
        design = dhf / "documents" / "design"
        write_design_doc(design, "core2", design_inputs=(("DI-6", ["UN-002"]),))
        (design / "core2.md").write_text((design / "core2.md").read_text().replace("context: core2", "context: core"))
        _git(dhf.parent, "add", "-A")
        _git(dhf.parent, "commit", "-m", "second core")
        warned = [e.message for e in run_design_gate(dhf).events if e.name == CONTEXT_REPEATED]
        attach("context warnings", warned)
        assert len(warned) == 1 and "core.md" in warned[0] and "core2.md" in warned[0]
