"""Acceptance test for the SHACL gate shapes (DI-38, see dhf/).

Tagged `@allure.story("DI-38")`, over the real projection, `shapes.ttl`,
`rdm graph validate`, and — for agreement — the real release gate. Skips
cleanly if allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from rdm.gates.design_gate import run_release_gate
from tests.util import git_run

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, clause  # noqa: E402
pytest.importorskip("pyshacl")

from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate, validate_command  # noqa: E402


def _dhf(tmp_path: Path, *, needs=("UN-1", "UN-2"), inputs=(("DI-1", "UN-1"), ("DI-2", "UN-2")),
         tagged=("DI-1", "DI-2"), extra_frontmatter="") -> Path:
    repo = tmp_path / "proj"
    docs = repo / "dhf" / "documents"
    (docs / "design").mkdir(parents=True)
    registry = "".join(f"  - {{id: {n}, text: '{n}'}}\n" for n in needs)
    (docs / "vv.md").write_text(f"---\nid: VVP-1\nuser_needs:\n{registry}---\n# Plan\n")
    rows = "".join(f"  - id: {di}\n    text: '{di} text'\n    traces_to: [{un}]\n" for di, un in inputs)
    (docs / "design" / "core.md").write_text(
        f"---\nid: SDS-1\nkind: design\ncontext: core\nsatisfies: [{', '.join(needs)}]\n"
        f"{extra_frontmatter}design_inputs:\n{rows}---\n# Core\n")
    (docs / "design_review.md").write_text("---\nid: DR-1\n---\n# Review\nApproved.\n")
    (repo / "tests").mkdir()
    (repo / "tests" / "test_core.py").write_text("import allure\n" + "".join(
        f'\n@allure.story("{di}")\ndef test_{i}():\n    assert True\n' for i, di in enumerate(tagged)))
    git_run(repo, "init")
    git_run(repo, "add", "-A")
    git_run(repo, "commit", "-m", "approve")
    return repo / "dhf"


def _results(tmp_path: Path, runs: dict[str, list[str]]) -> Path:
    out = tmp_path / "allure"
    out.mkdir(exist_ok=True)
    for di, statuses in runs.items():
        for n, status in enumerate(statuses):
            (out / f"{di}-{n}-result.json").write_text(json.dumps(
                {"name": f"{di}-{n}", "status": status, "labels": [{"name": "story", "value": di}]}))
    return out


def _blocked_by_gate(dhf: Path, results: Path) -> set[str]:
    blocking = run_release_gate(dhf, results).blocking
    return {m for msg in blocking for m in re.findall(r"\b(?:DI|UN)-\d+\b", msg)}


def _blocked_by_shapes(dhf: Path, results: Path) -> set[str]:
    return {r.label for r in validate(project(dhf, results)) if r.severity == "Violation"}


@allure.story("DI-38")
@allure.label("output", "rdm/graph/shapes.ttl")
def test_gate_shapes_agree_with_the_release_gate(tmp_path: Path) -> None:
    """DI-38: violations for unaddressed needs, unverified/failing inputs and
    unreferenced clauses; warnings for untagged inputs, undeclared references
    and stray tags; and the shapes block exactly what the release gate blocks."""
    # Agreement with the release gate, scenario by scenario.
    scenarios = {
        "all-pass": ({}, {"DI-1": ["passed"], "DI-2": ["passed"]}, set()),
        "failed": ({}, {"DI-1": ["passed", "failed"], "DI-2": ["passed"]}, {"DI-1"}),
        "broken": ({}, {"DI-1": ["passed", "broken"], "DI-2": ["passed"]}, {"DI-1"}),
        "untested": ({}, {"DI-1": ["passed"]}, {"DI-2"}),
        "skipped-only": ({}, {"DI-1": ["passed"], "DI-2": ["skipped"]}, {"DI-2"}),
        "unaddressed-need": ({"needs": ("UN-1", "UN-2", "UN-3")},
                             {"DI-1": ["passed"], "DI-2": ["passed"]}, {"UN-3"}),
    }
    for name, (shape, runs, expected) in scenarios.items():
        with clause(f"shapes block what the release gate blocks: {name}"):
            dhf = _dhf(tmp_path / name, **shape)
            results = _results(tmp_path / name, runs)
            by_shapes, by_gate = _blocked_by_shapes(dhf, results), _blocked_by_gate(dhf, results)
            attach("blocked", {"shapes": sorted(by_shapes), "release gate": sorted(by_gate)})
            assert by_shapes == by_gate == expected, name

    # Messages per rule (violations).
    dhf = _dhf(tmp_path / "msgs", needs=("UN-1", "UN-2", "UN-3"))
    results = _results(tmp_path / "msgs", {"DI-1": ["failed"]})
    found = {(r.severity, r.label, r.message) for r in validate(project(dhf, results))}
    assert ("Violation", "UN-3", "user need is addressed by no design input") in found
    assert ("Violation", "DI-1", "design input has a failed or broken test run") in found
    assert ("Violation", "DI-2", "design input is not verified by any passing test run") in found

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

    # Warnings: untagged input, undeclared references, a stray tag sharing the DI prefix.
    wdhf = _dhf(tmp_path / "warn", inputs=(("DI-1", "UN-1"), ("DI-2", "UN-9")), tagged=("DI-1", "DI-99", "US-1"),
                extra_frontmatter="realises: [DI-77]\n")
    (wdhf / "documents" / "design" / "core.md").write_text(
        (wdhf / "documents" / "design" / "core.md").read_text().replace("satisfies: [UN-1, UN-2]",
                                                                           "satisfies: [UN-1, UN-2, UN-8]"))
    warnings = {(r.label, r.message) for r in validate(project(wdhf)) if r.severity == "Warning"}
    assert ("DI-2", "design input has no tagged test file") in warnings
    assert ("DI-2", "design input traces to an undeclared user need") in warnings
    assert ("core", "context satisfies an undeclared user need") in warnings
    assert ("core", "context realises an undeclared design input") in warnings
    assert ("tests/test_core.py", "test tag DI-99 names no declared design input") in warnings
    assert not any("US-1" in message for _, message in warnings)  # unrelated prefix: noise, not reported
    assert not any(label == "DI-1" for label, _ in warnings)



@allure.story("DI-49")
@allure.label("output", "rdm/graph/validate.py")
def test_graph_validate_reports_and_exits_on_violations(tmp_path: Path, capsys) -> None:
    """DI-49: rdm graph validate prints each result with severity, focus and
    message, exits 1 on a violation and 0 otherwise, and runs user shape files."""
    dhf = _dhf(tmp_path / "msgs", needs=("UN-1", "UN-2", "UN-3"))
    results = _results(tmp_path / "msgs", {"DI-1": ["failed"]})

    # The command: per-result lines, exit 1 on a violation, 0 otherwise.
    ok = _dhf(tmp_path / "ok")
    ok_results = _results(tmp_path / "ok", {"DI-1": ["passed"], "DI-2": ["passed"]})
    capsys.readouterr()
    assert validate_command(dhf_dir=ok, allure_results_dir=ok_results) == 0
    assert "Graph validation PASSED" in capsys.readouterr().out
    assert validate_command(dhf_dir=dhf, allure_results_dir=results) == 1
    out = capsys.readouterr().out
    assert "[VIOLATION] UN-3: user need is addressed by no design input" in out

    # User-supplied shapes add rules as data.
    extra = tmp_path / "team.ttl"
    extra.write_text(
        "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix rdm: <https://github.com/scope-impact/rdm/ns#> .\n"
        "@prefix dcterms: <http://purl.org/dc/terms/> .\n"
        "[] a sh:NodeShape ; sh:targetClass rdm:DesignInput ; sh:property [ sh:path dcterms:title ; "
        "sh:minCount 1 ; sh:message \"design input needs a title\" ] .\n")
    assert validate_command(dhf_dir=ok, allure_results_dir=ok_results, extra_shapes=[extra]) == 1
    assert "[VIOLATION] DI-1: design input needs a title" in capsys.readouterr().out
    assert validate_command(dhf_dir=ok, extra_shapes=[tmp_path / "missing.ttl"]) == 2
