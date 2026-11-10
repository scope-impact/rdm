"""Acceptance tests for RDM's own design inputs (see dhf/).

Each test verifies a design input, the acceptance criterion ("live BDD"): its
verification steps check the clauses, the `@allure.story` tag (DI-n) is the
traceability link to the design input it verifies, and the optional `@allure.label("output", "...")` records the design
output exercised. There is no Gherkin / feature file / step glue — the reviewed
spec lives in the registries (the per-context design docs, the V&V plan) and the living doc
is the Allure report. An `allure.step(...)` narrative is available but optional
and free-form (it need not be Given/When/Then).

Run with Allure to produce the verification evidence the release gate consumes:

    uv run pytest tests/acceptance --alluredir=dhf/allure-results
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results

Verification is anchored on design inputs (§820.30(f): output meets input);
validation against user needs stays UN-keyed (validation records).
Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

from rdm.evidence import allure as allure_ingest
from rdm.main import cli
from rdm.release.verify import verify_command
from rdm.specification import sdd as specification_module
from rdm.specification.sdd import design_input_ids, design_inputs, user_need_texts
from rdm.release.gate import run_release_gate
from tests.util import COMPLETE_DOC as COMPLETE
from tests.util import git_run as _git
from tests.util import write_allure_result as _allure_result
from tests.util import write_design_doc

# Tagging requires allure-pytest; skip cleanly if it is not installed.
allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402


def _vv_plan(docs: Path, needs: list[str]) -> None:
    items = "\n".join(f"  - {{id: {n}, text: {n}}}" for n in needs)
    (docs / "verification_and_validation_plan.md").write_text(
        f"---\nid: VVP-001\nuser_needs:\n{items}\n---\n\nplan\n"
    )


def _approved_dhf(
    tmp_path: Path,
    needs: list[str],
    inputs: list[tuple[str, list[str]]] | None = None,
) -> Path:
    """A committed DHF: an approved per-context design doc (carrying the design
    inputs + output), a design review, and a V&V registry. By default one design
    input is declared per user need.
    """
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    _git(repo, "init")
    if inputs is None:
        inputs = [(f"DI-{i + 1}", [n]) for i, n in enumerate(needs)]
    write_design_doc(docs / "design", "core", design_inputs=tuple(inputs))
    (docs / "design_review.md").write_text(COMPLETE)
    _vv_plan(docs, needs)
    _git(repo, "add", "-A")
    _git(repo, "commit", "-m", "approve")
    return repo / "dhf"


@allure.story("DI-1")
@allure.label("output", "rdm/specification/sdd.py")
@allure.label("output", "rdm/kernel/frontmatter.py")
def test_the_record_is_read_from_frontmatter_alone(tmp_path: Path) -> None:
    """DI-1: the user needs and the design inputs that trace to them, read from
    the record's frontmatter, with no project-management dependency."""
    dhf = _approved_dhf(tmp_path, ["UN-001", "UN-002"], inputs=[("DI-1", ["UN-001"]), ("DI-2", ["UN-001", "UN-002"])])
    with verification_step("the user-need registry and each design input with the needs it traces to are read"):
        assert set(user_need_texts(dhf)) == {"UN-001", "UN-002"}
        assert [(di["id"], di["traces_to"]) for di in design_inputs(dhf)] == [
            ("DI-1", ["UN-001"]), ("DI-2", ["UN-001", "UN-002"])]
    with verification_step("a user need traced as one value, not a list, is that one need"):
        doc = dhf / "documents" / "design" / "core.md"
        doc.write_text(doc.read_text().replace("traces_to: [UN-001]", "traces_to: UN-001", 1))
        assert design_inputs(dhf)[0]["traces_to"] == ["UN-001"]
    with verification_step("a `---` inside a frontmatter value, or a byte-order mark, does not cut the document"):
        text = doc.read_text().replace(" requirement,", " --- requirement,", 1)
        doc.write_text("\ufeff" + text)
        assert design_input_ids(dhf) == {"DI-1", "DI-2"}
    with verification_step("reading the record needs no planning directory and imports no planning tool: nothing "
                           "beyond the shared kernel, the record reader and the YAML parser"):
        assert not (dhf.parent / "backlog").exists()
        probe = ("import sys\nbefore = set(sys.modules)\n"
                 "from rdm.specification.sdd import design_inputs, user_need_texts\n"
                 f"assert design_inputs({str(dhf)!r}) and user_need_texts({str(dhf)!r})\n"
                 "new = set(sys.modules) - before\n"
                 "import json\n"
                 "print(json.dumps([sorted({m.split('.')[0] for m in new} - set(sys.stdlib_module_names)),\n"
                 "                  sorted(m for m in new if m.startswith('rdm.'))]))\n")
        run = subprocess.run([sys.executable, "-c", probe], cwd=tmp_path, capture_output=True, text=True)
        assert run.returncode == 0, run.stderr
        third_party, ours = json.loads(run.stdout)
        attach("modules imported to read the record", run.stdout)
        assert {m for m in third_party if "cython" not in m} <= {"rdm", "yaml"}, third_party
        assert all(m.split(".")[1] in ("kernel", "specification") for m in ours), ours


@allure.story("DI-3")
@allure.label("output", "rdm/release/gate.py")
def test_release_gate_blocks_until_verified(tmp_path: Path) -> None:
    """DI-3: release is blocked unless every declared design input is verified
    by a passing test."""
    dhf = _approved_dhf(tmp_path, ["UN-003"], inputs=[("DI-1", ["UN-003"]), ("DI-2", ["UN-003"])])
    empty = tmp_path / "none"
    empty.mkdir()
    with verification_step("with no results, every design input is untested and release is blocked"):
        gate = run_release_gate(dhf, empty)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed
    results = tmp_path / "allure"
    with verification_step("one design input verified and another untested: release is blocked, naming the "
                           "untested one"):
        _allure_result(results, "a", "passed", "DI-1")
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed and any("DI-2" in m for m in gate.blocking) \
            and not any("DI-1" in m for m in gate.blocking), gate.blocking
    with verification_step("a failing design input blocks release"):
        _allure_result(results, "b", "failed", "DI-2")
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed and any("DI-2" in m for m in gate.blocking), gate.blocking
    with verification_step("a design input with a passing and a failing run is not verified: release is blocked"):
        _allure_result(results, "b", "passed", "DI-2")
        _allure_result(results, "c", "failed", "DI-1")
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed and any("DI-1" in m for m in gate.blocking), gate.blocking
    with verification_step("every design input verified by a passing test: release passes"):
        (results / "c-result.json").unlink()
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert gate.passed, gate.blocking


@allure.story("DI-4")
@allure.label("output", "rdm/evidence/allure.py")
@allure.label("output", "rdm/release/verify.py")
@allure.label("output", "rdm/publishing/render.py")
def test_verification_status_traceable_from_results(tmp_path: Path, monkeypatch, capsys) -> None:
    """DI-4: each declared design input reconciled against the story labels of
    the executed results as verified, failed or untested, and the traceability
    matrix of those statuses rendered under the user needs."""
    results = tmp_path / "allure"
    for name, status, story in (("p", "passed", "DI-1"), ("f", "failed", "DI-2"), ("s", "skipped", "DI-3"),
                                ("b", "broken", "DI-4")):
        _allure_result(results, name, status, story)
    with verification_step("a passed run verifies; a failed or broken one fails; a skipped run, or none, leaves "
                           "a design input untested"):
        report = allure_ingest.reconcile({"DI-1", "DI-2", "DI-3", "DI-4", "DI-5"}, results)
        attach("statuses", {i: v.status for i, v in sorted(report.by_id.items())})
        assert report.verified == ["DI-1"]
        assert report.failed == ["DI-2", "DI-4"]
        assert report.untested == ["DI-3", "DI-5"]
    with verification_step("only the story label names a design input: a feature or epic naming one does not"):
        (results / "e-result.json").write_text(json.dumps({"name": "e", "status": "failed", "labels": [
            {"name": "feature", "value": "DI-1"}, {"name": "epic", "value": "DI-1"}]}))
        assert allure_ingest.reconcile({"DI-1"}, results).verified == ["DI-1"]
        (results / "e-result.json").unlink()
    with verification_step("the rendered traceability matrix shows each design input's status under the user need "
                           "it traces to"):
        docs = tmp_path / "dhf" / "documents"
        docs.mkdir(parents=True)
        write_design_doc(docs / "design", "core", design_inputs=(
            ("DI-1", ["UN-001"]), ("DI-2", ["UN-001"]), ("DI-3", ["UN-001"]), ("DI-4", ["UN-002"]),
            ("DI-5", ["UN-002"])))
        _vv_plan(docs, ["UN-001", "UN-002"])
        assert verify_command(tmp_path / "dhf", results, tmp_path / "verification.yml") == 0
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
            elif line.startswith("| DI-"):
                cells = [c.strip() for c in line.strip("|").split("|")]
                rows[cells[0]] = (need, cells[1])
        assert rows == {"DI-1": ("UN-001", "verified"), "DI-2": ("UN-001", "failed"),
                        "DI-3": ("UN-001", "untested"), "DI-4": ("UN-002", "failed"),
                        "DI-5": ("UN-002", "untested")}, rows


