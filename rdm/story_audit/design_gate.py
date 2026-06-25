"""
Design-controls gate: ensure design input and design review exist and are
approved before design work transitions into backlog/implementation tasks.

Traceability model enforced here (ADR 0001):

    User Need (registry: V&V plan)  <- satisfies -  SDD (per bounded context)
                                                      |
                                       Acceptance Criteria (Allure tags on tests)

The gate verifies that, for the design history file (DHF), both a Design Input
document and a Design Review document exist and have been completed/approved
(committed) in version control. As soft checks it reconciles the user-need
registry against the SDDs that `satisfy` it (SDDs are discovered as multiple
documents, under an `sdd/` folder or named `*sdd*`) and against the Allure tags
on the tests.

Usage:
    rdm story design-gate                       # check ./dhf
    rdm story design-gate --dhf dhf

Exit codes:
    0 - gate passed
    1 - gate failed (a required artifact is missing or incomplete)
    2 - bad invocation (path not found)

Requires: pip install rdm[story-audit]
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record import allure
from rdm.record.reconcile import relevant_orphans
from rdm.record.sdd import (
    find_sdds,
    registry_user_needs,
    satisfies_by_sdd,
)
from rdm.record.sdd import find_dhf_doc as _find_doc

# Documents the gate requires, by id/basename. The basename matches the
# template filenames installed by `rdm init` (see rdm/init_files/documents/).
DESIGN_INPUT_DOC = "design_input.md"
DESIGN_REVIEW_DOC = "design_review.md"

# A document is considered "incomplete" while it still contains scaffold
# placeholders. These markers come from the init templates.
PLACEHOLDER_MARKERS = ("TODO", "ENDTODO")


@dataclass
class ArtifactCheck:
    """Result of checking a single required design-control artifact."""

    name: str
    path: Path
    exists: bool
    complete: bool
    reasons: list[str] = field(default_factory=list)
    # Version-control state of the document:
    #   True  -> has uncommitted changes (current revision is not yet approved)
    #   False -> clean and tracked (the committed revision is the approved one)
    #   None  -> cannot be determined (not a git work tree, or git unavailable)
    uncommitted: bool | None = None

    @property
    def ok(self) -> bool:
        # Approval is the version-control record, not anything inside the file.
        # A document with uncommitted changes has not been approved, so it
        # fails the gate. An undeterminable state (None) does not fail here; it
        # is surfaced as a warning by the command instead.
        return self.exists and self.complete and self.uncommitted is not True


@dataclass
class GateResult:
    """Aggregate result of the design gate."""

    artifacts: list[ArtifactCheck] = field(default_factory=list)
    task_warnings: list[str] = field(default_factory=list)
    traceability_warnings: list[str] = field(default_factory=list)
    verification_warnings: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        # Traceability gaps are reported as warnings, not failures: the design
        # gate runs before implementation, so the verifying tests (and their
        # Allure tags) legitimately may not exist yet.
        return all(a.ok for a in self.artifacts)


def has_uncommitted_changes(path: Path) -> bool | None:
    """Return the version-control state of a file.

    Approval is the version-control record (the reviewed PR/commit that merged
    the revision), so a document with uncommitted changes has not yet been
    approved, and a committed change after a prior approval re-opens approval.

    Returns:
        True  - the file has uncommitted changes (modified or untracked)
        False - the file is clean and tracked (committed revision == working copy)
        None  - cannot be determined (not a git work tree, or git unavailable)

    Note: a file excluded by .gitignore reports as clean; design documents are
    expected to be tracked, so this edge case is not treated specially.
    """
    try:
        inside = subprocess.run(
            ["git", "-C", str(path.parent), "rev-parse", "--is-inside-work-tree"],
            capture_output=True,
            text=True,
        )
        if inside.returncode != 0 or inside.stdout.strip() != "true":
            return None
        status = subprocess.run(
            ["git", "-C", str(path.parent), "status", "--porcelain", "--", str(path)],
            capture_output=True,
            text=True,
        )
        if status.returncode != 0:
            return None
        return bool(status.stdout.strip())
    except (OSError, ValueError):
        return None


def check_artifact(dhf_dir: Path, basename: str, name: str) -> ArtifactCheck:
    """Check that a required design document exists, is filled out, and is approved.

    "Approved" is verified against version control: a document with uncommitted
    changes is not yet approved (see `has_uncommitted_changes`).
    """
    path = _find_doc(dhf_dir, basename)
    if path is None:
        return ArtifactCheck(
            name=name,
            path=dhf_dir / "documents" / basename,
            exists=False,
            complete=False,
            reasons=[f"{basename} not found under {dhf_dir}"],
        )

    text = path.read_text(encoding="utf-8")
    reasons: list[str] = []

    if not text.strip():
        reasons.append("document is empty")

    leftover = [m for m in PLACEHOLDER_MARKERS if m in text]
    if leftover:
        reasons.append(
            f"contains unresolved placeholders ({', '.join(sorted(set(leftover)))}); "
            "fill in and remove TODO/ENDTODO blocks"
        )

    uncommitted = has_uncommitted_changes(path)
    if uncommitted is True:
        reasons.append(
            "has uncommitted changes; the current revision is not approved in "
            "version control (commit and merge via a reviewed PR to record approval)"
        )

    return ArtifactCheck(
        name=name,
        path=path,
        exists=True,
        # `complete` reflects document content; approval (uncommitted) is tracked
        # separately so the two failure modes are reported distinctly.
        complete=not leftover and bool(text.strip()),
        reasons=reasons,
        uncommitted=uncommitted,
    )


def _sdd_coverage_warnings(dhf_dir: Path) -> list[str]:
    """Reconcile the user-need registry against the SDDs that ``satisfy`` it.

    SDDs are discovered as multiple documents (under an ``sdd`` folder or named
    ``*sdd*``); each declares the user needs it ``satisfies``. Warns when no SDD
    is found, when a registered user need is addressed by no SDD, or when an SDD
    references a user need that is not in the registry. Warnings only.
    """
    sdds = find_sdds(dhf_dir)
    if not sdds:
        return ["no SDD found (expected SDDs under an `sdd/` folder or named *sdd*)"]

    warnings: list[str] = []
    registry = registry_user_needs(dhf_dir)
    satisfied: set[str] = set()
    for sdd, refs in satisfies_by_sdd(dhf_dir).items():
        satisfied |= refs
        for ref in sorted(refs - registry):
            warnings.append(f"{sdd.name} satisfies unknown user need {ref}")
    for need in sorted(registry - satisfied):
        warnings.append(f"user need {need} is not addressed by any SDD (no `satisfies`)")
    return warnings


def _verification_messages(report) -> list[str]:
    """Failed/untested messages for an Allure verification report (shared by the
    design gate's warnings and the release gate's blocking list)."""
    messages = [
        f"user need {uid} FAILED verification "
        f"({report.by_user_need[uid].failed} failing test(s))"
        for uid in report.failed
    ]
    messages += [
        f"user need {uid} not verified by any passing Allure test"
        for uid in report.untested
    ]
    return messages


def _traceability_warnings(dhf_dir: Path) -> list[str]:
    """Reconcile SDD user-need IDs against Allure tags found in the tests.

    Reports user needs with no verifying test, and Allure tags that look like
    user-need IDs (share a declared prefix) but match no SDD user need. These
    are warnings only -- see GateResult.passed.
    """
    sdd_ids = registry_user_needs(dhf_dir)
    if not sdd_ids:
        # Nothing declared yet; the SDD source-of-truth warning already covers
        # a missing or unpopulated SDD.
        return []

    tests_dir = allure.find_tests_dir(dhf_dir)
    if tests_dir is None:
        return [
            "no tests/ directory found to reconcile Allure tags against the "
            f"{len(sdd_ids)} SDD user need(s)"
        ]

    tagged = allure.scan_source_tags(tests_dir)
    tagged_ids = set(tagged)
    warnings: list[str] = []

    for uid in sorted(sdd_ids - tagged_ids):
        warnings.append(
            f"user need {uid} (SDD) has no @allure.story/feature tag in tests"
        )

    for tag in relevant_orphans(sorted(tagged_ids - sdd_ids), sdd_ids):
        warnings.append(
            f"Allure tag {tag} matches no SDD user need ({', '.join(tagged[tag][:2])})"
        )

    return warnings


def _verification_warnings(dhf_dir: Path, allure_results_dir: Path) -> list[str]:
    """Reconcile SDD user needs against *executed* Allure results.

    Unlike the source-tag scan, this reports whether each user need was actually
    verified (a passing test), failed, or never exercised. Warnings only -- the
    design gate runs before implementation; verification status is informational
    here and would be enforced at a later release gate.
    """
    sdd_ids = registry_user_needs(dhf_dir)
    if not sdd_ids:
        return []

    report = allure.reconcile(sdd_ids, allure_results_dir)
    warnings = _verification_messages(report)
    warnings += [
        f"Allure result tag {tag} matches no SDD user need"
        for tag in relevant_orphans(report.orphan_ids, sdd_ids)
    ]
    return warnings


def run_design_gate(dhf_dir: Path, allure_results_dir: Path | None = None) -> GateResult:
    """Run the design gate and return a structured result.

    When ``allure_results_dir`` is given and exists, traceability is reconciled
    against executed Allure results (verification status); otherwise it falls
    back to scanning the test sources for ``@allure`` tags.
    """
    result = GateResult()
    result.artifacts.append(
        check_artifact(dhf_dir, DESIGN_INPUT_DOC, "Design Input")
    )
    result.artifacts.append(
        check_artifact(dhf_dir, DESIGN_REVIEW_DOC, "Design Review")
    )
    result.task_warnings = _sdd_coverage_warnings(dhf_dir)

    if allure_results_dir is not None and Path(allure_results_dir).exists():
        result.verification_warnings = _verification_warnings(dhf_dir, Path(allure_results_dir))
    else:
        result.traceability_warnings = _traceability_warnings(dhf_dir)
    return result


def story_design_gate_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
) -> int:
    """Run the `rdm story design-gate` command."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        print("Run `rdm init` first, or pass --dhf <path>.")
        return 2

    result = run_design_gate(dhf, allure_results_dir)

    print("Design-controls gate")
    print(f"DHF: {dhf}\n")

    for artifact in result.artifacts:
        if artifact.ok:
            print(f"  [OK]   {artifact.name}: {artifact.path}")
            if artifact.uncommitted is None:
                print(
                    "           - [WARN] approval could not be verified via version "
                    "control (not a git work tree); approval is the reviewed PR/commit"
                )
        else:
            print(f"  [FAIL] {artifact.name}: {artifact.path}")
            for reason in artifact.reasons:
                print(f"           - {reason}")

    if result.task_warnings:
        print("\nTraceability warnings (sources of truth):")
        for warning in result.task_warnings:
            print(f"  [WARN] {warning}")

    if result.traceability_warnings:
        print("\nTraceability warnings (SDD user needs <-> Allure tags):")
        for warning in result.traceability_warnings:
            print(f"  [WARN] {warning}")

    if result.verification_warnings:
        print("\nVerification warnings (SDD user needs <-> Allure results):")
        for warning in result.verification_warnings:
            print(f"  [WARN] {warning}")

    print()
    if result.passed:
        print(
            "Design gate PASSED: design input and design review are present, "
            "complete, and approved (committed) in version control."
        )
        return 0

    print(
        "Design gate FAILED: complete and commit (approve) the design input and "
        "design review before transitioning work into backlog tasks."
    )
    return 1


@dataclass
class ReleaseResult:
    """Result of the release gate: design controls + full verification."""

    design: GateResult
    verified: list[str] = field(default_factory=list)
    blocking: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.blocking


def run_release_gate(dhf_dir: Path, allure_results_dir: Path) -> ReleaseResult:
    """Run the release gate.

    A release requires, as hard conditions:
      1. the design gate passes (design input + review present, complete, and
         approved in version control),
      2. the SDD declares at least one user need, and
      3. every declared user need is *verified* by a passing Allure test --
         any failed or untested user need blocks the release.

    Orphan Allure tags (no matching user need) are warnings, not blockers.
    """
    design = run_design_gate(dhf_dir, allure_results_dir)
    result = ReleaseResult(design=design)

    if not design.passed:
        for artifact in design.artifacts:
            if not artifact.ok:
                result.blocking.append(
                    f"design control not met -- {artifact.name}: {'; '.join(artifact.reasons)}"
                )

    sdd_ids = registry_user_needs(dhf_dir)
    if not sdd_ids:
        result.blocking.append("SDD declares no user needs (nothing to verify)")
        return result

    report = allure.reconcile(sdd_ids, Path(allure_results_dir))
    result.verified = report.verified
    result.blocking += _verification_messages(report)
    result.warnings += [
        f"Allure result tag {tag} matches no SDD user need"
        for tag in relevant_orphans(report.orphan_ids, sdd_ids)
    ]
    return result


def story_release_gate_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
) -> int:
    """Run the `rdm story release-gate` command."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        print("Run `rdm init` first, or pass --dhf <path>.")
        return 2
    if not allure_results_dir:
        print("Error: --allure-results <dir> is required for the release gate")
        return 2
    results = Path(allure_results_dir)
    if not results.exists():
        print(f"Error: Allure results directory not found: {results}")
        return 2

    result = run_release_gate(dhf, results)

    print("Release gate")
    print(f"DHF: {dhf}\n")

    design_state = "PASS" if result.design.passed else "FAIL"
    print(f"  [{design_state}] design controls (design input + review approved)")
    if result.verified:
        print(f"  [OK]   verified user needs: {', '.join(result.verified)}")

    if result.blocking:
        print("\nBlocking:")
        for reason in result.blocking:
            print(f"  [FAIL] {reason}")
    if result.warnings:
        print("\nWarnings:")
        for warning in result.warnings:
            print(f"  [WARN] {warning}")

    print()
    if result.passed:
        print(
            "Release gate PASSED: design controls are approved and every user need "
            "is verified by a passing test."
        )
        return 0

    print(
        "Release gate FAILED: do not release. Resolve every blocking item above "
        "(approve design controls; verify all user needs)."
    )
    return 1
