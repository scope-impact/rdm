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
validation against user needs stays UN-keyed (see test_formative_usability).
Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

from rdm.evidence import allure as allure_ingest
from rdm.specification import persona
from rdm.release.verify import build_verification, verify_command
from rdm.specification.design_gate import (
    check_design_docs,
    run_design_gate,
    story_design_gate_command,
)
from rdm.specification.sdd import design_input_ids, design_inputs, user_need_texts
from rdm.release.gate import INPUT_FAILED, UNREADABLE_RESULT, run_release_gate
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


@allure.story("DI-2")
@allure.label("output", "rdm/specification/design_gate.py")
def test_design_gate_requires_approval(tmp_path: Path, capsys) -> None:
    """DI-2: the design gate fails until the design documents, the user needs,
    the risk documents and the design review are complete and committed; an
    edit re-opens it; outside git it passes saying approval was not checked."""
    with verification_step("a design document holding a placeholder is not complete"):
        docs = tmp_path / "dhf" / "documents" / "design"
        docs.mkdir(parents=True)
        (docs / "core.md").write_text("---\nkind: design\ncontext: core\n---\nTODO: fill me\nENDTODO\n")
        assert not check_design_docs(tmp_path / "dhf")[0].complete
    with verification_step("complete and committed clean, the design documents are approved"):
        dhf = _approved_dhf(tmp_path, ["UN-002"])
        assert all(c.ok for c in check_design_docs(dhf))
    with verification_step("a complete but uncommitted document is not approved: an edit re-opens the gate"):
        repo = tmp_path / "uncommitted"
        udocs = repo / "dhf" / "documents" / "design"
        udocs.mkdir(parents=True)
        _git(repo, "init")
        write_design_doc(udocs, "core", design_inputs=(("DI-2", ["UN-002"]),))
        uncommitted = check_design_docs(repo / "dhf")
        assert uncommitted and uncommitted[0].complete and not uncommitted[0].ok
    with verification_step("during a merge, a document staged as a commit holds it is approved; an edit made "
                           "during the merge is not, staged or not"):
        green = _approved_dhf(tmp_path / "merging", ["UN-001"]).parent
        _git(green, "branch", "-M", "main")
        core = green / "dhf" / "documents" / "design" / "core.md"
        _git(green, "checkout", "-qb", "feature2")
        core.write_text(core.read_text().replace("DI-1 requirement", "DI-1 refined requirement"))
        _git(green, "commit", "-qam", "design, approved on its branch")
        _git(green, "checkout", "-q", "main")
        _git(green, "merge", "--no-ff", "--no-commit", "feature2")  # the merge is being made
        assert run_design_gate(green / "dhf").passed
        core.write_text(core.read_text() + "\nAn edit made during the merge.\n")
        assert not run_design_gate(green / "dhf").passed
        _git(green, "add", str(core))  # staged, as a resolved conflict is: content no commit holds
        assert not run_design_gate(green / "dhf").passed
    with verification_step("a design document git cannot see, or a link to one, is not approved"):
        design = dhf / "documents" / "design"
        (dhf.parent / ".gitignore").write_text("hidden.md\nreal.md\n")
        write_design_doc(design, "hidden", design_inputs=(("DI-7", ["UN-002"]),))
        _git(dhf.parent, "add", ".gitignore")
        _git(dhf.parent, "commit", "-m", "ignore")
        assert not run_design_gate(dhf).passed  # ignored, never committed
        (design / "hidden.md").unlink()
        real = write_design_doc(dhf.parent, "real", design_inputs=(("DI-7", ["UN-002"]),))  # outside the DHF
        (design / "link.md").symlink_to(os.path.relpath(real, design))
        _git(dhf.parent, "add", "-A")
        _git(dhf.parent, "commit", "-m", "link")
        assert not run_design_gate(dhf).passed  # the link is committed, its target is not
        (design / "link.md").unlink()
        real.unlink()
        _git(dhf.parent, "add", "-A")
        _git(dhf.parent, "commit", "-m", "unlink")
        assert run_design_gate(dhf).passed
        core = design / "core.md"
        _git(dhf.parent, "update-index", "--skip-worktree", str(core))
        core.write_text(core.read_text().replace("requirement", "weaker requirement"))
        assert not run_design_gate(dhf).passed  # the edit is hidden from git status, not from the gate
        _git(dhf.parent, "update-index", "--no-skip-worktree", str(core))
        _git(dhf.parent, "checkout", "--", str(core))
    with verification_step("a placeholder is the word TODO, not part of a longer word"):
        review = dhf / "documents" / "design_review.md"
        review.write_text(review.read_text() + "\nPHOTODOCUMENTATION of the device.\n")
        _git(dhf.parent, "commit", "-am", "photo")
        assert run_design_gate(dhf).passed
    with verification_step("the user needs and the risk documents are held as the design documents are: complete "
                           "and committed"):
        held = _approved_dhf(tmp_path / "held", ["UN-001"])
        assert run_design_gate(held).passed
        plan = held / "documents" / "verification_and_validation_plan.md"
        plan.write_text(plan.read_text() + "\nAn unreviewed edit.\n")
        gate = run_design_gate(held)
        assert not gate.passed and any("verification_and_validation_plan.md" in " ".join(a.reasons) or
                                       "verification_and_validation_plan" in a.name for a in gate.artifacts
                                       if not a.ok), gate.artifacts
        _git(held.parent, "commit", "-qam", "plan")
        assert run_design_gate(held).passed
        plan.write_text(plan.read_text() + "\nTODO: describe validation\n")
        _git(held.parent, "commit", "-qam", "placeholder")
        assert not run_design_gate(held).passed
        plan.write_text(plan.read_text().replace("TODO: describe validation", "Reviewed by QA."))
        _git(held.parent, "commit", "-qam", "filled")
        (held / "documents" / "risk").mkdir()
        (held / "documents" / "risk" / "register.md").write_text("---\nid: RMF\nkind: risk\nrisks: []\n---\n")
        (held / "documents" / "risk" / "policy.md").write_text("---\nid: RMP\nrisk_policy: {}\n---\n")
        gate = run_design_gate(held)
        failing = " ".join(f"{a.name} {' '.join(a.reasons)}" for a in gate.artifacts if not a.ok)
        assert not gate.passed and "register.md" in failing and "policy.md" in failing, failing
        _git(held.parent, "add", "-A")
        _git(held.parent, "commit", "-qm", "risks")
        assert run_design_gate(held).passed
    with verification_step("outside git the gate passes but does not claim the design was committed"):
        plain = tmp_path / "plain"
        write_design_doc(plain / "documents" / "design", "core", design_inputs=(("DI-1", ["UN-002"]),))
        (plain / "documents" / "design_review.md").write_text(COMPLETE)
        _vv_plan(plain / "documents", ["UN-002"])
        assert story_design_gate_command(plain) == 0
        out = capsys.readouterr().out
        attach("design gate outside git", out)
        assert "approved (committed) in version control" not in out and "could not be checked" in out


@allure.story("DI-3")
@allure.label("output", "rdm/release/gate.py")
def test_release_gate_blocks_until_verified(tmp_path: Path) -> None:
    """DI-3: block release until every design input is verified by a passing test."""
    dhf = _approved_dhf(tmp_path, ["UN-003"])  # DI-1 traces to UN-003
    empty = tmp_path / "none"
    empty.mkdir()
    with verification_step("an untested design input blocks"):
        gate = run_release_gate(dhf, empty)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed
    results = tmp_path / "allure"
    with verification_step("a failing design input blocks"):
        _allure_result(results, "a", "failed", "DI-1")
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert not gate.passed
    with verification_step("a verified design input passes"):
        _allure_result(results, "a", "passed", "DI-1")
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert gate.passed
    with verification_step("a result file that cannot be read blocks: it could hold a failed run"):
        for bad in ('{"status": "failed", "labels": [{"name": "story", "value": "DI-1"', "[]"):
            (results / "b-result.json").write_text(bad)
            gate = run_release_gate(dhf, results)
            attach("release gate blocking", gate.blocking)
            assert not gate.passed and any(e.name == UNREADABLE_RESULT for e in gate.events)
        (results / "b-result.json").write_bytes(b'{"name": "\xff"}')
        assert not run_release_gate(dhf, results).passed
    with verification_step("a result with a byte-order mark is read, and its failed run counts"):
        failed = '{"status": "failed", "labels": [{"name": "story", "value": "DI-1"}]}'
        (results / "b-result.json").write_text("\ufeff" + failed)
        gate = run_release_gate(dhf, results)
        assert not gate.passed and [e.name for e in gate.events if e.blocking] == [INPUT_FAILED]
    with verification_step("a result that could hold a failed run is unreadable: a status Allure does not write, "
                           "labels that are not name and value text, a symbolic link, JSON nested too deep"):
        story = [{"name": "story", "value": "DI-1"}]
        cases = {"capital-status": {"status": "Failed", "labels": story}, "no-status": {"labels": story},
                 "null-status": {"status": None, "labels": story},
                 "labels-text": {"status": "failed", "labels": "story=DI-1"},
                 "story-list": {"status": "failed", "labels": [{"name": "story", "value": ["DI-1"]}]}}
        for name, data in cases.items():
            case = tmp_path / f"unreadable-{name}"
            _allure_result(case, "a", "passed", "DI-1")
            (case / "b-result.json").write_text(json.dumps(data))
            gate = run_release_gate(dhf, case)
            attach(f"{name} blocking", gate.blocking)
            assert [e.name for e in gate.events if e.blocking] == [UNREADABLE_RESULT], (name, gate.blocking)
        case = tmp_path / "unreadable-link"
        _allure_result(case, "a", "passed", "DI-1")
        (tmp_path / "elsewhere.json").write_text(json.dumps({"status": "passed", "labels": story}))
        (case / "b-result.json").symlink_to(tmp_path / "elsewhere.json")
        assert [e.name for e in run_release_gate(dhf, case).events if e.blocking] == [UNREADABLE_RESULT]
        case = tmp_path / "unreadable-deep"
        _allure_result(case, "a", "passed", "DI-1")
        (case / "b-result.json").write_text("[" * 100000 + "]" * 100000)
        assert [e.name for e in run_release_gate(dhf, case).events if e.blocking] == [UNREADABLE_RESULT]
    with verification_step("a run tagged with a mistyped id is an orphan warning, never silence"):
        case = tmp_path / "mistyped"
        _allure_result(case, "a", "passed", "DI-1")
        for i, tag in enumerate(("di-1", "DI_1", "DI\u20131")):
            _allure_result(case, f"m{i}", "failed", tag)
        gate = run_release_gate(dhf, case)
        attach("warnings", gate.warnings)
        assert gate.passed, gate.blocking
        assert {f"Allure result tag {tag} matches no design input" for tag in ("di-1", "DI_1", "DI\u20131")} \
            <= set(gate.warnings), gate.warnings


@allure.story("DI-4")
@allure.label("output", "rdm/evidence/allure.py")
@allure.label("output", "rdm/release/verify.py")
def test_verification_status_traceable_from_results(tmp_path: Path) -> None:
    """DI-4: results reconcile to a status (reconcile clause) AND assemble into the
    traceability matrix grouped under the user need (render clause)."""
    with verification_step("Reconcile clause: executed results classify into verified/failed/untested"):
        results = tmp_path / "allure"
        _allure_result(results, "ok", "passed", "DI-A")
        _allure_result(results, "bad", "failed", "DI-B")
        report = allure_ingest.reconcile({"DI-A", "DI-B", "DI-C"}, results)
        assert report.verified == ["DI-A"]
        assert report.failed == ["DI-B"]
        assert report.untested == ["DI-C"]

    # Render clause: build_verification assembles the matrix — each design input
    # carries its real status, grouped under the user need it traces to. The
    # mixed pass/fail row set means a "always verified" assembly bug is caught.
    with verification_step("Render clause: build_verification assembles the matrix — each design input carries its "
                           "real…"):
        docs = tmp_path / "dhf" / "documents"
        docs.mkdir(parents=True)
        write_design_doc(docs / "design", "core", design_inputs=(("DI-1", ["UN-001"]), ("DI-2", ["UN-001"])))
        _vv_plan(docs, ["UN-001"])
        matrix_results = tmp_path / "allure-matrix"
        _allure_result(matrix_results, "p", "passed", "DI-1")
        _allure_result(matrix_results, "f", "failed", "DI-2")
        data = build_verification(tmp_path / "dhf", matrix_results)
        rows = {di["design_input"]: di["status"]
                for group in data["groups"] for di in group["design_inputs"]}
        assert rows == {"DI-1": "verified", "DI-2": "failed"}
        assert data["groups"][0]["user_need"] == "UN-001"

    with verification_step("A passed run whose verification step failed or broke failed: a failed step fails the test"):
        stepped = tmp_path / "allure-steps"
        stepped.mkdir()
        for name, status in (("s", "failed"), ("t", "broken")):
            (stepped / f"{name}-result.json").write_text(json.dumps(
                {"name": name, "status": "passed", "labels": [{"name": "story", "value": f"DI-{name.upper()}"}],
                 "steps": [{"name": "outer", "status": "passed", "steps": [{"name": "inner", "status": status}]}]}))
        report = allure_ingest.reconcile({"DI-S", "DI-T"}, stepped)
        assert report.failed == ["DI-S", "DI-T"], report.by_id

    with verification_step("verify names a result file it cannot read, and exits non-zero, as the gate blocks"):
        (matrix_results / "x-result.json").write_text('{"status": "failed", "labels": [')
        out_file = tmp_path / "verification.yml"
        assert verify_command(tmp_path / "dhf", matrix_results, out_file) == 1
        data = yaml.safe_load(out_file.read_text())
        assert data["unreadable"] == ["x-result.json"]


@allure.story("DI-5")
@allure.label("output", "rdm/specification/persona.py")
def test_formative_usability_classified(tmp_path: Path) -> None:
    """DI-5: usability can be exercised formatively against a user need.

    Validation stays anchored on the *user need* (UN-001), not a design input:
    formative usability evidence never gates release.
    """
    runs = tmp_path / "persona-results"
    runs.mkdir()
    (runs / "p-persona.json").write_text(
        json.dumps({"persona": "nurse", "user_need": "UN-001", "outcome": "success",
                    "usability_issues": [{"severity": "confusion", "step": 1, "note": "x"}]})
    )
    report = persona.reconcile({"UN-001"}, runs)
    assert report.by_id["UN-001"].status == persona.ISSUES
    with verification_step("a run that does not say it completed did not: an unknown or missing outcome fails"):
        for i, outcome in enumerate(("error", None)):
            other = tmp_path / f"runs-{i}"
            other.mkdir()
            (other / "q-persona.json").write_text(json.dumps(
                {"persona": "nurse", "user_need": "UN-001", **({"outcome": outcome} if outcome else {})}))
            assert persona.reconcile({"UN-001"}, other).by_id["UN-001"].status == persona.FAILED


