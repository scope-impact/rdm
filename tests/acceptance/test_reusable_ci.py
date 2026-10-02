"""Acceptance test for RDM's gates as reusable CI (DI-63, see dhf/).

Tagged `@allure.story("DI-63")`. The composite gates action is run step by
step against a small committed record, the way a runner would; the reusable
workflow, the PDF action and the workflow `rdm adopt` lays down are read as
the YAML GitHub reads. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

allure = pytest.importorskip("allure")

from rdm.specification.adopt import adopt  # noqa: E402
from rdm.main import parse_arguments  # noqa: E402
from rdm.kernel.version import __version__  # noqa: E402
from tests.acceptance.evidence import attach, verification_step  # noqa: E402
from tests.util import git_run, write_allure_result  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "gates.yml"
GATES = ROOT / "actions" / "gates" / "action.yml"
PDF_ACTION = ROOT / "action.yml"
DOGFOOD = ROOT / ".github" / "workflows" / "design-controls.yml"
EXPR = re.compile(r"\$\{\{\s*(.*?)\s*\}\}")


def _releasable(repo: Path) -> Path:
    """A committed record that passes every gate: one need, one design input,
    a review, and a passing run of the input's tagged test."""
    docs = repo / "dhf" / "documents"
    (docs / "design").mkdir(parents=True)
    (docs / "verification_and_validation_plan.md").write_text(
        "---\nid: VVP-001\nuser_needs:\n  - {id: UN-001, text: 'a need'}\n---\n# Plan\n")
    (docs / "design" / "alarms.md").write_text(
        "---\nid: SDS-ALM-001\nkind: design\ncontext: alarms\ndesign_inputs:\n"
        "  - {id: DI-1, text: 'The device shall alarm.', traces_to: [UN-001]}\n---\n# Alarms\n")
    (docs / "design_review.md").write_text("---\nid: DR-001\n---\n# Review\nApproved.\n")
    write_allure_result(repo / "dhf" / "allure-results", "r1", "passed", "DI-1")
    for args in (["init", "-q"], ["add", "-A"], ["commit", "-qm", "r"]):
        git_run(repo, *args)
    return repo / "dhf"


def _yaml(path: Path) -> dict:
    data = yaml.safe_load(path.read_text())
    if True in data:  # YAML 1.1 reads a workflow's key `on` as True
        data["on"] = data.pop(True)
    return data


def _value(expr: str, inputs: dict[str, str], env: dict[str, str], outputs: dict[str, dict[str, str]]) -> str:
    """The few expressions the gates action uses, evaluated as Actions does."""
    expr = expr.strip()
    if step := re.fullmatch(r"steps\.([\w-]+)\.outputs\.([\w-]+)", expr):
        return outputs.get(step.group(1), {}).get(step.group(2), "")
    if expr.startswith("inputs."):
        return inputs[expr.removeprefix("inputs.")]
    if expr == "runner.temp":
        return env["RUNNER_TEMP"]
    raise AssertionError(f"expression the test does not know: {expr}")


def _condition(cond: str, inputs: dict[str, str], env: dict[str, str], outputs: dict[str, dict[str, str]]) -> bool:
    def term(t: str) -> bool:
        left, op, right = re.fullmatch(r"(.+?)\s*(==|!=)\s*'(.*)'", t.strip()).groups()
        return (_value(left, inputs, env, outputs) == right) == (op == "==")
    return all(term(t) for t in cond.split("&&"))


def _run_action(action: dict, inputs: dict[str, str], cwd: Path, temp: Path) -> list[str]:
    """Run a composite action's shell steps as the runner would; return the names run."""
    inputs = {k: str(v.get("default", "")) for k, v in action["inputs"].items()} | inputs
    env = os.environ | {"PWD": str(cwd), "RUNNER_TEMP": str(temp),
                        "PATH": f"{Path(sys.executable).parent}{os.pathsep}{os.environ['PATH']}"}
    outputs: dict[str, dict[str, str]] = {}

    def expand(text: str) -> str:
        return EXPR.sub(lambda m: _value(m.group(1), inputs, env, outputs), text)

    ran = []
    for step in action["runs"]["steps"]:
        if "if" in step and not _condition(step["if"], inputs, env, outputs):
            continue
        if "uses" in step:  # actions/upload-artifact: what it uploads must exist
            if "path" in step.get("with", {}):
                path = expand(step["with"]["path"])
                assert Path(path).is_dir() and any(Path(path).iterdir()), path
            ran.append(step["uses"].split("@")[0])
            continue
        assert not EXPR.search(step["run"]), f"{step['name']}: an expression spliced into the script"
        output_file = temp / "github-output"
        output_file.write_text("")
        step_env = env | {k: expand(v) for k, v in step.get("env", {}).items()} | {"GITHUB_OUTPUT": str(output_file)}
        done = subprocess.run(["bash", "-e", "-c", step["run"]], cwd=cwd, env=step_env,
                              capture_output=True, text=True)
        assert done.returncode == 0, f"{step['name']}:\n{done.stdout}\n{done.stderr}"
        if "id" in step:
            outputs[step["id"]] = dict(line.split("=", 1) for line in output_file.read_text().splitlines() if line)
        ran.append(step["name"])
    return ran


def _rdm_commands(text: str) -> list[list[str]]:
    """Each `rdm …` invocation in a workflow's scripts, shell-split: a variable
    stands for one word, an array expansion (`${a[@]}`) for none."""
    text = EXPR.sub("X", text.replace("\\\n", " "))
    commands = []
    for line in text.splitlines():
        match = re.search(r"(?:^|[\s;&|])rdm\s+([^;&|#]+)", line)
        if match and not line.lstrip().startswith(("#", "name:", "description:")):
            words = [w if not w.startswith("$") else "X" for w in shlex.split(match.group(1))
                     if not re.fullmatch(r"\$\{\w+\[@\]\}", w)]
            commands.append(words)
    return commands


@allure.story("DI-63")
@allure.label("output", ".github/workflows/gates.yml")
@allure.label("output", "actions/gates/action.yml")
@allure.label("output", "action.yml")
@allure.label("output", "rdm/specification/adopt.py")
@allure.label("output", "rdm/specification/adopt_files/.github/workflows/design-controls.yml")
def test_the_gates_are_reusable_ci_pinned_by_revision(tmp_path: Path) -> None:
    """DI-63: a reusable workflow (tests, then the gates) and a composite gates
    action install RDM from the pinned revision; the PDF action renders with
    that revision's image; the workflow rdm adopt lays down calls the reusable
    workflow at the installed version, as RDM's own CI does."""
    action, workflow = _yaml(GATES), _yaml(WORKFLOW)
    repo = tmp_path / "product"
    dhf = _releasable(repo)
    (tmp_path / "runner").mkdir()

    with verification_step("the gates action runs the design gate, verify, the release gate, graph validation "
                           "and the evidence bundle on a record, and uploads the bundle"):
        ran = _run_action(action, {"install-rdm": "false"}, repo, tmp_path / "runner")
        attach("steps run", ran)
        assert ran == ["Find the Allure results", "Design gate", "Verification data", "Release gate",
                       "Graph validation", "Release evidence bundle", "actions/upload-artifact"]
        assert (dhf / "data" / "verification.yml").is_file()
        assert any((tmp_path / "runner" / "rdm-evidence").iterdir())
    with verification_step("the checklists named are held against the documents in graph validation"):
        with pytest.raises(AssertionError, match=r"Graph validation:(?s:.*)P11:11\.10a: checklist clause"):
            _run_action(action, {"install-rdm": "false", "checklists": "part11_document_control"},
                        repo, tmp_path / "runner")
    with verification_step("with the release gate off and no results yet, only the design gate runs"):
        shutil.rmtree(dhf / "allure-results")
        assert _run_action(action, {"install-rdm": "false", "release-gate": "false", "graph-validate": "false"},
                           repo, tmp_path / "runner") == ["Find the Allure results", "Design gate"]
    with verification_step("a gate that fails fails the action"):
        (dhf / "documents" / "design_review.md").unlink()
        with pytest.raises(AssertionError, match="Design gate"):
            _run_action(action, {"install-rdm": "false"}, repo, tmp_path / "runner")
    with verification_step("every rdm command the workflow and the actions run is one RDM's CLI accepts"):
        commands = [c for path in (GATES, WORKFLOW, PDF_ACTION) for c in _rdm_commands(path.read_text())]
        attach("rdm commands", [" ".join(c) for c in commands])
        assert {tuple(c[:2]) for c in commands} >= {("story", "design-gate"), ("story", "verify"),
                                                    ("story", "release-gate"), ("graph", "validate"),
                                                    ("story", "evidence-bundle")}
        for command in commands:
            if command != ["--version"]:
                parse_arguments(command)
    with verification_step("the action installs RDM from its own revision, the workflow from the revision the "
                           "caller pinned, and nothing installs rdm from a package index"):
        install = next(s["run"] for s in action["runs"]["steps"] if s.get("name", "").startswith("Install RDM"))
        assert '"rdm[graph,report] @ file://' in install and "$GITHUB_ACTION_PATH/../.." in install
        steps = workflow["jobs"]["gates"]["steps"]
        rdm = next(s for s in steps if s.get("with", {}).get("path") == ".rdm")
        assert rdm["with"]["ref"] == "${{ inputs.rdm-ref }}" and workflow["on"]["workflow_call"]["inputs"][
            "rdm-ref"]["required"] is True
        assert any('"./.rdm[graph,report]"' in s.get("run", "") for s in steps)
        assert any(s.get("uses") == "./.rdm/actions/gates" for s in steps)
        for path in (GATES, WORKFLOW, PDF_ACTION, DOGFOOD):
            assert not re.search(r"install\b[^\n]*\s(?:rdm|'rdm'|\"rdm\")(?:[\s=<>\[]|$)", path.read_text(),
                                 re.M), path
    with verification_step("the reusable workflow runs the caller's acceptance tests before the gates"):
        names = [s.get("name", s.get("uses")) for s in steps]
        assert names.index("Acceptance tests -> Allure results") < names.index("Gates")
        assert "pytest tests/acceptance" in workflow["on"]["workflow_call"]["inputs"]["test-command"]["default"]
    with verification_step("the PDF action renders with the image of the release it is pinned to, else latest"):
        script = _yaml(PDF_ACTION)["runs"]["steps"][0]["run"]
        bin_dir = tmp_path / "bin"
        bin_dir.mkdir()
        (bin_dir / "docker").write_text('#!/bin/sh\necho "$@"\n')
        (bin_dir / "docker").chmod(0o755)

        def image(ref: str, version: str = "") -> str:
            env = os.environ | {"ACTION_REF": ref, "VERSION": version, "DHF": "dhf", "GITHUB_WORKSPACE": "/w",
                                "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}"}
            out = subprocess.run(["bash", "-e", "-c", script], env=env, capture_output=True, text=True, check=True)
            return re.search(r"ghcr\.io/scope-impact/rdm:(\S+)\s+pdfs", out.stdout).group(1)
        assert [image("v1.2.0"), image("v1"), image("main"), image("0123abc"), image("v1.2.0", "edge")] == [
            "1.2.0", "1", "latest", "latest", "edge"]
    with verification_step("the workflow rdm adopt lays down calls the reusable workflow pinned to the installed "
                           "RDM's version, with inputs the workflow declares"):
        target = tmp_path / "adopter"
        target.mkdir()
        adopt(target)
        laid = _yaml(target / ".github" / "workflows" / "design-controls.yml")
        attach("adopted workflow", (target / ".github" / "workflows" / "design-controls.yml").read_text())
        job = laid["jobs"]["design-controls"]
        assert job["uses"] == f"scope-impact/rdm/.github/workflows/gates.yml@v{__version__}"
        assert job["with"]["rdm-ref"] == f"v{__version__}"
        assert set(job["with"]) <= set(workflow["on"]["workflow_call"]["inputs"])
    with verification_step("RDM's own CI calls the same workflow, pinned to the commit under test"):
        own = _yaml(DOGFOOD)["jobs"]["design-controls"]
        assert own["uses"] == "./.github/workflows/gates.yml" and own["with"]["rdm-ref"] == "${{ github.sha }}"
        assert set(own["with"]) <= set(workflow["on"]["workflow_call"]["inputs"])
