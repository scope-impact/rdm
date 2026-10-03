"""Acceptance tests for the approval context (dhf/documents/design/approval.md):
what a change must pass to reach the default branch. See dc_support.py."""

from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from dc_support import EXAMPLE, GITHUB, attach, read_json, run_text, verification_step, workflow  # noqa: E402

RULESET = GITHUB / "rulesets" / "controlled-documents.json"
SETTINGS = GITHUB / "settings.json"
SETUP = EXAMPLE / "setup.sh"


def _rules() -> dict:
    return {rule["type"]: rule for rule in read_json(RULESET)["rules"]}


@allure.story("DI-1")
@allure.label("output", ".github/rulesets/controlled-documents.json")
def test_ruleset_gates_the_default_branch() -> None:
    """DI-1: PR + code-owner approval, verified signatures, status checks, and
    immutable history are enforced by the ruleset configuration."""
    ruleset = read_json(RULESET)
    attach("controlled-documents.json", ruleset)
    rules = _rules()

    with verification_step("the ruleset is active on the default branch"):
        assert ruleset["enforcement"] == "active"
        assert "~DEFAULT_BRANCH" in ruleset["conditions"]["ref_name"]["include"]
    with verification_step("a pull request needs a current code-owner approval"):
        pr = rules["pull_request"]["parameters"]
        assert pr["required_approving_review_count"] >= 1     # independent approval
        assert pr["require_code_owner_review"] is True        # authorized signers only
        assert pr["dismiss_stale_reviews_on_push"] is True    # signature covers what merges
        assert pr["require_last_push_approval"] is True       # the last pusher cannot approve
    with verification_step("commits carry verified signatures"):
        assert "required_signatures" in rules
    with verification_step("status checks must pass on the up-to-date branch"):
        checks = rules["required_status_checks"]["parameters"]
        assert checks["strict_required_status_checks_policy"] is True
        assert checks["required_status_checks"]
    with verification_step("history cannot be rewritten and the branch cannot be deleted"):
        assert "non_fast_forward" in rules and "deletion" in rules
    with verification_step("CODEOWNERS routes every controlled path to the quality team"):
        codeowners = (GITHUB / "CODEOWNERS").read_text()
        attach("CODEOWNERS", codeowners)
        for controlled in ("/dhf/", "/checklists/", "/.github/", "/setup.sh", "/tests/"):
            assert re.search(rf"^{re.escape(controlled)}\s+@", codeowners, re.M), controlled


@allure.story("DI-6")
@allure.label("output", ".github/settings.json")
def test_merge_behavior_is_configuration_code() -> None:
    """DI-6: merge-commit-only settings are declared as code, and setup.sh
    applies and drift-checks them against the live repository."""
    settings = read_json(SETTINGS)
    attach("settings.json", settings)
    setup = SETUP.read_text()

    with verification_step("only merge commits, so the reviewed SHA survives; head branches deleted"):
        assert settings["allow_merge_commit"] is True
        assert settings["allow_squash_merge"] is False     # squash rewrites it
        assert settings["allow_rebase_merge"] is False     # rebase rewrites it
        assert settings["delete_branch_on_merge"] is True
    with verification_step("setup.sh applies the settings to the repository"):
        assert 'SETTINGS_FILE="$HERE/.github/settings.json"' in setup
        assert '--method PATCH "repos/$REPO" --input "$SETTINGS_FILE"' in setup
    with verification_step("setup.sh --check compares the live settings for exact equality"):
        # The live repo object is projected onto the declared fields and
        # compared exactly: jq `contains` matches substrings and array
        # subsets, so a changed value could pass a containment test.
        assert 'live_settings="$(gh api "repos/$REPO")"' in setup
        assert 'matches_declared "$want_settings"' in setup
        assert "def prune($w):" in setup
        assert "contains($want)" not in setup


def _reported_checks(flow: dict) -> set[str]:
    """The status-check names a workflow reports. A job that calls a reusable
    workflow reports `<job> / <called job>`; RDM's gates.yml has one job, `gates`."""
    return {f"{job_id} / gates" if "gates.yml@" in job.get("uses", "") else job.get("name", job_id)
            for job_id, job in flow["jobs"].items()}


@allure.story("DI-9")
@allure.label("output", ".github/workflows/design-controls.yml")
def test_every_pull_request_runs_the_checks_the_ruleset_requires() -> None:
    """DI-9: the design controls (RDM's reusable gates, pinned) and the Part 11
    gap analysis run on every pull request, and the ruleset requires exactly
    the checks that workflow reports."""
    flow = workflow("design-controls.yml")
    jobs = flow["jobs"]
    required = {c["context"] for c in _rules()["required_status_checks"]["parameters"]["required_status_checks"]}
    reported = _reported_checks(flow)
    attach("checks", {"required by the ruleset": sorted(required), "reported by the workflow": sorted(reported)})

    with verification_step("the workflow runs on every pull request"):
        assert "pull_request" in flow["on"]
    with verification_step("the design controls are RDM's reusable gates, pinned to a released tag"):
        gates = jobs["design-controls"]
        repository, _, ref = gates["uses"].partition("@")
        assert repository == "scope-impact/rdm/.github/workflows/gates.yml"
        assert re.fullmatch(r"v\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?", ref), ref
        assert gates["with"]["rdm-ref"] == ref                      # RDM installed at the same tag
        assert gates["with"].get("acceptance-tests", True) is not False
        assert gates["with"].get("release-gate", True) is not False
        assert gates["with"]["checklists"] == "checklists/part11_document_control.txt"
    with verification_step("the record checks run the Part 11 gap analysis and this system's graph rules"):
        checks = run_text(jobs["record-checks"]["steps"])
        assert ("rdm gap checklists/part11_document_control.txt "
                "dhf/documents/procedures/document_control_procedure.md") in checks
        assert "--shapes dhf/shapes/document_control.ttl" in checks
        assert f"scope-impact/rdm@{ref}" in checks                  # the same RDM as the gates
    with verification_step("the ruleset requires exactly the checks the workflow reports"):
        assert required == reported == {"design-controls / gates", "record-checks"}


def _fake_gh(tmp_path: Path, live_ruleset: dict, live_settings: dict) -> dict[str, str]:
    """A `gh` on PATH that answers the three calls `setup.sh --check` makes
    from the given live configuration, as GitHub's API would."""
    (tmp_path / "ruleset.json").write_text(json.dumps({"id": 42, **live_ruleset}))
    (tmp_path / "settings.json").write_text(json.dumps({"full_name": "acme/docs", **live_settings}))
    gh = tmp_path / "bin" / "gh"
    gh.parent.mkdir()
    gh.write_text(f"""#!/usr/bin/env bash
case "$2" in
  repos/acme/docs/rulesets) echo '[{{"id": 42, "name": "{live_ruleset["name"]}"}}]' | jq -r "${{4}}" ;;
  repos/acme/docs/rulesets/42) cat {tmp_path / "ruleset.json"} ;;
  repos/acme/docs) cat {tmp_path / "settings.json"} ;;
  *) echo "unexpected gh call: $*" >&2; exit 3 ;;
esac
""")
    gh.chmod(0o755)
    return {**os.environ, "PATH": f"{gh.parent}{os.pathsep}{os.environ['PATH']}"}


def _check(env: dict[str, str]) -> subprocess.CompletedProcess:
    return subprocess.run(["bash", str(SETUP), "--check", "acme/docs"], env=env, capture_output=True, text=True)


@allure.story("DI-11")
@allure.label("output", ".github/workflows/drift-audit.yml")
def test_a_daily_audit_fails_on_any_drift(tmp_path: Path) -> None:
    """DI-11: a scheduled audit compares the live ruleset and repository
    settings with the checked-in configuration at least daily, and fails on
    any difference."""
    flow = workflow("drift-audit.yml")
    steps = flow["jobs"]["drift-audit"]["steps"]
    attach("drift-audit.yml", flow)

    with verification_step("the audit is scheduled at least daily"):
        minute, hour, day, month, weekday = flow["on"]["schedule"][0]["cron"].split()
        assert minute.isdigit() and hour.isdigit() and (day, month, weekday) == ("*", "*", "*")
    with verification_step("it runs setup.sh --check on this repository"):
        assert './setup.sh --check "$REPO"' in run_text(steps)
        assert any(step.get("env", {}).get("REPO") == "${{ github.repository }}" for step in steps)

    ruleset, settings = read_json(RULESET), read_json(SETTINGS)
    with verification_step("the live configuration as declared passes the audit"):
        (tmp_path / "same").mkdir()
        same = _check(_fake_gh(tmp_path / "same", ruleset, settings))
        attach("audit: no drift", same.stdout + same.stderr)
        assert same.returncode == 0, same.stdout + same.stderr
    with verification_step("a weakened ruleset fails the audit"):
        weakened = json.loads(json.dumps(ruleset))
        next(r for r in weakened["rules"] if r["type"] == "pull_request")["parameters"][
            "required_approving_review_count"] = 0
        (tmp_path / "ruleset").mkdir()
        drift = _check(_fake_gh(tmp_path / "ruleset", weakened, settings))
        attach("audit: review count lowered to 0", drift.stdout + drift.stderr)
        assert drift.returncode == 1 and "DRIFT" in drift.stderr
    with verification_step("a changed merge setting fails the audit"):
        (tmp_path / "settings").mkdir()
        drift = _check(_fake_gh(tmp_path / "settings", ruleset, {**settings, "allow_squash_merge": True}))
        attach("audit: squash merge enabled", drift.stdout + drift.stderr)
        assert drift.returncode == 1 and "DRIFT" in drift.stderr
