"""Acceptance tests for the git/GitHub document-control example (see ../dhf/).

Each test is the acceptance criterion ("live BDD") for a design input declared
in ``dhf/documents/design/document_control.md``, tagged with `@allure.story`.
They verify the REAL configuration code and the REAL controlled document —
not fixtures — so a drift in the ruleset, workflow, CODEOWNERS, or SOP fails
the suite.

Run from the example directory (or with the repository root on sys.path):

    pytest tests/acceptance --alluredir=dhf/allure-results
    rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import io
import json
import shutil
from pathlib import Path

import pytest
import yaml

from rdm.gaps import audit_for_gaps
from rdm.render import render_template_to_file
from rdm.util import context_from_data_files, load_yaml

allure = pytest.importorskip("allure")

EXAMPLE = Path(__file__).parents[2]
SOP = EXAMPLE / "documents" / "document_control_procedure.md"
CHECKLIST = EXAMPLE / "checklists" / "part11_document_control.txt"


def _render_sop() -> str:
    import jinja2

    config = load_yaml(EXAMPLE / "config.yml")
    context = context_from_data_files([str(EXAMPLE / "data" / "history.yml")])
    out = io.StringIO()
    render_template_to_file(config, "documents/document_control_procedure.md", context, out,
                            loaders=[jinja2.FileSystemLoader(str(EXAMPLE))])
    return out.getvalue()


@allure.story("DI-1")
@allure.label("output", "github/rulesets/controlled-documents.json")
def test_ruleset_gates_the_default_branch() -> None:
    """DI-1: PR + code-owner approval, verified signatures, status checks, and
    immutable history are enforced by the ruleset configuration."""
    ruleset = json.loads((EXAMPLE / "github" / "rulesets" / "controlled-documents.json").read_text())

    assert ruleset["enforcement"] == "active"
    assert "~DEFAULT_BRANCH" in ruleset["conditions"]["ref_name"]["include"]

    rules = {rule["type"]: rule for rule in ruleset["rules"]}
    pr = rules["pull_request"]["parameters"]
    assert pr["required_approving_review_count"] >= 1     # independent approval
    assert pr["require_code_owner_review"] is True        # authorized signers only
    assert pr["dismiss_stale_reviews_on_push"] is True    # signature covers what merges
    assert "required_signatures" in rules                 # attributable authorship
    checks = rules["required_status_checks"]["parameters"]["required_status_checks"]
    assert {c["context"] for c in checks} >= {"render-documents", "part11-gap-analysis"}
    assert "non_fast_forward" in rules and "deletion" in rules  # audit trail immutable

    # CODEOWNERS routes every controlled path to the quality team.
    codeowners = (EXAMPLE / "github" / "CODEOWNERS").read_text()
    for controlled in ("/documents/", "/checklists/", "/github/", "/dhf/"):
        assert controlled in codeowners


@allure.story("DI-2")
@allure.label("output", "documents/document_control_procedure.md")
def test_controlled_documents_declare_identity_and_revision() -> None:
    """DI-2: every controlled document carries id + revision frontmatter."""
    docs = sorted((EXAMPLE / "documents").glob("*.md"))
    assert docs, "no controlled documents found"
    for doc in docs:
        text = doc.read_text()
        assert text.startswith("---"), f"{doc.name}: no frontmatter"
        frontmatter = yaml.safe_load(text.split("---", 2)[1])
        assert str(frontmatter.get("id", "")).strip(), f"{doc.name}: missing id"
        assert frontmatter.get("revision") is not None, f"{doc.name}: missing revision"


@allure.story("DI-3")
@allure.label("output", "github/workflows/release-documents.yml")
def test_release_is_tag_triggered_with_both_copy_forms() -> None:
    """DI-3: a doc-* tag triggers the release; rendered human-readable copies
    AND a complete electronic archive are attached."""
    workflow = yaml.safe_load((EXAMPLE / "github" / "workflows" / "release-documents.yml").read_text())

    trigger = workflow[True] if True in workflow else workflow["on"]  # yaml parses `on:` as True
    assert any(tag.startswith("doc-") for tag in trigger["push"]["tags"])

    steps = workflow["jobs"]["release-documents"]["steps"]
    run_text = "\n".join(step.get("run", "") for step in steps)
    assert "rdm render" in run_text and "pandoc" in run_text   # human-readable copies
    assert "git archive" in run_text                           # complete electronic copy
    assert any("release" in step.get("uses", "") for step in steps)  # attached to the Release


@allure.story("DI-4")
@allure.label("output", "documents/document_control_procedure.md")
def test_rendered_sop_embeds_generated_revision_history() -> None:
    """DI-4: the rendered SOP's history table comes from repository data."""
    rendered = _render_sop()
    assert "| 1 | 2026-07-04 | Example Author | Initial approved release" in rendered
    # The template row, not a hand-written table: the source has the loop,
    # the render has the data.
    assert "{% for entry in history.entries %}" not in rendered
    assert "{%- for entry in history.entries %}" in SOP.read_text()


@allure.story("DI-5")
@allure.label("output", "checklists/part11_document_control.txt")
def test_sop_addresses_every_part11_checklist_item(tmp_path: Path) -> None:
    """DI-5: gap analysis over the SOP with the Part 11 checklist reports full
    coverage — and the check is falsifiable (a stripped SOP fails it)."""
    assert audit_for_gaps(str(CHECKLIST), [str(SOP)], coverage=False) == 0

    # Falsifiability: remove one Part 11 reference and the audit must fail.
    stripped = tmp_path / "sop_missing_audit_trail.md"
    shutil.copy(SOP, stripped)
    stripped.write_text(stripped.read_text().replace("[[P11:11.10e]]", ""))
    assert audit_for_gaps(str(CHECKLIST), [str(stripped)], coverage=False) == 3
