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
from pathlib import Path

import pytest
import yaml

from rdm.evidence import allure as allure_ingest
from rdm.specification import design_gate as design_gate_module
from rdm.specification import persona
from rdm.specification.hooks import install_hooks
from rdm.release.verify import build_verification, verify_command
from rdm.specification.design_gate import (
    CONTEXT_REPEATED,
    MALFORMED_DECLARATION,
    UNREADABLE_FRONTMATTER,
    check_design_docs,
    run_design_gate,
    story_design_gate_command,
)
from rdm.specification.sdd import design_input_ids, design_inputs
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
def test_compile_verification_from_the_record(tmp_path: Path) -> None:
    """DI-1: compile a DHF from the system of record (registry + results)."""
    dhf = _approved_dhf(tmp_path, ["UN-001"])  # DI-1 traces to UN-001
    results = tmp_path / "allure"
    _allure_result(results, "a", "passed", "DI-1")
    with allure.step("Reconcile the declared design inputs against Allure results"):
        data = build_verification(dhf, results)
    assert data["summary"]["total"] == 1
    with verification_step("Rows are design inputs, grouped under the user need they trace to"):
        assert data["groups"][0]["user_need"] == "UN-001"
        assert data["groups"][0]["design_inputs"][0]["design_input"] == "DI-1"
    with verification_step("a user need traced as one value, not a list, is that one need"):
        doc = dhf / "documents" / "design" / "core.md"
        doc.write_text(doc.read_text().replace("traces_to: [UN-001]", "traces_to: UN-001"))
        assert [di["traces_to"] for di in design_inputs(dhf)] == [["UN-001"]]
    # ...with NO project-management dependency: the record core must not import
    # the planning layer (a violation would show as a source-level import).
    with verification_step("...with NO project-management dependency: the record core must not import the planning "
                           "layer (a…"):
        import rdm.kernel
        import rdm.specification
        assert not any(
            "project_management" in p.read_text(encoding="utf-8")
            for package in (rdm.specification, rdm.kernel)
            for p in Path(package.__file__).parent.glob("*.py")
        )


@allure.story("DI-2")
@allure.label("output", "rdm/specification/design_gate.py")
def test_design_gate_requires_approval(tmp_path: Path, capsys) -> None:
    """DI-2: block transition until design docs are complete and approved."""
    with verification_step("the hook gates implementation however it is named, cased or staged, and so does a merge"):
        hooks = Path(design_gate_module.__file__).parent / "hook_files"
        red = tmp_path / "red"
        (red / "dhf").mkdir(parents=True)  # no design document: the gate is red
        _git(red, "init", "-q", "-b", "main")
        (red / "README.md").write_text("start\n")
        _git(red, "add", "-A")
        _git(red, "commit", "-qm", "start")
        assert "pip install rdm " not in (hooks / "pre-commit").read_text()

        def hook(name: str = "pre-commit") -> int:
            return subprocess.run(["bash", str(hooks / name)], cwd=red, capture_output=True).returncode

        for name in ("a\tb.py", 'we"ird.py', "app.PY", "Main.JAVA", "config.json", "settings.ini", "setup.cfg",
                     "Dockerfile", "Makefile", "index.html", "App.vue", "stubs.pyi", "analysis.R", "deploy.ps1"):
            (red / name).write_text("x\n")
            _git(red, "add", "--", name)
            assert hook() == 1, name
            _git(red, "reset", "-q")
            (red / name).unlink()
        (red / "app.py").write_text("x\n")
        (red / "evil.py").write_text("y\n")
        _git(red, "add", "-A")
        _git(red, "commit", "-qm", "before the gate")  # no hook installed yet
        (red / "app.py").unlink()
        (red / "app.py").symlink_to("evil.py")
        _git(red, "add", "app.py")
        assert hook() == 1, "a type change"
        _git(red, "reset", "-q", "--hard")
        install_hooks(str(red / ".git" / "hooks"))
        _git(red, "checkout", "-qb", "feature")
        (red / "merged.py").write_text("z\n")
        _git(red, "add", "-A")
        subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "work"], cwd=red,
                       check=True, env={**os.environ, "RDM_SKIP_DESIGN_GATE": "1"})
        _git(red, "checkout", "-q", "main")
        (red / "README.md").write_text("moved on\n")
        _git(red, "commit", "-qam", "docs")
        head = _git(red, "rev-parse", "HEAD")
        merge = subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "merge", "--no-ff", "-m", "m",
                                "feature"], cwd=red, capture_output=True, text=True)
        assert merge.returncode != 0 and _git(red, "rev-parse", "HEAD") == head, merge.stdout + merge.stderr
    with verification_step("a merge of a design change approved on its branch, with its implementation, passes"):
        green = _approved_dhf(tmp_path / "green", ["UN-001"]).parent
        _git(green, "branch", "-M", "main")
        install_hooks(str(green / ".git" / "hooks"))
        _git(green, "checkout", "-qb", "feature")
        core = green / "dhf" / "documents" / "design" / "core.md"
        core.write_text(core.read_text().replace("DI-1 requirement", "DI-1 refined requirement"))
        _git(green, "commit", "-qam", "design")  # the approval: design documents only
        (green / "app.py").write_text("print(1)\n")
        _git(green, "add", "app.py")
        _git(green, "commit", "-qm", "code")
        _git(green, "checkout", "-q", "main")
        (green / "README.md").write_text("moved on\n")
        _git(green, "add", "README.md")
        _git(green, "commit", "-qm", "docs")
        merge = subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "merge", "--no-ff", "-m", "m",
                                "feature"], cwd=green, capture_output=True, text=True)
        assert merge.returncode == 0 and (green / "app.py").exists(), merge.stdout + merge.stderr
        _git(green, "checkout", "-qb", "feature2", "feature")
        core.write_text(core.read_text().replace("refined", "re-refined"))
        _git(green, "commit", "-qam", "design again")
        _git(green, "checkout", "-q", "main")
        _git(green, "merge", "--no-ff", "--no-commit", "feature2")  # concluded later by git commit
        assert run_design_gate(green / "dhf").passed
        core.write_text(core.read_text() + "\nAn edit made during the merge.\n")
        assert not run_design_gate(green / "dhf").passed
        _git(green, "add", str(core))  # staged, as a resolved conflict is: content no commit holds
        assert not run_design_gate(green / "dhf").passed
    with verification_step("Incomplete (placeholder) design doc -> not complete"):
        docs = tmp_path / "dhf" / "documents" / "design"
        docs.mkdir(parents=True)
        (docs / "core.md").write_text("---\nkind: design\ncontext: core\n---\nTODO: fill me\nENDTODO\n")
        assert not check_design_docs(tmp_path / "dhf")[0].complete
    with verification_step("Approved (committed clean) -> ok"):
        dhf = _approved_dhf(tmp_path, ["UN-002"])
        assert all(c.ok for c in check_design_docs(dhf))
    # A COMPLETE but uncommitted doc is NOT approved -> not ok (this is the
    # "edit re-opens the gate" clause: approval is the committed revision, so an
    # unapproved working-tree change must fail even though the content is fine).
    with verification_step("A COMPLETE but uncommitted doc is NOT approved -> not ok (this is the \"edit re-opens "
                           "the gate\"…"):
        repo = tmp_path / "uncommitted"
        udocs = repo / "dhf" / "documents" / "design"
        udocs.mkdir(parents=True)
        _git(repo, "init")
        write_design_doc(udocs, "core", design_inputs=(("DI-2", ["UN-002"]),))
        uncommitted = check_design_docs(repo / "dhf")
        assert uncommitted and uncommitted[0].complete and not uncommitted[0].ok
    with verification_step("a `---` inside a frontmatter value, or a byte-order mark, does not cut the document"):
        doc = dhf / "documents" / "design" / "core.md"
        text = doc.read_text().replace(" requirement,", " --- requirement,", 1)
        doc.write_text("\ufeff" + text)
        assert design_input_ids(dhf) == {"DI-1"}
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
    with verification_step("two design documents for one context are a warning naming both"):
        write_design_doc(design, "core2", design_inputs=(("DI-6", ["UN-002"]),))
        (design / "core2.md").write_text((design / "core2.md").read_text().replace("context: core2", "context: core"))
        _git(dhf.parent, "add", "-A")
        _git(dhf.parent, "commit", "-m", "second core")
        warned = [e.message for e in run_design_gate(dhf).events if e.name == CONTEXT_REPEATED]
        attach("context warnings", warned)
        assert len(warned) == 1 and "core.md" in warned[0] and "core2.md" in warned[0]
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


