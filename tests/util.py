import json
import subprocess
from pathlib import Path

import jinja2
from jinja2 import FunctionLoader

from rdm.render import render_template_to_string

# A complete (placeholder-free) design document for gate fixtures.
COMPLETE_DOC = "# Doc\n\nApproved and complete.\n"


def git_run(repo: Path, *args: str) -> None:
    """Run a git command in `repo` with a fixed test identity."""
    subprocess.run(
        ["git", "-c", "user.email=t@t", "-c", "user.name=t", *args],
        cwd=repo,
        check=True,
        capture_output=True,
    )


def write_allure_result(results_dir: Path, name: str, status: str, *story_ids: str) -> None:
    """Write one Allure ``*-result.json`` tagging the given story IDs (DI/UN)."""
    results_dir.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "story", "value": sid} for sid in story_ids]
    (results_dir / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


def write_design_doc(
    docs_dir: Path,
    context: str,
    *,
    satisfies: tuple[str, ...] = (),
    design_inputs: tuple[tuple[str, list[str]], ...] = (),
    realises: tuple[str, ...] = (),
) -> Path:
    """Write a per-context design document (``kind: design``).

    `design_inputs` is a tuple of ``(DI-id, [user needs it traces_to])``. Returns
    the written path.
    """
    docs_dir.mkdir(parents=True, exist_ok=True)
    if design_inputs:
        rows = "\n".join(
            f"  - {{id: {di}, text: {di} requirement, traces_to: [{', '.join(traces)}]}}"
            for di, traces in design_inputs
        )
        di_block = f"design_inputs:\n{rows}\n"
    else:
        di_block = "design_inputs: []\n"
    path = docs_dir / f"{context}.md"
    path.write_text(
        f"---\nid: SDS-{context}\nkind: design\ncontext: {context}\n"
        f"satisfies: [{', '.join(satisfies)}]\n{di_block}"
        f"realises: [{', '.join(realises)}]\n---\n\ndesign\n"
    )
    return path


def write_faithful_verdicts(
    dhf: Path, verdicts_dir: Path | None = None, reviewer: str = "reviewer (independent)"
) -> Path:
    """Write a `faithful` verdict per declared design input, hash-pinned the same
    way the gate computes it (so the verdicts are current). Returns the dir.
    """
    from rdm.record.allure import find_tests_dir
    from rdm.record.faithfulness import current_hashes
    from rdm.record.sdd import design_inputs

    out = verdicts_dir or (dhf / "faithfulness")
    out.mkdir(parents=True, exist_ok=True)
    inputs = design_inputs(dhf)
    hashes = current_hashes(inputs, find_tests_dir(dhf))
    for di in inputs:
        (out / f"{di['id']}-faithfulness.json").write_text(json.dumps({
            "design_input": di["id"],
            "verdict": "faithful",
            "reviewer": reviewer,
            "rationale": "the verifying test exercises the input",
            "test_hash": hashes[di["id"]],
        }))
    return out


def render_from_string(
    input_string=None,
    context=None,
    config=None,
    template_name=None,
    input_dictionary=None
):
    jinja2.clear_caches()
    if config is None:
        config = {}
    if template_name is None:
        template_name = 'input.md'
    if input_dictionary is None:
        input_dictionary = {}
    if input_string is not None:
        input_dictionary[template_name] = input_string
    if context is None:
        context = {}

    def load_string(template_name):
        return input_dictionary[template_name]

    loaders = [FunctionLoader(load_string)]

    return render_template_to_string(config, template_name, context, loaders=loaders)
