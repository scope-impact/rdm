"""Acceptance test for the SHACL gate shapes (item-06-004, item-06-010).

Tagged `@allure.tag`, over the real projection, `shapes.ttl`,
`rdm graph validate`, and — for agreement — the real release gate. Skips
cleanly if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from rdm.release.gate import run_release_gate
from tests.util import git_run, write_spec

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402
pytest.importorskip("pyshacl")

from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate, validate_command  # noqa: E402


def _dhf(tmp_path: Path, *, needs=("Need1", "Need2"),
         requirements=(("item-01-001", "Need1"), ("item-01-002", "Need2")), layout: str = "gherkin") -> Path:
    """A committed repository whose spec (context ``core``, slice 01) holds the
    user needs ``needs`` and the requirements ``requirements``, ``(item id, the need
    it addresses or None)``; and controlled documents. Returns its DHF. In
    plain Gherkin (the default) each need is a Feature, one with no
    requirement an empty one; ``layout="loyly"`` makes each an Allium surface."""
    repo = tmp_path / "proj"
    docs = repo / "dhf" / "documents"
    (docs / "design").mkdir(parents=True)
    write_spec(repo, tuple((ident, f"{ident} text", [need] if need else []) for ident, need in requirements),
               {n: ("core", "User", n) for n in needs}, {"01": "core"}, layout=layout)
    if layout == "gherkin":
        for need in set(needs) - {need for _, need in requirements}:
            (repo / "spec" / "core" / f"{need}.feature").write_text(f"Feature: {need}\n  {need}\n")
    (docs / "vv.md").write_text("---\nid: VVP-1\n---\n# Plan\n")
    (docs / "design" / "core.md").write_text("---\nid: SDS-1\n---\n# Core\n")
    (docs / "design_review.md").write_text("---\nid: DR-1\n---\n# Review\nApproved.\n")
    git_run(repo, "init")
    git_run(repo, "add", "-A")
    git_run(repo, "commit", "-m", "approve")
    return repo / "dhf"


def _results(tmp_path: Path, runs: dict[str, list[str]]) -> Path:
    """Allure results: for each requirement, one run per status, claiming it with a tag label."""
    out = tmp_path / "allure"
    out.mkdir(exist_ok=True)
    for ident, statuses in runs.items():
        for n, status in enumerate(statuses):
            (out / f"{ident}-{n}-result.json").write_text(json.dumps(
                {"name": f"{ident}-{n}", "status": status, "labels": [{"name": "tag", "value": ident}]}))
    return out


_IDS = re.compile(r"\bitem-\d+-\d+\b|\bNeed\d+\b")


def _blocked_by_gate(dhf: Path, results: Path) -> set[str]:
    blocking = run_release_gate(dhf, results).blocking
    return {m for msg in blocking for m in _IDS.findall(msg)}


def _blocked_by_shapes(dhf: Path, results: Path) -> set[str]:
    return {r.label for r in validate(project(dhf, results)) if r.severity == "Violation"}


@allure.tag("item-06-004")
@allure.label("output", "rdm/graph/shapes.ttl")
def test_gate_shapes_agree_with_the_release_gate(tmp_path: Path) -> None:
    """item-06-004: violations for unaddressed user needs, requirements without
    a passing run or with a failing one, and unreferenced clauses; warnings for
    a requirement addressing no need and a run claiming no requirement; and the
    shapes block exactly what the release gate blocks."""
    scenarios = {
        "all-pass": ({}, {"item-01-001": ["passed"], "item-01-002": ["passed"]}, set()),
        "failed": ({}, {"item-01-001": ["passed", "failed"], "item-01-002": ["passed"]}, {"item-01-001"}),
        "broken": ({}, {"item-01-001": ["passed", "broken"], "item-01-002": ["passed"]}, {"item-01-001"}),
        "untested": ({}, {"item-01-001": ["passed"]}, {"item-01-002"}),
        "skipped-only": ({}, {"item-01-001": ["passed"], "item-01-002": ["skipped"]}, {"item-01-002"}),
        "unaddressed-need": ({"needs": ("Need1", "Need2", "Need3")},
                             {"item-01-001": ["passed"], "item-01-002": ["passed"]}, {"Need3"}),
    }
    for name, (shape, runs, expected) in scenarios.items():
        with verification_step(f"shapes block what the release gate blocks: {name}"):
            dhf = _dhf(tmp_path / name, **shape)
            results = _results(tmp_path / name, runs)
            by_shapes, by_gate = _blocked_by_shapes(dhf, results), _blocked_by_gate(dhf, results)
            attach("blocked", {"shapes": sorted(by_shapes), "release gate": sorted(by_gate)})
            assert by_shapes == by_gate == expected, name

    with verification_step("Each violation rule reports its focus node and message"):
        dhf = _dhf(tmp_path / "msgs", needs=("Need1", "Need2", "Need3"))
        results = _results(tmp_path / "msgs", {"item-01-001": ["failed"]})
        found = {(r.severity, r.label, r.message) for r in validate(project(dhf, results))}
        assert ("Violation", "Need3", "user need is addressed by no requirement") in found
        assert ("Violation", "item-01-001", "requirement has a failed or broken test run") in found
        assert ("Violation", "item-01-002", "requirement is not verified by any passing test run") in found

    with verification_step("An unreferenced clause and a clause without a key or a standard are violations"):
        # Unreferenced checklist clauses are violations; checklist data is checked too.
        lists = tmp_path / "lists"
        lists.mkdir()
        (lists / "mini.txt").write_text("STD:1 a clause no document references\n")
        (lists / "bad.ttl").write_text(
            "@prefix rdm: <https://github.com/scope-impact/rdm/ns#> .\n"
            "<urn:rdm:clause:BAD:1> a rdm:Clause .\n")  # no key, no standard (labelled from its IRI)
        quads = project(dhf, results, checklists=[str(lists / "mini.txt"), str(lists / "bad.ttl")])
        found = {(r.severity, r.label, r.message) for r in validate(quads)}
        assert ("Violation", "STD:1", "checklist clause is referenced by no document") in found
        assert ("Violation", "BAD:1", "clause needs exactly one key (skos:notation)") in found
        assert ("Violation", "BAD:1",
                "clause needs a standard (skos:inScheme a skos:ConceptScheme)") in found

    with verification_step("A requirement addressing no user need, and a run claiming no requirement, are warnings"):
        # Only a needs adapter can leave a requirement addressing no need: under plain Gherkin each sits
        # under a Feature.
        wdhf = _dhf(tmp_path / "warn", needs=(), requirements=(("item-01-001", None),), layout="loyly")
        wresults = _results(tmp_path / "warn", {"item-01-001": ["passed"], "item-09-001": ["passed"]})
        warnings = {(r.label, r.message) for r in validate(project(wdhf, wresults)) if r.severity == "Warning"}
        assert ("item-01-001", "requirement addresses no user need") in warnings
        assert ("item-09-001-0", "test run claims item-09-001, which is not a requirement of the spec") in warnings

    def commit(dhf: Path) -> None:
        git_run(dhf.parent, "add", "-A")
        git_run(dhf.parent, "commit", "-qm", "change")

    def no_inputs(dhf):
        for feature in (dhf.parent / "spec" / "core").glob("*.feature"):
            feature.write_text(f"Feature: {feature.stem}\n")
        commit(dhf)

    def unstamped(dhf):
        feature = dhf.parent / "spec" / "core" / "Need1.feature"
        feature.write_text(feature.read_text() + "\n  Scenario: nobody stamped me\n")
        commit(dhf)

    def stamped_twice(dhf):
        feature = dhf.parent / "spec" / "core" / "Need1.feature"
        feature.write_text(feature.read_text() + "\n  @item-01-001\n  Scenario: a second claim on one id\n")
        commit(dhf)

    def unreadable_result(dhf):
        (dhf.parents[1] / "allure" / "cut-result.json").write_text('{"status": "failed", "labels": [')

    for change in (no_inputs, unstamped, stamped_twice, unreadable_result):
        with verification_step(f"what the release gate blocks about the whole record, the shapes block: "
                               f"{change.__name__.replace('_', ' ')}"):
            case = tmp_path / change.__name__
            dhf = _dhf(case)
            results = _results(case, {"item-01-001": ["passed"], "item-01-002": ["passed"]})
            change(dhf)
            gate = {m for m in run_release_gate(dhf, results).blocking
                    if not re.match(r"(requirement|user need) ", m)}
            shapes = {r.message for r in validate(project(dhf, results))
                      if r.severity == "Violation" and r.focus.endswith(":record")}
            attach("blocked", {"release gate": sorted(gate), "shapes": sorted(shapes)})
            assert gate and shapes == gate, (change.__name__, shapes, gate)

    with verification_step("the executions graph reads results as the gates do: a byte-order mark, a failed step"):
        case = tmp_path / "reader"
        dhf = _dhf(case)
        results = _results(case, {"item-01-001": ["passed"]})
        (results / "bom-result.json").write_text("\ufeff" + json.dumps(
            {"name": "bom", "status": "failed", "labels": [{"name": "tag", "value": "item-01-001"}]}))
        (results / "step-result.json").write_text(json.dumps(
            {"name": "step", "status": "passed", "labels": [{"name": "tag", "value": "item-01-002"}],
             "steps": [{"name": "a check", "status": "failed"}]}))
        by_shapes, by_gate = _blocked_by_shapes(dhf, results), _blocked_by_gate(dhf, results)
        assert by_shapes == by_gate == {"item-01-001", "item-01-002"}, (by_shapes, by_gate)


@allure.tag("item-06-010")
@allure.label("output", "rdm/graph/validate.py")
def test_graph_validate_reports_and_exits_on_violations(tmp_path: Path, capsys) -> None:
    """item-06-010: rdm graph validate prints each result with severity, focus and
    message, exits 1 on a violation and 0 otherwise, and runs user shape files."""
    dhf = _dhf(tmp_path / "msgs", needs=("Need1", "Need2", "Need3"))
    results = _results(tmp_path / "msgs", {"item-01-001": ["failed"]})

    with verification_step("The command: per-result lines, exit 1 on a violation, 0 otherwise"):
        ok = _dhf(tmp_path / "ok")
        ok_results = _results(tmp_path / "ok", {"item-01-001": ["passed"], "item-01-002": ["passed"]})
        capsys.readouterr()
        assert validate_command(dhf_dir=ok, allure_results_dir=ok_results) == 0
        assert "Graph validation PASSED" in capsys.readouterr().out
        assert validate_command(dhf_dir=dhf, allure_results_dir=results) == 1
        out = capsys.readouterr().out
        assert "[VIOLATION] Need3: user need is addressed by no requirement" in out

    with verification_step("User-supplied shapes add rules as data"):
        extra = tmp_path / "team.ttl"
        extra.write_text(
            "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix rdm: <https://github.com/scope-impact/rdm/ns#> .\n"
            "@prefix dcterms: <http://purl.org/dc/terms/> .\n"
            "[] a sh:NodeShape ; sh:targetClass rdm:Requirement ; sh:property [ sh:path dcterms:title ; "
            "sh:minCount 1 ; sh:message \"requirement needs a title\" ] .\n")
        assert validate_command(dhf_dir=ok, allure_results_dir=ok_results, extra_shapes=[extra]) == 1
        assert "[VIOLATION] item-01-001: requirement needs a title" in capsys.readouterr().out
        assert validate_command(dhf_dir=ok, extra_shapes=[tmp_path / "missing.ttl"]) == 2

    with verification_step("A shapes or checklist file that is not RDF is an error (exit 2), never a traceback"):
        bad = tmp_path / "bad.ttl"
        bad.write_text("this is { not turtle\n")
        bad_nt = tmp_path / "bad.nt"
        bad_nt.write_text("<urn:a> <urn:b> .\n")
        capsys.readouterr()
        assert validate_command(dhf_dir=ok, extra_shapes=[bad]) == 2
        assert "bad.ttl" in capsys.readouterr().out
        assert validate_command(dhf_dir=ok, checklists=[str(bad_nt)]) == 2
        assert "bad.nt" in capsys.readouterr().out
