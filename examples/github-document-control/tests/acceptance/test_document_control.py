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


def _render(template: str, data_files: list[str]) -> str:
    import jinja2

    config = load_yaml(EXAMPLE / "config.yml")
    context = context_from_data_files(data_files)
    out = io.StringIO()
    render_template_to_file(config, template, context, out,
                            loaders=[jinja2.FileSystemLoader(str(EXAMPLE))])
    return out.getvalue()


def _render_sop() -> str:
    return _render("documents/document_control_procedure.md",
                   [str(EXAMPLE / "data" / "history.yml")])


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


@allure.story("DI-6")
@allure.label("output", "github/settings.json")
def test_merge_behavior_is_configuration_code() -> None:
    """DI-6: merge-commit-only settings are declared as code, and setup.sh
    applies and drift-checks them against the live repository."""
    settings = json.loads((EXAMPLE / "github" / "settings.json").read_text())
    assert settings["allow_merge_commit"] is True      # the reviewed SHA survives
    assert settings["allow_squash_merge"] is False     # squash rewrites it
    assert settings["allow_rebase_merge"] is False     # rebase rewrites it
    assert settings["delete_branch_on_merge"] is True

    setup = (EXAMPLE / "setup.sh").read_text()
    assert "settings.json" in setup
    assert '--method PATCH "repos/$REPO" --input "$SETTINGS_FILE"' in setup  # apply path
    # The SETTINGS-specific drift check (not just the ruleset's): the live
    # repo object is projected onto the declared fields and compared for
    # EXACT equality. A containment test is not a drift check — jq `contains`
    # matches substrings and array subsets, so a changed value could pass.
    assert 'live_settings="$(gh api "repos/$REPO")"' in setup
    assert 'matches_declared "$want_settings"' in setup
    assert "def prune($w):" in setup                  # projected-equality helper
    assert "contains($want)" not in setup             # subset matching banned


@allure.story("DI-7")
@allure.label("output", "documents/device_master_record_index.md")
def test_dmr_index_lists_the_specification_set_from_data() -> None:
    """DI-7: the DMR index is a controlled document rendered from repository
    data, listing each controlled document with identity and revision."""
    dmr_data = yaml.safe_load((EXAMPLE / "data" / "dmr.yml").read_text())
    rendered = _render(
        "documents/device_master_record_index.md",
        [str(EXAMPLE / "data" / "history.yml"), str(EXAMPLE / "data" / "dmr.yml")],
    )

    for entry in dmr_data["entries"]:
        # Every indexed document is listed with its identity and revision...
        assert f"| {entry['id']} |" in rendered
        assert f"`{entry['path']}`" in rendered
        # ...and the index agrees with the document's OWN frontmatter (the
        # record is consistent, not just present).
        doc = EXAMPLE / entry["path"]
        frontmatter = yaml.safe_load(doc.read_text().split("---", 2)[1])
        assert frontmatter["id"] == entry["id"]
        assert frontmatter["revision"] == entry["revision"]

    # ENUMERATING the specification set means completeness: every controlled
    # document under documents/ is indexed (an un-indexed spec fails).
    indexed_ids = {entry["id"] for entry in dmr_data["entries"]}
    for doc in sorted((EXAMPLE / "documents").glob("*.md")):
        doc_id = yaml.safe_load(doc.read_text().split("---", 2)[1])["id"]
        assert doc_id in indexed_ids, f"{doc.name} ({doc_id}) missing from the DMR index"

    # The index is itself controlled (id + revision) and generated (loop in
    # source, data in render).
    index_src = (EXAMPLE / "documents" / "device_master_record_index.md").read_text()
    assert "{%- for entry in dmr.entries %}" in index_src
    assert "{%- for" not in rendered


@allure.story("DI-8")
@allure.label("output", "github/workflows/release-documents.yml")
def test_release_writes_a_device_history_record() -> None:
    """DI-8: the release workflow writes a manifest (tag, commit SHA, actor,
    timestamp, artifacts) and attaches it with the copies."""
    workflow = yaml.safe_load((EXAMPLE / "github" / "workflows" / "release-documents.yml").read_text())
    steps = workflow["jobs"]["release-documents"]["steps"]
    run_text = "\n".join(step.get("run", "") for step in steps)

    manifest = next(s for s in steps if "device-history-record.json" in s.get("run", ""))
    # The fields must be BOUND to the actual release context, not just named:
    # a manifest recording a constant SHA/tag/actor would be a false record.
    for binding in (
        '--arg tag "${{ github.ref_name }}"',
        '--arg commit_sha "${{ github.sha }}"',
        '--arg released_by "${{ github.actor }}"',
        '--arg released_at "$(date -u',
        '--argjson artifacts "$(ls release',
    ):
        assert binding in manifest["run"], f"manifest missing binding: {binding}"
    for field in ("$tag", "$commit_sha", "$released_by", "$released_at", "$artifacts"):
        assert field in manifest["run"], f"manifest missing {field}"
    # Written under release/, which the release step attaches wholesale.
    assert "> release/device-history-record.json" in run_text
    attach = next(s for s in steps if "release" in s.get("uses", ""))
    assert attach["with"]["files"] == "release/*"
