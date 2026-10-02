"""What the acceptance tests share: the example's paths, its files read as data,
rendering, and attaching what a verification step checked.

A test verifies one design input, tagged `@allure.story("DI-n")`. The design
input is the acceptance criterion, accepted as a whole; the test's
verification steps are its own checks, and each attaches what it looked at.
The tests read the REAL configuration code and the REAL controlled documents,
not fixtures, so a drift in the ruleset, a workflow, CODEOWNERS or a procedure
fails the suite.
"""

from __future__ import annotations

import io
import json
import subprocess
import sys
from pathlib import Path

import allure
import jinja2
import yaml

from rdm.render import render_template_to_file
from rdm.util import context_from_data_files, load_yaml

verification_step = allure.step

EXAMPLE = Path(__file__).parents[2]
DHF = EXAMPLE / "dhf"
GITHUB = EXAMPLE / ".github"
PROCEDURES = DHF / "documents" / "procedures"
SOP = PROCEDURES / "document_control_procedure.md"
DMR_INDEX = PROCEDURES / "device_master_record_index.md"
CHECKLIST = EXAMPLE / "checklists" / "part11_document_control.txt"
SHAPES = DHF / "shapes" / "document_control.ttl"
DATA = sorted(str(path) for path in (DHF / "data").glob("*.yml") if path.name != "verification.yml")


def attach(name: str, content) -> None:
    """Attach text as text, anything else as JSON."""
    if isinstance(content, str):
        allure.attach(content, name=name, attachment_type=allure.attachment_type.TEXT)
    else:
        allure.attach(json.dumps(content, indent=2, sort_keys=True), name=name,
                      attachment_type=allure.attachment_type.JSON)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text())


def workflow(name: str) -> dict:
    data = yaml.safe_load((GITHUB / "workflows" / name).read_text())
    data["on"] = data.pop(True, data.get("on"))  # yaml reads the key `on:` as True
    return data


def steps_of(name: str, job: str) -> list[dict]:
    return workflow(name)["jobs"][job]["steps"]


def run_text(steps: list[dict]) -> str:
    return "\n".join(step.get("run", "") for step in steps)


def frontmatter(doc: Path) -> dict:
    return yaml.safe_load(doc.read_text().split("---", 2)[1])


def render(template: Path) -> str:
    """Render a controlled document as `make` does: the DHF's config and data."""
    out = io.StringIO()
    render_template_to_file(load_yaml(DHF / "config.yml"), str(template.relative_to(DHF)),
                            context_from_data_files(DATA), out,
                            loaders=[jinja2.FileSystemLoader(str(DHF))])
    return out.getvalue()


def rdm(*args: str, cwd: Path = EXAMPLE) -> subprocess.CompletedProcess:
    """Run the rdm CLI of this Python environment."""
    return subprocess.run([sys.executable, "-m", "rdm.main", *args], cwd=cwd, capture_output=True, text=True)
