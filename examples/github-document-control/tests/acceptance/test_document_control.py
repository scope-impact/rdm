"""Acceptance tests for the git/GitHub document-control example (see ../dhf/).

Each test verifies one design input declared in
``dhf/documents/design/document_control.md`` and is tagged with it
(`@allure.story("DI-n")`). The design input is the acceptance criterion,
accepted as a whole; a test's verification steps are its own checks, and each
attaches what it looked at. The tests read the REAL configuration code and the
REAL controlled documents, not fixtures, so a drift in the ruleset, a workflow,
CODEOWNERS or the SOP fails the suite. ``conftest.py`` enables RDM's pytest
plugin, which labels each run from the record.

Run from the example directory (or with the repository root on sys.path):

    pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
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
verification_step = allure.step

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


def _attach(name: str, content) -> None:
    """Attach what a verification step checked: text as text, anything else as JSON."""
    if isinstance(content, str):
        allure.attach(content, name=name, attachment_type=allure.attachment_type.TEXT)
    else:
        allure.attach(json.dumps(content, indent=2, sort_keys=True), name=name,
                      attachment_type=allure.attachment_type.JSON)


def _json(*parts: str) -> dict:
    return json.loads(EXAMPLE.joinpath(*parts).read_text())


def _workflow(name: str) -> dict:
    return yaml.safe_load((EXAMPLE / "github" / "workflows" / name).read_text())


def _frontmatter(doc: Path) -> dict:
    return yaml.safe_load(doc.read_text().split("---", 2)[1])


def _render_sop() -> str:
    return _render("documents/document_control_procedure.md",
                   [str(EXAMPLE / "data" / "history.yml")])


@allure.story("DI-1")
@allure.label("output", "github/rulesets/controlled-documents.json")
def test_ruleset_gates_the_default_branch() -> None:
    """DI-1: PR + code-owner approval, verified signatures, status checks, and
    immutable history are enforced by the ruleset configuration."""
    ruleset = _json("github", "rulesets", "controlled-documents.json")
    _attach("controlled-documents.json", ruleset)
    rules = {rule["type"]: rule for rule in ruleset["rules"]}

    with verification_step("the ruleset is active on the default branch"):
        assert ruleset["enforcement"] == "active"
        assert "~DEFAULT_BRANCH" in ruleset["conditions"]["ref_name"]["include"]
    with verification_step("a pull request needs a current code-owner approval"):
        pr = rules["pull_request"]["parameters"]
        assert pr["required_approving_review_count"] >= 1     # independent approval
        assert pr["require_code_owner_review"] is True        # authorized signers only
        assert pr["dismiss_stale_reviews_on_push"] is True    # signature covers what merges
    with verification_step("commits carry verified signatures"):
        assert "required_signatures" in rules                 # attributable authorship
    with verification_step("status checks must pass on the up-to-date branch"):
        checks = rules["required_status_checks"]["parameters"]
        assert checks["strict_required_status_checks_policy"] is True
        assert checks["required_status_checks"]
    with verification_step("history cannot be rewritten and the branch cannot be deleted"):
        assert "non_fast_forward" in rules and "deletion" in rules
    with verification_step("CODEOWNERS routes every controlled path to the quality team"):
        codeowners = (EXAMPLE / "github" / "CODEOWNERS").read_text()
        _attach("CODEOWNERS", codeowners)
        for controlled in ("/documents/", "/checklists/", "/github/", "/dhf/"):
            assert controlled in codeowners


@allure.story("DI-2")
@allure.label("output", "documents/document_control_procedure.md")
def test_controlled_documents_declare_identity_and_revision() -> None:
    """DI-2: every controlled document carries id + revision frontmatter."""
    docs = sorted((EXAMPLE / "documents").glob("*.md"))
    with verification_step("there are controlled documents"):
        assert docs, "no controlled documents found"
    identities = {}
    for doc in docs:
        with verification_step(f"{doc.name} declares its id and revision"):
            assert doc.read_text().startswith("---"), f"{doc.name}: no frontmatter"
            frontmatter = _frontmatter(doc)
            identities[doc.name] = {"id": frontmatter.get("id"), "revision": frontmatter.get("revision")}
            assert str(frontmatter.get("id", "")).strip(), f"{doc.name}: missing id"
            assert frontmatter.get("revision") is not None, f"{doc.name}: missing revision"
    _attach("identity and revision", identities)


@allure.story("DI-3")
@allure.label("output", "github/workflows/release-documents.yml")
def test_release_is_tag_triggered_with_both_copy_forms() -> None:
    """DI-3: a doc-* tag triggers the release; rendered human-readable copies
    AND a complete electronic archive are attached."""
    workflow = _workflow("release-documents.yml")
    steps = workflow["jobs"]["release-documents"]["steps"]
    run_text = "\n".join(step.get("run", "") for step in steps)
    _attach("release steps", [step.get("name", step.get("uses")) for step in steps])

    with verification_step("pushing a doc-* tag triggers the release"):
        trigger = workflow[True] if True in workflow else workflow["on"]  # yaml parses `on:` as True
        assert any(tag.startswith("doc-") for tag in trigger["push"]["tags"])
    with verification_step("each controlled document is rendered to a human-readable copy"):
        assert "rdm render" in run_text and "pandoc" in run_text
    with verification_step("the complete electronic set is archived at the tag"):
        assert "git archive" in run_text
    with verification_step("the copies are attached to the GitHub Release"):
        assert any("release" in step.get("uses", "") for step in steps)


@allure.story("DI-4")
@allure.label("output", "documents/document_control_procedure.md")
def test_rendered_sop_embeds_generated_revision_history() -> None:
    """DI-4: the rendered SOP's history table comes from repository data."""
    rendered = _render_sop()
    history = yaml.safe_load((EXAMPLE / "data" / "history.yml").read_text())
    _attach("data/history.yml", history)
    with verification_step("every entry of the history data is a row of the rendered SOP"):
        for entry in history["entries"]:
            assert f"| {entry['revision']} | {entry['date']} | {entry['author']} | {entry['change']}" in rendered
    with verification_step("the table is a template loop in the source, not a hand-written table"):
        assert "{%- for entry in history.entries %}" in SOP.read_text()
        assert "{% for entry in history.entries %}" not in rendered
    with verification_step("the latest history entry is the SOP's declared revision"):
        assert history["entries"][-1]["revision"] == _frontmatter(SOP)["revision"]


@allure.story("DI-5")
@allure.label("output", "checklists/part11_document_control.txt")
def test_sop_addresses_every_part11_checklist_item(tmp_path: Path) -> None:
    """DI-5: gap analysis over the SOP with the Part 11 checklist reports full
    coverage — and the check is falsifiable (a stripped SOP fails it)."""
    _attach("part11_document_control.txt", CHECKLIST.read_text())
    with verification_step("the gap analysis reports no missing checklist item"):
        assert audit_for_gaps(str(CHECKLIST), [str(SOP)], coverage=False) == 0
    with verification_step("removing one Part 11 reference makes the gap analysis fail"):
        stripped = tmp_path / "sop_missing_audit_trail.md"
        shutil.copy(SOP, stripped)
        stripped.write_text(stripped.read_text().replace("[[P11:11.10e]]", ""))
        assert audit_for_gaps(str(CHECKLIST), [str(stripped)], coverage=False) == 3


@allure.story("DI-6")
@allure.label("output", "github/settings.json")
def test_merge_behavior_is_configuration_code() -> None:
    """DI-6: merge-commit-only settings are declared as code, and setup.sh
    applies and drift-checks them against the live repository."""
    settings = _json("github", "settings.json")
    _attach("settings.json", settings)
    setup = (EXAMPLE / "setup.sh").read_text()

    with verification_step("only merge commits, so the reviewed SHA survives; head branches deleted"):
        assert settings["allow_merge_commit"] is True
        assert settings["allow_squash_merge"] is False     # squash rewrites it
        assert settings["allow_rebase_merge"] is False     # rebase rewrites it
        assert settings["delete_branch_on_merge"] is True
    with verification_step("setup.sh applies the settings to the repository"):
        assert '--method PATCH "repos/$REPO" --input "$SETTINGS_FILE"' in setup
    with verification_step("setup.sh --check compares the live settings for exact equality"):
        # The live repo object is projected onto the declared fields and
        # compared exactly: jq `contains` matches substrings and array
        # subsets, so a changed value could pass a containment test.
        assert 'live_settings="$(gh api "repos/$REPO")"' in setup
        assert 'matches_declared "$want_settings"' in setup
        assert "def prune($w):" in setup
        assert "contains($want)" not in setup


@allure.story("DI-7")
@allure.label("output", "documents/device_master_record_index.md")
def test_dmr_index_lists_the_specification_set_from_data() -> None:
    """DI-7: the DMR index is a controlled document rendered from repository
    data, listing each controlled document with identity and revision."""
    dmr_data = yaml.safe_load((EXAMPLE / "data" / "dmr.yml").read_text())
    _attach("data/dmr.yml", dmr_data)
    rendered = _render(
        "documents/device_master_record_index.md",
        [str(EXAMPLE / "data" / "history.yml"), str(EXAMPLE / "data" / "dmr.yml")],
    )

    for entry in dmr_data["entries"]:
        with verification_step(f"{entry['id']} is listed, and agrees with its own frontmatter"):
            assert f"| {entry['id']} |" in rendered
            assert f"`{entry['path']}`" in rendered
            frontmatter = _frontmatter(EXAMPLE / entry["path"])
            assert (frontmatter["id"], frontmatter["revision"]) == (entry["id"], entry["revision"])
    with verification_step("every controlled document under documents/ is indexed"):
        indexed_ids = {entry["id"] for entry in dmr_data["entries"]}
        for doc in sorted((EXAMPLE / "documents").glob("*.md")):
            assert _frontmatter(doc)["id"] in indexed_ids, f"{doc.name} missing from the DMR index"
    with verification_step("the index is rendered from the data, not hand-written"):
        index_src = (EXAMPLE / "documents" / "device_master_record_index.md").read_text()
        assert "{%- for entry in dmr.entries %}" in index_src
        assert "{%- for" not in rendered


@allure.story("DI-8")
@allure.label("output", "github/workflows/release-documents.yml")
def test_release_writes_a_device_history_record() -> None:
    """DI-8: the release workflow writes a manifest (tag, commit SHA, actor,
    timestamp, artifacts) and attaches it with the copies."""
    steps = _workflow("release-documents.yml")["jobs"]["release-documents"]["steps"]
    manifest = next(s for s in steps if "device-history-record.json" in s.get("run", ""))
    _attach("manifest step", manifest["run"])

    with verification_step("each field is bound to the actual release, not a constant"):
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
    with verification_step("the manifest is written under release/, which is attached wholesale"):
        assert "> release/device-history-record.json" in manifest["run"]
        attach = next(s for s in steps if "release" in s.get("uses", ""))
        assert attach["with"]["files"] == "release/*"


def _reported_checks(workflow: dict) -> set[str]:
    """The status-check names a workflow reports. A job that calls a reusable
    workflow reports `<job> / <called job>`; RDM's gates.yml has one job, `gates`."""
    return {f"{job_id} / gates" if "gates.yml@" in job.get("uses", "") else job.get("name", job_id)
            for job_id, job in workflow["jobs"].items()}


@allure.story("DI-9")
@allure.label("output", "github/workflows/design-controls.yml")
def test_every_pull_request_runs_the_checks_the_ruleset_requires() -> None:
    """DI-9: the design controls (RDM's reusable gates, pinned) and the Part 11
    gap analysis run on every pull request, and the ruleset requires exactly
    the checks that workflow reports."""
    workflow = _workflow("design-controls.yml")
    jobs = workflow["jobs"]
    ruleset = _json("github", "rulesets", "controlled-documents.json")
    rules = {rule["type"]: rule for rule in ruleset["rules"]}
    required = {c["context"] for c in rules["required_status_checks"]["parameters"]["required_status_checks"]}
    reported = _reported_checks(workflow)
    _attach("checks", {"required by the ruleset": sorted(required), "reported by the workflow": sorted(reported)})

    with verification_step("the workflow runs on every pull request"):
        trigger = workflow[True] if True in workflow else workflow["on"]
        assert "pull_request" in trigger
    with verification_step("the design controls are RDM's reusable gates, pinned to a released tag"):
        gates = jobs["design-controls"]
        repository, _, ref = gates["uses"].partition("@")
        assert repository == "scope-impact/rdm/.github/workflows/gates.yml"
        assert ref.startswith("v") and ref[1:].replace(".", "").isdigit(), ref
        assert gates["with"]["rdm-ref"] == ref                      # RDM installed at the same tag
        assert gates["with"].get("acceptance-tests", True) is not False
        assert gates["with"].get("release-gate", True) is not False
    with verification_step("the gap analysis runs over the SOP with the Part 11 checklist"):
        gap = "\n".join(step.get("run", "") for step in jobs["part11-gap-analysis"]["steps"])
        assert ("rdm gap checklists/part11_document_control.txt documents/document_control_procedure.md"
                in gap)
        assert f"scope-impact/rdm@{ref}" in gap                     # the same RDM as the gates
    with verification_step("the ruleset requires exactly the checks the workflow reports"):
        assert required == reported == {"design-controls / gates", "part11-gap-analysis"}


@allure.story("DI-10")
@allure.label("output", "github/workflows/release-documents.yml")
def test_a_release_is_published_only_when_verified_with_its_report() -> None:
    """DI-10: the release runs the acceptance tests and the release gate at
    the tag before anything is published, and attaches the verification report."""
    steps = _workflow("release-documents.yml")["jobs"]["release-documents"]["steps"]
    _attach("release steps", [step.get("name", step.get("uses")) for step in steps])

    def index(predicate) -> int:
        return next(i for i, step in enumerate(steps) if predicate(step))

    verify = index(lambda s: "rdm story release-gate" in s.get("run", ""))
    report = index(lambda s: "rdm story evidence-report" in s.get("run", ""))
    manifest = index(lambda s: "device-history-record.json" in s.get("run", ""))
    publish = index(lambda s: "release" in s.get("uses", ""))
    with verification_step("the acceptance tests and the release gate run at the tag"):
        run = steps[verify]["run"]
        assert "pytest tests/acceptance" in run and "--alluredir=dhf/allure-results" in run
        assert "rdm story release-gate --dhf dhf --allure-results dhf/allure-results" in run
    with verification_step("a failing release gate stops the release before it is published"):
        assert verify < publish
        assert not steps[verify].get("continue-on-error") and "|| true" not in steps[verify]["run"]
    with verification_step("the verification report is written into the attached release/ set"):
        assert "-o release/verification-report.pdf" in steps[report]["run"]
        assert report < manifest < publish                          # listed in the DHR, then attached
    with verification_step("the results and copies are ignored, so the run is of a clean commit"):
        ignored = (EXAMPLE / ".gitignore").read_text().split()
        _attach(".gitignore", ignored)
        assert {"dhf/allure-results/", "release/"} <= set(ignored)
