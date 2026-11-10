"""Acceptance tests for the release gate and verification over the spec's
requirements (see spec/release/).

Each test claims the requirement it verifies with `@allure.tag("item-NN-SSS")`;
its verification steps check what the requirement's text states. The spec of
each fixture is a small Löyly-shaped one (`tests.util.write_spec`): stamped
scenarios are the requirements, Allium surfaces the user needs.

Run with Allure to produce the verification evidence the release gate consumes:

    uv run pytest tests/acceptance --alluredir=dhf/allure-results
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.evidence import allure as allure_ingest
from rdm.main import cli
from rdm.release.gate import (
    NO_REQUIREMENTS,
    NO_SPEC,
    REQUIREMENT_FAILED,
    REQUIREMENT_UNTESTED,
    STAMPED_TWICE,
    UNREADABLE_SPEC,
    UNSTAMPED,
    run_release_gate,
)
from rdm.release.verify import verify_command
from rdm.specification import sdd as specification_module
from tests.util import git_run as _git
from tests.util import spec_project
from tests.util import write_allure_result as _allure_result

# Tagging requires allure-pytest; skip cleanly if it is not installed.
allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

NEEDS = {"Release": ("core", "Team", "A release is blocked until every requirement is verified."),
         "Trace": ("core", "Reviewer", "Each requirement's status is traceable from the results.")}


def _approved_dhf(tmp_path: Path, needs: list[str], items: list[tuple[str, list[str]]] | None = None) -> Path:
    """A committed repository with a DHF and a spec of context ``core``
    (slice 01). By default one requirement covers each named need; ``needs``
    are the user needs of the spec (each a Feature)."""
    if items is None:
        items = [(f"item-01-{i + 1:03d}", [n]) for i, n in enumerate(needs)]
    declared = {n: NEEDS.get(n, ("core", "User", f"The need {n}.")) for n in needs}
    return spec_project(tmp_path / "repo", tuple((i, f"requirement {i}", covers) for i, covers in items),
                        declared, {"01": "core"})


def _blocking(gate) -> list[str]:
    return [e.name for e in gate.events if e.blocking]


@allure.tag("item-04-001")
@allure.label("output", "rdm/release/gate.py")
def test_release_gate_blocks_until_verified(tmp_path: Path) -> None:
    """item-04-001: release is blocked unless the spec is found and read
    whole, holds at least one requirement, and every requirement is verified
    by a passing test."""
    dhf = _approved_dhf(tmp_path, ["Release"], [("item-01-001", ["Release"]), ("item-01-002", ["Release"])])
    empty = tmp_path / "none"
    empty.mkdir()
    with verification_step("with no results, every requirement is untested and release is blocked"):
        gate = run_release_gate(dhf, empty)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed and set(_blocking(gate)) == {REQUIREMENT_UNTESTED}
    results = tmp_path / "allure"
    with verification_step("one requirement verified and another untested: release is blocked, naming the "
                           "untested one"):
        _allure_result(results, "a", "passed", "item-01-001")
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed and any("item-01-002" in m for m in gate.blocking) \
            and not any("item-01-001" in m for m in gate.blocking), gate.blocking
    with verification_step("a failing requirement blocks release"):
        _allure_result(results, "b", "failed", "item-01-002")
        gate = run_release_gate(dhf, results)
        assert not gate.passed and _blocking(gate) == [REQUIREMENT_FAILED], gate.blocking
    with verification_step("a requirement with a passing and a failing run is not verified: release is blocked"):
        _allure_result(results, "b", "passed", "item-01-002")
        _allure_result(results, "c", "failed", "item-01-001")
        gate = run_release_gate(dhf, results)
        assert not gate.passed and any("item-01-001" in m for m in gate.blocking), gate.blocking
    with verification_step("every requirement verified by a passing test: release passes"):
        (results / "c-result.json").unlink()
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert gate.passed, gate.blocking
    feature = dhf.parent / "spec" / "core" / "Release.feature"
    passing = feature.read_text()
    with verification_step("a scenario with no @item id blocks: the spec is not read whole"):
        feature.write_text(passing + "\n  Scenario: an unstamped behaviour\n")
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert _blocking(gate) == [UNSTAMPED] and "an unstamped behaviour" in gate.blocking[0]
    with verification_step("an item stamped on two scenarios blocks"):
        feature.write_text(passing + "\n  @item-01-001\n  Scenario: a second claim on one id\n")
        gate = run_release_gate(dhf, results)
        assert _blocking(gate) == [STAMPED_TWICE] and "item-01-001" in gate.blocking[0], gate.blocking
    with verification_step("a spec file that cannot be read blocks"):
        feature.write_text(passing)
        broken = feature.parent / "broken.feature"
        broken.write_text("@a-tag-and-no-feature\n")  # Gherkin's own parser refuses it
        gate = run_release_gate(dhf, results)
        assert _blocking(gate) == [UNREADABLE_SPEC] and "broken.feature" in gate.blocking[0], gate.blocking
        broken.unlink()
    with verification_step("a spec with no stamped scenario blocks: nothing to verify"):
        feature.write_text("Feature: Release\n")
        gate = run_release_gate(dhf, results)
        assert _blocking(gate) == [NO_REQUIREMENTS], gate.blocking
    with verification_step("no spec found blocks"):
        lone = tmp_path / "lone"
        (lone / "dhf").mkdir(parents=True)
        _git(lone, "init", "-q")
        gate = run_release_gate(lone / "dhf", results)
        attach("release gate blocking", gate.blocking)
        assert _blocking(gate) == [NO_SPEC], gate.blocking


@allure.tag("item-04-002")
@allure.label("output", "rdm/evidence/allure.py")
@allure.label("output", "rdm/release/verify.py")
@allure.label("output", "rdm/publishing/render.py")
def test_verification_status_traceable_from_results(tmp_path: Path, monkeypatch, capsys) -> None:
    """item-04-002: each requirement reconciled against the item tags of the
    executed results as verified, failed or untested, and the traceability
    matrix of those statuses rendered under the user needs they address."""
    results = tmp_path / "allure"
    for name, status, item in (("p", "passed", "item-01-001"), ("f", "failed", "item-01-002"),
                               ("s", "skipped", "item-01-003"), ("b", "broken", "item-01-004")):
        _allure_result(results, name, status, item)
    ids = {f"item-01-00{n}" for n in range(1, 6)}
    with verification_step("a passed run verifies; a failed or broken one fails; a skipped run, or none, leaves "
                           "a requirement untested"):
        report = allure_ingest.reconcile(ids, results)
        attach("statuses", {i: v.status for i, v in sorted(report.by_id.items())})
        assert report.verified == ["item-01-001"]
        assert report.failed == ["item-01-002", "item-01-004"]
        assert report.untested == ["item-01-003", "item-01-005"]
    with verification_step("only an Allure tag label names a requirement: a story, feature or epic naming one "
                           "does not; an @ before the id is read"):
        (results / "e-result.json").write_text(json.dumps({"name": "e", "status": "failed", "labels": [
            {"name": "story", "value": "item-01-001"}, {"name": "feature", "value": "item-01-001"},
            {"name": "epic", "value": "item-01-001"}]}))
        assert allure_ingest.reconcile({"item-01-001"}, results).verified == ["item-01-001"]
        (results / "e-result.json").write_text(json.dumps({"name": "e", "status": "passed", "labels": [
            {"name": "tag", "value": "@item-01-005"}]}))
        assert "item-01-005" in allure_ingest.reconcile(ids, results).verified
        (results / "e-result.json").unlink()
    with verification_step("the rendered traceability matrix shows each requirement's status under the user need "
                           "it addresses"):
        dhf = _approved_dhf(tmp_path, ["Release", "Trace"], [
            ("item-01-001", ["Release"]), ("item-01-002", ["Release"]), ("item-01-003", ["Release"]),
            ("item-01-004", ["Trace"]), ("item-01-005", ["Trace"])])
        assert verify_command(dhf, results, tmp_path / "verification.yml") == 0
        template = Path(specification_module.__file__).parent / "init_files" / "documents" / "traceability_matrix.md"
        (tmp_path / "matrix.md").write_text(template.read_text())
        (tmp_path / "config.yml").write_text("")
        (tmp_path / "device.yml").write_text("name: Acme Monitor\nversion: '1.0'\n")  # the scaffold's device data
        monkeypatch.chdir(tmp_path)
        capsys.readouterr()
        assert cli(["render", "matrix.md", "config.yml", "device.yml", "verification.yml"]) == 0, \
            capsys.readouterr().err
        matrix = capsys.readouterr().out
        attach("rendered matrix", matrix)
        rows = {}
        need = None
        for line in matrix.splitlines():
            if line.startswith("## "):
                need = line[3:].strip()
            elif line.startswith("| item-"):
                cells = [c.strip() for c in line.strip("|").split("|")]
                rows[cells[0]] = (need, cells[2])
        assert rows == {"item-01-001": ("Release", "verified"), "item-01-002": ("Release", "failed"),
                        "item-01-003": ("Release", "untested"), "item-01-004": ("Trace", "failed"),
                        "item-01-005": ("Trace", "untested")}, rows
        assert "A release is blocked until every requirement is verified." in matrix
