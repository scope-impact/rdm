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

    @property
    def ok(self) -> bool:
        return self.exists and self.complete


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


def check_artifact(dhf_dir: Path, basename: str, name: str) -> ArtifactCheck:
    """Check that a required design document exists and has been filled out."""
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

    return ArtifactCheck(
        name=name,
        path=path,
        exists=True,
        complete=not reasons,
        reasons=reasons,
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
        else:
            label = "FAIL"
            print(f"  [{label}] {artifact.name}: {artifact.path}")
            for reason in artifact.reasons:
                print(f"           - {reason}")

    if result.task_warnings:
        print("\nTraceability warnings (sources of truth):")
        for warning in result.task_warnings:
            print(f"  [WARN] {warning}")

    print()
    if result.passed:
        print("Design gate PASSED: design input and design review are present and complete.")
        return 0

    print(
        "Design gate FAILED: complete the design input and design review before "
        "transitioning work into backlog tasks."
    )
    return 1
