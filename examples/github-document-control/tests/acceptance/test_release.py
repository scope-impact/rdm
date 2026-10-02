"""Acceptance tests for the release context (dhf/documents/design/release.md):
the copies, the device history record and the verification evidence of each
release. See dc_support.py."""

from __future__ import annotations

import re

import pytest

allure = pytest.importorskip("allure")

from dc_support import DHF, EXAMPLE, attach, run_text, steps_of, verification_step, workflow  # noqa: E402

RELEASE = "release-documents.yml"


def _steps() -> list[dict]:
    return steps_of(RELEASE, "release-documents")


def _index(steps: list[dict], predicate) -> int:
    return next(i for i, step in enumerate(steps) if predicate(step))


def _publish(steps: list[dict]) -> int:
    return _index(steps, lambda s: "action-gh-release" in s.get("uses", ""))


@allure.story("DI-3")
@allure.label("output", ".github/workflows/release-documents.yml")
def test_release_is_tag_triggered_with_both_copy_forms() -> None:
    """DI-3: a doc-* tag triggers the release; rendered human-readable copies
    AND a complete electronic archive are attached."""
    flow, steps = workflow(RELEASE), _steps()
    attach("release steps", [step.get("name", step.get("uses")) for step in steps])

    with verification_step("pushing a doc-* tag triggers the release"):
        assert any(tag.startswith("doc-") for tag in flow["on"]["push"]["tags"])
    with verification_step("every controlled document is rendered to PDF by RDM's action, at the same release"):
        render = steps[_index(steps, lambda s: s.get("uses", "").startswith("scope-impact/rdm@"))]
        assert re.fullmatch(r"scope-impact/rdm@v\d+\.\d+\.\d+", render["uses"]), render["uses"]
        assert render["with"]["dhf_path"] == "dhf"
        makefile = (DHF / "Makefile").read_text()
        assert "rdm render $<" in makefile and "pandoc --defaults=./pandoc_pdf.yml" in makefile
        assert "cp -r dhf/release/. release/copies/" in run_text(steps)
    with verification_step("the complete electronic set is archived at the tag"):
        assert 'git archive --format=zip --output="release/document-set-$TAG.zip" "$TAG"' in run_text(steps)
    with verification_step("the copies are attached to the GitHub Release"):
        assert steps[_publish(steps)]["with"]["files"] == "release/**/*"


@allure.story("DI-8")
@allure.label("output", ".github/workflows/release-documents.yml")
def test_release_writes_a_device_history_record() -> None:
    """DI-8: the release workflow writes a manifest (tag, commit SHA, actor,
    timestamp, artifacts) and attaches it with the copies."""
    steps = _steps()
    at = _index(steps, lambda s: "device-history-record.json" in s.get("run", ""))
    manifest = steps[at]["run"]
    attach("manifest step", manifest)

    with verification_step("each field is bound to the actual release, not a constant"):
        for binding in (
            '--arg tag "${{ github.ref_name }}"',
            '--arg commit_sha "${{ github.sha }}"',
            '--arg released_by "${{ github.actor }}"',
            '--arg released_at "$(date -u',
            '--argjson artifacts "$(cd release && find . -type f',
        ):
            assert binding in manifest, f"manifest missing binding: {binding}"
        for field in ("$tag", "$commit_sha", "$released_by", "$released_at", "$artifacts"):
            assert field in manifest, f"manifest missing {field}"
    with verification_step("the manifest lists everything else the release attaches, then is attached with it"):
        assert "> release/device-history-record.json" in manifest
        assert all("release/" not in step.get("run", "") for step in steps[at + 1:_publish(steps)])
        assert at < _publish(steps)


@allure.story("DI-10")
@allure.label("output", ".github/workflows/release-documents.yml")
def test_a_release_is_published_only_when_verified_with_its_evidence() -> None:
    """DI-10: the release runs the acceptance tests and the release gate at
    the tag before anything is published, and attaches the evidence bundle
    with its verification report."""
    steps = _steps()
    attach("release steps", [step.get("name", step.get("uses")) for step in steps])
    verify = _index(steps, lambda s: "rdm story release-gate" in s.get("run", ""))
    bundle = _index(steps, lambda s: "rdm story evidence-bundle" in s.get("run", ""))
    manifest = _index(steps, lambda s: "device-history-record.json" in s.get("run", ""))
    publish = _publish(steps)

    with verification_step("the acceptance tests, verify and the release gate run at the tag"):
        run = steps[verify]["run"]
        assert "pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results" in run
        assert "rdm story verify --dhf dhf --allure-results dhf/allure-results" in run
        assert "rdm story release-gate --dhf dhf --allure-results dhf/allure-results" in run
    render = _index(steps, lambda s: s.get("uses", "").startswith("scope-impact/rdm@"))
    with verification_step("the bundle reads the record before the rendered copies land in dhf/release/"):
        assert bundle < render
    with verification_step("a failing release gate stops the release before anything is rendered or published"):
        assert verify < bundle < publish and verify == min(
            i for i, s in enumerate(steps) if "release" in s.get("run", "") or s.get("uses", "").startswith("scope"))
        assert not steps[verify].get("continue-on-error") and "|| true" not in steps[verify]["run"]
    with verification_step("the evidence bundle and its verification report go into the attached release/ set"):
        run = steps[bundle]["run"]
        assert "rdm story evidence-bundle --dhf dhf --allure-results dhf/allure-results -o release-evidence" in run
        assert "test -f release-evidence/verification_report.pdf" in run          # no report, no release
        assert "release/verification-report.pdf" in run and '"../release/evidence-$TAG.zip"' in run
        assert bundle < manifest < publish                                        # listed in the DHR, then attached
    with verification_step("the results and copies are ignored, so the run is of a clean commit"):
        ignored = set((EXAMPLE / ".gitignore").read_text().split())
        attach(".gitignore", sorted(ignored))
        assert {"dhf/allure-results/", "dhf/release/", "release/", "release-evidence/"} <= ignored
