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


def write_allure_result(results_dir: Path, name: str, status: str, *user_need_ids: str) -> None:
    """Write one Allure ``*-result.json`` tagging the given user-need IDs."""
    results_dir.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "story", "value": uid} for uid in user_need_ids]
    (results_dir / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


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
