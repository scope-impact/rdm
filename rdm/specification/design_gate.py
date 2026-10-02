"""
Design-controls gate: ensure the per-context design document(s) and the design
review exist and are approved before design work transitions into
backlog/implementation tasks.

Traceability model enforced here (ADR 0001):

    User Need (registry: V&V plan)  <- traces_to -  Design Input  <- @allure.story("DI") -  test
                                                      |  declared in, owned by
                                       design doc (per bounded context)

The gate verifies that, for the design history file (DHF), at least one
per-context design document (`kind: design`, carrying its design inputs and
outputs) and a Design Review document exist and have been completed/approved
(committed) in version control. As soft checks it reconciles the user-need
registry against the design inputs that trace to it and against the Allure
tags on the tests.

Usage:
    rdm story design-gate                       # check ./dhf
    rdm story design-gate --dhf dhf

Exit codes:
    0 - gate passed
    1 - gate failed (a required artifact is missing or incomplete)
    2 - bad invocation (path not found)

Part of the core install: it reads only the record (``rdm.specification``).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from rdm.kernel.events import Event
from rdm.kernel.git import git
from rdm.kernel.ids import relevant_orphans
from rdm.specification import tags
from rdm.specification.sdd import (
    context_of,
    design_input_ids,
    design_inputs,
    duplicate_declarations,
    find_design_docs,
    realises_by_context,
    registry_user_needs,
)
from rdm.specification.sdd import find_dhf_doc as _find_doc

# The design review is a standalone record (§820.30(e)); its basename matches the
# template installed by `rdm init`. The design inputs and outputs are no longer a
# separate document -- they live in the per-context design docs (`kind: design`).
DESIGN_REVIEW_DOC = "design_review.md"

# A document is considered "incomplete" while it still contains scaffold
# placeholders. These markers come from the init templates.
PLACEHOLDER_MARKERS = ("TODO", "ENDTODO")

# Events (specification.md, "Commands and events"): the checks fail with these,
# and warn with the Warned ones; Approved is the verdict when none fails.
APPROVED = "Design Controls Approved"
UNCOMMITTED = "Design Controls Not Approved / Uncommitted"
EMPTY = "Design Controls Not Approved / Empty Document"
PLACEHOLDERS = "Design Controls Not Approved / Placeholders"
DOCUMENT_MISSING = "Design Controls Not Approved / Document Missing"
NO_DESIGN_DOCUMENT = "Design Controls Not Approved / No Design Document"
DUPLICATE_ID = "Design Controls Not Approved / Duplicate Id"
VIEWS_STALE = "Design Controls Not Approved / Views Stale"
NEED_UNTRACED = "Design Controls Warned / Need Untraced"
UNKNOWN_NEED = "Design Controls Warned / Unknown Need"
UNKNOWN_REALISED_INPUT = "Design Controls Warned / Unknown Realised Input"
NO_DESIGN_DOCUMENT_WARNED = "Design Controls Warned / No Design Document"
NO_TESTS = "Design Controls Warned / No Tests"
INPUT_UNTAGGED = "Design Controls Warned / Input Untagged"
ORPHAN_TAG = "Design Controls Warned / Orphan Tag"


@dataclass
class ArtifactCheck:
    """Result of checking a single required design-control artifact."""

    name: str
    path: Path
    complete: bool  # present, non-empty, no placeholders
    events: list[Event] = field(default_factory=list)  # the rules it broke
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
        return not any(e.blocking for e in self.events)

    @property
    def reasons(self) -> list[str]:
        return [e.message for e in self.events]


@dataclass
class GateResult:
    """Aggregate result of the design gate."""

    artifacts: list[ArtifactCheck] = field(default_factory=list)
    task_warnings: list[Event] = field(default_factory=list)
    traceability_warnings: list[Event] = field(default_factory=list)
    verification_warnings: list[Event] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        # Traceability gaps are reported as warnings, not failures: the design
        # gate runs before implementation, so the verifying tests (and their
        # Allure tags) legitimately may not exist yet.
        return all(a.ok for a in self.artifacts)

    @property
    def events(self) -> list[Event]:
        """Every event of the gate, with its verdict first when it passes."""
        verdict = [Event(APPROVED, "the design controls are approved")] if self.passed else []
        return [*verdict, *(e for a in self.artifacts for e in a.events),
                *self.task_warnings, *self.traceability_warnings, *self.verification_warnings]


NO_DESIGN_DOC = "no design document (`kind: design`) found under the DHF"

UNAPPROVED = ("has uncommitted changes; the current revision is not approved in "
              "version control (commit and merge via a reviewed PR to record approval)")


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
    # git status fails (None) outside a work tree, so it needs no prior check.
    status = git(path.parent, "status", "--porcelain", "--", str(path))
    return None if status is None else bool(status)


def _approval(path: Path, events: list[Event]) -> bool | None:
    """The version-control state of ``path``; an unapproved one adds its event."""
    uncommitted = has_uncommitted_changes(path)
    if uncommitted is True:
        events.append(Event(UNCOMMITTED, UNAPPROVED))
    return uncommitted


def _missing(name: str, path: Path, event: Event) -> ArtifactCheck:
    return ArtifactCheck(name=name, path=path, complete=False, events=[event])


def check_doc_path(path: Path, name: str) -> ArtifactCheck:
    """Check that a design-control document at ``path`` is filled out and approved.

    "Approved" is verified against version control: a document with uncommitted
    changes is not yet approved (see `has_uncommitted_changes`).
    """
    text = path.read_text(encoding="utf-8")
    events: list[Event] = []

    if not text.strip():
        events.append(Event(EMPTY, "document is empty"))

    leftover = [m for m in PLACEHOLDER_MARKERS if m in text]
    if leftover:
        events.append(Event(PLACEHOLDERS,
            f"contains unresolved placeholders ({', '.join(sorted(set(leftover)))}); "
            "fill in and remove TODO/ENDTODO blocks"
        ))

    uncommitted = _approval(path, events)

    return ArtifactCheck(
        name=name,
        path=path,
        # `complete` reflects document content; approval (uncommitted) is tracked
        # separately so the two failure modes are reported distinctly.
        complete=not leftover and bool(text.strip()),
        events=events,
        uncommitted=uncommitted,
    )


def check_artifact(dhf_dir: Path, basename: str, name: str) -> ArtifactCheck:
    """Check a required design-control document, located by basename."""
    path = _find_doc(dhf_dir, basename)
    if path is None:
        return _missing(name, dhf_dir / "documents" / basename,
                        Event(DOCUMENT_MISSING, f"{basename} not found under {dhf_dir}"))
    return check_doc_path(path, name)


def check_design_docs(dhf_dir: Path) -> list[ArtifactCheck]:
    """Check the per-context design documents (`kind: design`).

    The design inputs + outputs live in these documents; the gate requires at
    least one, present, complete, and approved.
    """
    docs = find_design_docs(dhf_dir)
    if not docs:
        return [_missing("Software Design Description", dhf_dir / "documents" / "design",
                         Event(NO_DESIGN_DOCUMENT, NO_DESIGN_DOC))]
    return [check_doc_path(doc, f"Software Design Description ({context_of(doc)})") for doc in docs]


def _coverage_warnings(dhf_dir: Path) -> list[Event]:
    """Reconcile the user-need registry against the design docs' references.

    Warns (warnings only) when a registered user need is traced to by no design
    input, when a design input ``traces_to`` an unknown user need, or when a
    ``realises`` reference names an unknown design input.
    """
    docs = find_design_docs(dhf_dir)
    if not docs:
        return [Event(NO_DESIGN_DOCUMENT_WARNED, NO_DESIGN_DOC)]

    warnings: list[Event] = []
    registry = registry_user_needs(dhf_dir)
    inputs = design_inputs(dhf_dir)
    traced = {need for di in inputs for need in di["traces_to"]}
    for need in sorted(registry - traced):
        warnings.append(Event(NEED_UNTRACED, f"user need {need} is traced to by no design input"))

    di_ids = {di["id"] for di in inputs}
    for di in inputs:
        for ref in sorted(set(di["traces_to"]) - registry):
            warnings.append(Event(UNKNOWN_NEED, f"design input {di['id']} traces_to unknown user need {ref}"))
    for doc, refs in realises_by_context(dhf_dir).items():
        for ref in sorted(refs - di_ids):
            warnings.append(Event(UNKNOWN_REALISED_INPUT, f"{doc.name} realises unknown design input {ref}"))
    return warnings


def _traceability_warnings(dhf_dir: Path) -> list[Event]:
    """Reconcile design-input IDs against @allure tags found in the tests.

    Reports design inputs with no verifying test, and Allure tags that share a
    declared prefix but match no design input. Warnings only -- the verifying
    tests legitimately may not exist yet at the design gate.
    """
    di_ids = design_input_ids(dhf_dir)
    if not di_ids:
        # No design inputs declared yet; the SDD coverage warning covers gaps.
        return []

    tests_dir = tags.find_tests_dir(dhf_dir)
    if tests_dir is None:
        return [Event(NO_TESTS, "no tests/ directory found to reconcile Allure tags against the "
                                f"{len(di_ids)} design input(s)")]

    tagged = tags.scan_source_tags(tests_dir)
    tagged_ids = set(tagged)
    warnings = [Event(INPUT_UNTAGGED, f"design input {di} has no @allure.story tag in tests")
                for di in sorted(di_ids - tagged_ids)]
    warnings += [Event(ORPHAN_TAG, f"Allure tag {tag} matches no design input ({', '.join(tagged[tag][:2])})")
                 for tag in relevant_orphans(tagged_ids, di_ids)]
    return warnings


def check_unique_ids(dhf_dir: Path) -> ArtifactCheck:
    """Every user-need and design-input id declared once (DI-46). The record
    reader keeps an id's first declaration, so a second would silently drop
    out of every gate and the graph."""
    events = [Event(DUPLICATE_ID, f"{ident} is declared {len(docs)} times: {', '.join(docs)}")
              for ident, docs in sorted(duplicate_declarations(dhf_dir).items())]
    return ArtifactCheck(name="Requirement ids", path=Path(dhf_dir),
                         complete=not events, events=events, uncommitted=False)


def check_architecture_views(dhf_dir: Path) -> list[ArtifactCheck]:
    """The architecture workspace and what is drawn from it, current (DI-70) and
    approved (committed, the drawn files with it); none when the DHF has no
    workspace."""
    from rdm.architecture.model import WORKSPACE, stale

    workspace = Path(dhf_dir) / WORKSPACE
    if not workspace.is_file():
        return []
    events = [Event(VIEWS_STALE, reason) for reason in stale(dhf_dir)]
    complete = not events
    uncommitted = _approval(workspace.parent, events)
    return [ArtifactCheck(name="Architecture views", path=workspace, complete=complete,
                          events=events, uncommitted=uncommitted)]


def design_artifacts(dhf_dir: Path) -> list[ArtifactCheck]:
    """The design gate's pass/fail checks: design documents, the design review,
    ids declared once, the architecture's drawn views."""
    return [*check_design_docs(dhf_dir), check_artifact(dhf_dir, DESIGN_REVIEW_DOC, "Design Review"),
            check_unique_ids(dhf_dir), *check_architecture_views(dhf_dir)]


def run_design_gate(dhf_dir: Path, verification_warnings=None) -> GateResult:
    """Run the design gate and return a structured result.

    ``verification_warnings``, when given, computes the warnings about executed
    results for the DHF (release's, ``rdm.release.gate.verification_warnings``,
    handed in by the composition root): they replace the warnings about test
    tags. The specification itself never reads results.
    """
    result = GateResult()
    result.artifacts.extend(design_artifacts(dhf_dir))
    result.task_warnings = _coverage_warnings(dhf_dir)
    if verification_warnings is not None:
        result.verification_warnings = verification_warnings(dhf_dir)
    else:
        result.traceability_warnings = _traceability_warnings(dhf_dir)
    return result


def story_design_gate_command(dhf_dir: Path | None = None, verification_warnings=None) -> int:
    """Run the `rdm story design-gate` command."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        print("Run `rdm init` first, or pass --dhf <path>.")
        return 2

    result = run_design_gate(dhf, verification_warnings)

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

    for heading, warnings in (
        ("Traceability warnings (sources of truth)", result.task_warnings),
        ("Traceability warnings (SDD user needs <-> Allure tags)", result.traceability_warnings),
        ("Verification warnings (SDD user needs <-> Allure results)", result.verification_warnings),
    ):
        if warnings:
            print(f"\n{heading}:")
            for warning in warnings:
                print(f"  [WARN] {warning.message}")

    print()
    if result.passed:
        print(
            "Design gate PASSED: the per-context design document(s) and the design "
            "review are present, complete, and approved (committed) in version control."
        )
        return 0

    print(
        "Design gate FAILED: complete and commit (approve) the design document(s) "
        "and design review before transitioning work into backlog tasks."
    )
    return 1
