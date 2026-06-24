"""
Design-controls gate: ensure design input and design review exist and are
approved before design work transitions into backlog/implementation tasks.

Traceability model enforced here:

    Design Input  ->  User Need (captured in the SDD)
                      Acceptance Criteria (captured as Allure tags on tests)

The Software Design Specification (SDD) and the Allure tags are the sources of
truth. The gate verifies that, for the design history file (DHF), both a Design
Input document and a Design Review document exist and have been
completed/approved. As a soft check it also warns when the SDD source of truth
is missing.

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

# Documents the gate requires, by id/basename. The basename matches the
# template filenames installed by `rdm init` (see rdm/init_files/documents/).
DESIGN_INPUT_DOC = "design_input.md"
DESIGN_REVIEW_DOC = "design_review.md"

# Source-of-truth document for user needs. Its absence is a soft warning.
SDD_DOC = "software_design_specification.md"

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

    @property
    def passed(self) -> bool:
        return all(a.ok for a in self.artifacts)


def _find_doc(dhf_dir: Path, basename: str) -> Path | None:
    """Locate a design document under the DHF directory.

    Checks `<dhf>/documents/<basename>` first (the layout produced by
    `rdm init`), then falls back to a recursive search so rendered/relocated
    copies are still found.
    """
    preferred = dhf_dir / "documents" / basename
    if preferred.exists():
        return preferred
    matches = sorted(dhf_dir.rglob(basename))
    return matches[0] if matches else None


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


def _source_of_truth_warnings(dhf_dir: Path) -> list[str]:
    """Return soft warnings about missing traceability sources of truth.

    The Software Design Specification (SDD) is the source of truth for user
    needs (acceptance criteria are captured as Allure tags on the verifying
    tests, which live in the test suite rather than the DHF). A missing or
    placeholder SDD is reported as a warning, not a hard failure.
    """
    sdd = check_artifact(dhf_dir, SDD_DOC, "Software Design Specification")
    if sdd.ok:
        return []
    return [f"Software Design Specification (user-need source of truth): {'; '.join(sdd.reasons)}"]


def run_design_gate(dhf_dir: Path) -> GateResult:
    """Run the design gate and return a structured result."""
    result = GateResult()
    result.artifacts.append(
        check_artifact(dhf_dir, DESIGN_INPUT_DOC, "Design Input")
    )
    result.artifacts.append(
        check_artifact(dhf_dir, DESIGN_REVIEW_DOC, "Design Review")
    )
    result.task_warnings = _source_of_truth_warnings(dhf_dir)
    return result


def story_design_gate_command(
    dhf_dir: Path | None = None,
) -> int:
    """Run the `rdm story design-gate` command."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        print("Run `rdm init` first, or pass --dhf <path>.")
        return 2

    result = run_design_gate(dhf)

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
