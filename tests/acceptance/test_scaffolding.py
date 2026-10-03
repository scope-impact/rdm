"""Acceptance tests for the scaffolding context's design inputs (see dhf/).

The tests ("live BDD") that verify DI-15 (`rdm init`) and DI-22
(`rdm story new-input`), tagged `@allure.story`, exercising the real
scaffolders. Lightweight on purpose: the end-to-end "the scaffold builds a
release" check lives in `fresh_release_test.py` (it needs Pandoc/make), not here.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from rdm.specification.sdd import design_inputs
from rdm.specification.new_input import story_new_input_command

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402


@allure.story("DI-15")
@allure.label("output", "rdm/specification/init.py")
def test_init_scaffolds_a_project(tmp_path: Path, capsys) -> None:
    """DI-15: `rdm init` lays down the templates, Makefile, and render config."""
    import yaml

    from rdm.main import cli

    project = tmp_path / "regulatory"  # must not pre-exist (copytree)
    assert cli(["init", "-o", str(project)]) == 0
    with verification_step("it says what it laid down and what to do next; a directory that exists is refused"):
        out = capsys.readouterr().out
        assert str(project) in out and "Next steps" in out and "rdm gap" in out, out
        assert cli(["init", "-o", str(project)]) == 2
        assert "exists" in capsys.readouterr().out
    with verification_step("the scaffold builds offline and without an RDM checkout: local images, Word rebuilt "
                           "when its reference document changes, the container installs RDM without a wheel"):
        for doc in (project / "documents").rglob("*.md"):
            assert "github.com/innolitics/rdm/raw" not in doc.read_text(), doc
        assert "$(wildcard reference.docx)" in (project / "Makefile").read_text()
        dockerfile = (project / "Dockerfile").read_text()
        assert "COPY dist/*.whl" not in dockerfile and "git+https://github.com/scope-impact/rdm" in dockerfile

    with verification_step("the build skeleton"):
        assert (project / "Makefile").is_file()
        assert (project / "config.yml").is_file()
        assert (project / "documents").is_dir()

    with verification_step("the design-controls templates ship, so new projects inherit the model"):
        docs = project / "documents"
        assert (docs / "software_design_specification.md").is_file()  # the kind:design template
        assert (docs / "design_review.md").is_file()
        assert (docs / "traceability_matrix.md").is_file()
        assert "kind: design" in (docs / "software_design_specification.md").read_text()

    # the record-first control surface matches the adopt scaffold: the V&V
    # plan carries a machine-readable `user_needs` registry (the coverage
    # denominator the gates read), and the agent runbook lands at the root —
    # an init project must not discover at gate time that its registry has
    # no home.
    with verification_step("the record-first control surface matches the adopt scaffold: the V&V plan carries a…"):
        vv_plan = (docs / "verification_and_validation_plan.md").read_text()
        frontmatter = yaml.safe_load(vv_plan.split("---")[1])
        assert frontmatter["user_needs"] == []              # present, empty, parseable
        assert (project / "AGENT_WORKFLOW.md").is_file()    # same runbook as `rdm adopt`


def _mini_dhf(tmp_path: Path) -> Path:
    """A minimal DHF: two design contexts (so context *choice* is observable),
    design inputs DI-1/DI-3 taken, one registered user need."""
    dhf = tmp_path / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (dhf / "documents" / "vv_plan.md").write_text(
        "---\nuser_needs:\n"
        "  - {id: UN-001, text: 'a need'}\n"
        "  - {id: UN-002, text: 'another need'}\n"
        "---\n"
    )
    (dhf / "documents" / "design" / "alarms.md").write_text(
        "---\n"
        "id: SDS-A-001\n"
        "kind: design\n"
        "context: alarms\n"
        "design_inputs:\n"
        "  - id: DI-1\n"
        '    text: "existing input"\n'
        "    traces_to: [UN-001]\n"
        "---\n\n# Alarms\n"
    )
    (dhf / "documents" / "design" / "trends.md").write_text(
        "---\n"
        "id: SDS-T-001\n"
        "kind: design\n"
        "context: trends\n"
        "satisfies:\n"          # a legacy key from before needs were derived: left alone
        "  - UN-001\n"
        "design_inputs:\n"
        "  - id: DI-3\n"
        '    text: "another input"\n'
        "    traces_to: [UN-001]\n"
        "---\n\n# Trends\n"
    )
    (tmp_path / "tests").mkdir()
    return dhf


@allure.story("DI-22")
@allure.label("output", "rdm/specification/new_input.py")
def test_new_input_scaffolds_a_traced_design_input(tmp_path: Path, capsys) -> None:
    """DI-22: next unused id allocated, frontmatter entry inserted, failing tagged
    stub test emitted, checklist printed; unknown context/user need rejected."""
    dhf = _mini_dhf(tmp_path)

    assert story_new_input_command(
        dhf_dir=dhf, context="alarms", text="RDM shall beep.", traces_to="UN-002"
    ) == 0
    out = capsys.readouterr().out

    # Allocates the next UNUSED id: max(DI-1, DI-3) + 1, not first-gap or count.
    # The entry lands in the CHOSEN context's design_inputs frontmatter (and not
    # in the other context's), traced to the user need.
    with verification_step("Allocates the next UNUSED id: max(DI-1, DI-3) + 1, not first-gap or count. The entry "
                           "lands in…"):
        declared = {di["id"]: di for di in design_inputs(dhf)}
        assert set(declared) == {"DI-1", "DI-3", "DI-4"}
        assert declared["DI-4"]["text"] == "RDM shall beep."
        assert declared["DI-4"]["traces_to"] == ["UN-002"]
        assert declared["DI-4"]["context"] == "alarms"
        alarms_text = (dhf / "documents" / "design" / "alarms.md").read_text()
        assert "DI-4" in alarms_text
        assert "DI-4" not in (dhf / "documents" / "design" / "trends.md").read_text()

    # The user need goes on the input alone: no context-level list is added.
    with verification_step("The user need goes on the input alone: no context-level satisfies list is written"):
        assert "satisfies" not in alarms_text

    # Emits a stub acceptance test tagged with the new id that FAILS until
    # implemented (honest red at the release gate), and prints the checklist.
    with verification_step("Emits a stub acceptance test tagged with the new id, with a component label to fill "
                           "in, that FAILS until implemented"):
        stub = tmp_path / "tests" / "acceptance" / "test_alarms.py"
        assert '@allure.story("DI-4")' in stub.read_text()
        assert '@allure.label("component", "TODO")' in stub.read_text()  # the component to fill in
        assert '@allure.label("output"' not in stub.read_text()
        result = pytest.main(["-q", "--no-header", "-p", "no:cacheprovider", str(stub)])
        assert result == pytest.ExitCode.TESTS_FAILED
        assert "checklist" in out and "DI-4" in out

    # A legacy `satisfies` key is left exactly as it was. The requirement text
    # is embedded safely: quotes, a backslash, and a triple-quote must corrupt
    # neither the frontmatter nor the stub test.
    with verification_step("A legacy satisfies key is left as it was; hostile requirement text round-trips safely"):
        import ast

        import yaml

        hostile = ('The "trend" shall use \\ paths and tolerate """ in prose, and keep doing so for every '
                   'trend a clinician opens, however long the requirement that says so happens to run.')
        assert story_new_input_command(
            dhf_dir=dhf, context="trends", text=hostile, traces_to="UN-002"
        ) == 0
        trends_text = (dhf / "documents" / "design" / "trends.md").read_text()
        frontmatter = yaml.safe_load(trends_text.split("---")[1])
        assert frontmatter["satisfies"] == ["UN-001"]            # legacy key untouched
        declared = {di["id"]: di for di in design_inputs(dhf)}
        assert declared["DI-5"]["text"] == hostile               # YAML round-trips exactly
        trends_stub = tmp_path / "tests" / "acceptance" / "test_trends.py"
        module = ast.parse(trends_stub.read_text())              # stub is valid Python
        assert all(len(line) <= 100 for line in trends_stub.read_text().splitlines())  # wrapped for lint
        stub_fn = next(n for n in module.body if isinstance(n, ast.FunctionDef) and "5" in n.name)
        assert " ".join(ast.get_docstring(stub_fn).split()) == "DI-5: " + " ".join(hostile.split())
        assert '@allure.story("DI-5")' in trends_stub.read_text()

    # A context declaring `design_inputs: []` gets its list filled in place,
    # never a second `design_inputs:` key.
    with verification_step("A context declaring `design_inputs: []` gets its list filled in place, never a second…"):
        (dhf / "documents" / "design" / "empty.md").write_text(
            "---\nid: SDS-EMPTY\nkind: design\ncontext: empty\n"
            "design_inputs: []\n---\n# Empty\n")
        assert story_new_input_command(
            dhf_dir=dhf, context="empty", text="RDM shall fill.", traces_to="UN-001"
        ) == 0
        empty_text = (dhf / "documents" / "design" / "empty.md").read_text()
        assert empty_text.count("design_inputs:") == 1
        assert [d["id"] for d in yaml.safe_load(empty_text.split("---")[1])["design_inputs"]] == ["DI-6"]

    with verification_step("A list written at the key's own indentation is extended in its own style"):
        shapes = _mini_dhf(tmp_path / "shapes")
        flat = shapes / "documents" / "design" / "flat.md"
        flat.write_text("---\nid: SDS-F\nkind: design\ncontext: flat\ndesign_inputs:\n- id: DI-20\n"
                        "  text: x\n  traces_to: [UN-001]\nstatus: draft\n---\n")
        assert story_new_input_command(dhf_dir=shapes, context="flat", text="RDM shall flatten.",
                                       traces_to="UN-001") == 0
        assert [di["id"] for di in design_inputs(shapes) if di["context"] == "flat"] == ["DI-20", "DI-21"]
        assert "status: draft" in flat.read_text()
    with verification_step("A list the edit cannot extend safely is refused, and the document is left as it was"):
        flow = shapes / "documents" / "design" / "flow.md"
        written = "---\nid: SDS-W\nkind: design\ncontext: flow\ndesign_inputs: [{id: DI-30, text: y}]\n---\n"
        flow.write_text(written)
        capsys.readouterr()
        assert story_new_input_command(dhf_dir=shapes, context="flow", text="RDM shall flow.", traces_to="UN-001") == 2
        assert "cannot add DI-31" in capsys.readouterr().out and flow.read_text() == written
    with verification_step("Rejects an unknown context and an unknown user need (nothing scaffolded)"):
        assert story_new_input_command(
            dhf_dir=dhf, context="nope", text="x", traces_to="UN-001"
        ) != 0
        assert story_new_input_command(
            dhf_dir=dhf, context="alarms", text="x", traces_to="UN-999"
        ) != 0
        assert set(di["id"] for di in design_inputs(dhf)) == {"DI-1", "DI-3", "DI-4", "DI-5", "DI-6"}
    with verification_step("Refuses a context with two design documents, naming both (nothing scaffolded)"):
        second = dhf / "documents" / "design" / "alarms_too.md"
        second.write_text("---\nid: SDS-A-002\nkind: design\ncontext: alarms\ndesign_inputs: []\n---\n")
        capsys.readouterr()
        assert story_new_input_command(dhf_dir=dhf, context="alarms", text="x", traces_to="UN-001") == 2
        out = capsys.readouterr().out
        attach("new-input on a repeated context", out)
        assert "alarms.md" in out and "alarms_too.md" in out
        assert set(di["id"] for di in design_inputs(dhf)) == {"DI-1", "DI-3", "DI-4", "DI-5", "DI-6"}
        second.unlink()
    with verification_step("an id a test is still tagged with is taken; a stub never shadows a test module of the "
                           "same name elsewhere in the suite"):
        (tmp_path / "tests" / "test_old.py").write_text(
            'import allure\n\n\n@allure.story("DI-9")\ndef test_retired():\n    pass\n')
        (tmp_path / "tests" / "test_trends.py").unlink(missing_ok=True)
        (tmp_path / "tests" / "unit").mkdir()
        (tmp_path / "tests" / "unit" / "test_alarms.py").write_text("def test_unit():\n    pass\n")
        (tmp_path / "tests" / "acceptance" / "test_alarms.py").unlink()
        assert story_new_input_command(dhf_dir=dhf, context="alarms", text="RDM shall ring.", traces_to="UN-001") == 0
        assert "DI-10" in {di["id"] for di in design_inputs(dhf)}
        assert not (tmp_path / "tests" / "acceptance" / "test_alarms.py").exists()
        assert '@allure.story("DI-10")' in (tmp_path / "tests" / "acceptance" / "test_alarms_acceptance.py").read_text()
    with verification_step("CRLF line endings and the comment on an empty list are kept"):
        crlf = dhf / "documents" / "design" / "crlf.md"
        crlf.write_bytes(b"---\r\nid: SDS-CRLF\r\nkind: design\r\ncontext: crlf\r\n"
                         b"design_inputs: []  # none yet, see review 3\r\n---\r\n# CRLF\r\n")
        assert story_new_input_command(dhf_dir=dhf, context="crlf", text="RDM shall keep.", traces_to="UN-001") == 0
        raw = crlf.read_bytes()
        assert b"\n" not in raw.replace(b"\r\n", b""), raw
        assert b"# none yet, see review 3" in raw


@allure.story("DI-24")
@allure.label("output", "rdm/specification/adopt.py")
def test_adopt_brings_existing_repo_under_controls(tmp_path: Path, capsys) -> None:
    """DI-24: one command lays down the DHF skeleton, runbook, design-gate hook,
    session bootstrap, and CI workflow into an existing repo, skipping (never
    overwriting) anything that already exists."""
    import os

    from rdm.specification.adopt import adopt_command

    repo = tmp_path / "legacy"
    (repo / "src").mkdir(parents=True)
    (repo / "src" / "app.py").write_text("print('legacy')\n")
    (repo / "dhf").mkdir()
    sentinel = "# our own pre-existing DHF notes\n"
    (repo / "dhf" / "README.md").write_text(sentinel)

    assert adopt_command(str(repo)) == 0
    out = capsys.readouterr().out

    with verification_step("The control surface lands: DHF skeleton"):
        docs = repo / "dhf" / "documents"
        assert (docs / "verification_and_validation_plan.md").is_file()   # V&V plan
        design_template = docs / "design" / "example_context.md"
        assert "kind: design" in design_template.read_text()              # context template
        assert (docs / "design_review.md").is_file()                      # design review
        assert (docs / "traceability_matrix.md").is_file()                # matrix template
    with verification_step("...the runbook, the hook, the bootstrap, and the CI workflow"):
        assert (repo / "dhf" / "AGENT_WORKFLOW.md").is_file()
        hook = repo / ".githooks" / "pre-commit"
        assert "design-gate" in hook.read_text() and os.access(hook, os.X_OK)
        merge_hook = repo / ".githooks" / "pre-merge-commit"  # a merge runs the same gate
        assert "pre-commit" in merge_hook.read_text() and os.access(merge_hook, os.X_OK)
        bootstrap = repo / "scripts" / "agent-bootstrap.sh"
        assert os.access(bootstrap, os.X_OK)
        assert "agent-bootstrap" in (repo / ".claude" / "settings.json").read_text()
        workflow = (repo / ".github" / "workflows" / "design-controls.yml").read_text()
        assert "scope-impact/rdm/.github/workflows/gates.yml@v" in workflow

    with verification_step("Pre-existing files are skipped, never overwritten — and reported"):
        assert (repo / "dhf" / "README.md").read_text() == sentinel
        assert "dhf/README.md" in out and "Skipped" in out
        assert (repo / "src" / "app.py").read_text() == "print('legacy')\n"

    with verification_step("Re-running is safe: everything now exists, so nothing is copied"):
        assert adopt_command(str(repo)) == 0
        rerun_out = capsys.readouterr().out
        assert "Laid down:" not in rerun_out
    with verification_step("what RDM generates is ignored and a render configuration lands; the design template "
                           "links nothing the project lacks"):
        ignored = (repo / ".gitignore").read_text()
        for entry in ("allure-results/", "dhf/data/", ".rdm/", "__pycache__/"):
            assert entry in ignored, entry
        assert (repo / "dhf" / "config.yml").is_file()
        template = (repo / "dhf" / "documents" / "design" / "example_context.md").read_text()
        assert not re.search(r"^!\[.*c4/views", template, re.M)  # no image the project does not have
    with verification_step("an existing .gitignore is kept, and the lines to add are named; a symbolic link, "
                           "dangling or not, is never written through"):
        other = tmp_path / "other"
        other.mkdir()
        (other / ".gitignore").write_text("node_modules/\n")
        (other / "scripts").mkdir()
        (other / "scripts" / "agent-bootstrap.sh").symlink_to("../../outside-the-repo.sh")
        capsys.readouterr()
        assert adopt_command(str(other)) == 0
        out = capsys.readouterr().out
        assert (other / ".gitignore").read_text() == "node_modules/\n"
        assert "__pycache__/" in out and ".gitignore" in out
        assert not (tmp_path / "outside-the-repo.sh").exists()
        assert "scripts/agent-bootstrap.sh" in out.split("Skipped")[1]
