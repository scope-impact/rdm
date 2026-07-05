"""
Design-controls gate: ensure the per-context design document(s) and the design
review exist and are approved before design work transitions into
backlog/implementation tasks.

Traceability model enforced here (ADR 0001):

    User Need (registry: V&V plan)  <- satisfies -  design doc (per bounded context)
                                                      |  declares design inputs
                                       Design Input  <- @allure.story("DI") -  test

The gate verifies that, for the design history file (DHF), at least one
per-context design document (`kind: design`, carrying its design inputs and
outputs) and a Design Review document exist and have been completed/approved
(committed) in version control. As soft checks it reconciles the user-need
registry against the design documents that `satisfy` it and against the Allure
tags on the tests.

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

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record import allure, faithfulness
from rdm.record.reconcile import relevant_orphans
from rdm.record.sdd import (
    context_of,
    design_input_ids,
    design_inputs,
    find_design_docs,
    realises_by_context,
    registry_user_needs,
    satisfies_by_context,
)
from rdm.record.sdd import find_dhf_doc as _find_doc

# The design review is a standalone record (§820.30(e)); its basename matches the
# template installed by `rdm init`. The design inputs and outputs are no longer a
# separate document -- they live in the per-context design docs (`kind: design`).
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


def check_doc_path(path: Path, name: str) -> ArtifactCheck:
    """Check that a design-control document at ``path`` is filled out and approved.

    "Approved" is verified against version control: a document with uncommitted
    changes is not yet approved (see `has_uncommitted_changes`).
    """
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


def check_artifact(dhf_dir: Path, basename: str, name: str) -> ArtifactCheck:
    """Check a required design-control document, located by basename."""
    path = _find_doc(dhf_dir, basename)
    if path is None:
        return ArtifactCheck(
            name=name,
            path=dhf_dir / "documents" / basename,
            exists=False,
            complete=False,
            reasons=[f"{basename} not found under {dhf_dir}"],
        )
    return check_doc_path(path, name)


def check_design_docs(dhf_dir: Path) -> list[ArtifactCheck]:
    """Check the per-context design documents (`kind: design`).

    The design inputs + outputs live in these documents; the gate requires at
    least one, present, complete, and approved.
    """
    docs = find_design_docs(dhf_dir)
    if not docs:
        return [
            ArtifactCheck(
                name="Software Design Description",
                path=dhf_dir / "documents" / "design",
                exists=False,
                complete=False,
                reasons=["no design document (`kind: design`) found under the DHF"],
            )
        ]
    return [check_doc_path(doc, f"Software Design Description ({context_of(doc)})") for doc in docs]


def _coverage_warnings(dhf_dir: Path) -> list[str]:
    """Reconcile the user-need registry against the design docs' references.

    Warns (warnings only) when a registered user need is addressed by no design
    document, when a document ``satisfies`` or a design input ``traces_to`` an
    unknown user need, or when a ``realises`` reference names an unknown design
    input.
    """
    docs = find_design_docs(dhf_dir)
    if not docs:
        return ["no design document (`kind: design`) found under the DHF"]

    warnings: list[str] = []
    registry = registry_user_needs(dhf_dir)
    satisfied: set[str] = set()
    for doc, refs in satisfies_by_context(dhf_dir).items():
        satisfied |= refs
        for ref in sorted(refs - registry):
            warnings.append(f"{doc.name} satisfies unknown user need {ref}")
    for need in sorted(registry - satisfied):
        warnings.append(f"user need {need} is addressed by no design document (no `satisfies`)")

    di_ids = design_input_ids(dhf_dir)
    for di in design_inputs(dhf_dir):
        for ref in sorted(set(di["traces_to"]) - registry):
            warnings.append(f"design input {di['id']} traces_to unknown user need {ref}")
    for doc, refs in realises_by_context(dhf_dir).items():
        for ref in sorted(refs - di_ids):
            warnings.append(f"{doc.name} realises unknown design input {ref}")
    return warnings


def _verification_messages(report) -> list[str]:
    """Failed/untested messages for an Allure verification report (shared by the
    design gate's warnings and the release gate's blocking list)."""
    messages = [
        f"design input {uid} FAILED verification "
        f"({report.by_id[uid].failed} failing test(s))"
        for uid in report.failed
    ]
    messages += [
        f"design input {uid} not verified by any passing Allure test"
        for uid in report.untested
    ]
    return messages


def _faithfulness_messages(report: faithfulness.FaithfulnessReport) -> list[str]:
    """Blocking messages for a faithfulness report: each design input whose
    verifying test is not independently confirmed to verify it."""
    messages = [
        f"design input {uid} has no faithfulness review (its verifying test is "
        "unconfirmed -- a passing test is not proof it verifies the input)"
        for uid in report.unreviewed
    ]
    messages += [
        f"design input {uid} FAILED faithfulness review "
        f"({report.by_id[uid].rationale or 'test does not verify the input'})"
        for uid in report.unfaithful
    ]
    messages += [
        f"design input {uid} faithfulness review is STALE: its verifying test "
        "changed since the review (re-review the test against the input)"
        for uid in report.stale
    ]
    messages += [
        f"design input {uid} is only PARTIALLY verified -- uncovered clause(s): "
        f"{'; '.join(report.by_id[uid].uncovered_clauses) or 'see review'}"
        for uid in report.partial
    ]
    return messages


# Console label per faithfulness status (default ``[????]`` covers UNREVIEWED).
_FAITHFULNESS_MARKS = {
    faithfulness.FAITHFUL: "[OK]   ",
    faithfulness.UNFAITHFUL: "[FAIL] ",
    faithfulness.PARTIAL: "[PART] ",
    faithfulness.STALE: "[STALE]",
}


def faithfulness_dir_for(dhf_dir: Path, faithfulness_dir: Path | None) -> Path:
    """Resolve the faithfulness-verdicts directory (default ``<dhf>/faithfulness``)."""
    return Path(faithfulness_dir) if faithfulness_dir else dhf_dir / "faithfulness"


def run_faithfulness_gate(
    dhf_dir: Path, faithfulness_dir: Path | None = None
) -> faithfulness.FaithfulnessReport:
    """Reconcile the declared design inputs against faithfulness verdicts."""
    inputs = design_inputs(dhf_dir)
    return faithfulness.reconcile(
        inputs,
        faithfulness_dir_for(dhf_dir, faithfulness_dir),
        allure.find_tests_dir(dhf_dir),
    )


def _traceability_warnings(dhf_dir: Path) -> list[str]:
    """Reconcile design-input IDs against @allure tags found in the tests.

    Reports design inputs with no verifying test, and Allure tags that share a
    declared prefix but match no design input. Warnings only -- the verifying
    tests legitimately may not exist yet at the design gate.
    """
    di_ids = design_input_ids(dhf_dir)
    if not di_ids:
        # No design inputs declared yet; the SDD coverage warning covers gaps.
        return []

    tests_dir = allure.find_tests_dir(dhf_dir)
    if tests_dir is None:
        return [
            "no tests/ directory found to reconcile Allure tags against the "
            f"{len(di_ids)} design input(s)"
        ]

    tagged = allure.scan_source_tags(tests_dir)
    tagged_ids = set(tagged)
    warnings: list[str] = []

    for di in sorted(di_ids - tagged_ids):
        warnings.append(
            f"design input {di} has no @allure.story/feature tag in tests"
        )

    for tag in relevant_orphans(sorted(tagged_ids - di_ids), di_ids):
        warnings.append(
            f"Allure tag {tag} matches no design input ({', '.join(tagged[tag][:2])})"
        )

    return warnings


def _verification_warnings(dhf_dir: Path, allure_results_dir: Path) -> list[str]:
    """Reconcile design inputs against *executed* Allure results.

    Reports whether each design input was actually verified (a passing test),
    failed, or never exercised. Warnings only at the design gate; the release
    gate enforces this.
    """
    di_ids = design_input_ids(dhf_dir)
    if not di_ids:
        return []

    report = allure.reconcile(di_ids, allure_results_dir)
    warnings = _verification_messages(report)
    warnings += [
        f"Allure result tag {tag} matches no design input"
        for tag in relevant_orphans(report.orphan_ids, di_ids)
    ]
    return warnings


def run_design_gate(dhf_dir: Path, allure_results_dir: Path | None = None) -> GateResult:
    """Run the design gate and return a structured result.

    When ``allure_results_dir`` is given and exists, traceability is reconciled
    against executed Allure results (verification status); otherwise it falls
    back to scanning the test sources for ``@allure`` tags.
    """
    result = GateResult()
    result.artifacts.extend(check_design_docs(dhf_dir))
    result.artifacts.append(
        check_artifact(dhf_dir, DESIGN_REVIEW_DOC, "Design Review")
    )
    result.task_warnings = _coverage_warnings(dhf_dir)

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
            "Design gate PASSED: the per-context design document(s) and the design "
            "review are present, complete, and approved (committed) in version control."
        )
        return 0

    print(
        "Design gate FAILED: complete and commit (approve) the design document(s) "
        "and design review before transitioning work into backlog tasks."
    )
    return 1


@dataclass
class ReleaseResult:
    """Result of the release gate: design controls + full verification."""

    design: GateResult
    verified: list[str] = field(default_factory=list)
    faithful: list[str] = field(default_factory=list)
    blocking: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.blocking


def run_release_gate(
    dhf_dir: Path,
    allure_results_dir: Path,
    faithfulness_dir: Path | None = None,
) -> ReleaseResult:
    """Run the release gate.

    A release requires, as hard conditions:
      1. the design gate passes (the per-context design document(s) + review
         present, complete, and approved in version control),
      2. at least one design input is declared,
      3. every declared design input is *verified* by a passing Allure test --
         any failed or untested design input blocks the release,
      4. every declared design input has a current, *faithful* review -- its
         verifying test is independently confirmed to verify it (a passing test
         is not proof on its own), and
      5. every user need is addressed by at least one design input.

    Orphan Allure tags (no matching design input) are warnings, not blockers.
    """
    design = run_design_gate(dhf_dir, allure_results_dir)
    result = ReleaseResult(design=design)

    if not design.passed:
        for artifact in design.artifacts:
            if not artifact.ok:
                result.blocking.append(
                    f"design control not met -- {artifact.name}: {'; '.join(artifact.reasons)}"
                )

    inputs = design_inputs(dhf_dir)
    di_ids = {di["id"] for di in inputs}
    if not di_ids:
        result.blocking.append("no design inputs declared (nothing to verify)")
        return result

    report = allure.reconcile(di_ids, Path(allure_results_dir))
    result.verified = report.verified
    result.blocking += _verification_messages(report)

    # Verified (the test passed) is necessary but not sufficient: the test must
    # also be independently confirmed to actually verify the input. Reuse the
    # `inputs` already computed above rather than re-walking the DHF.
    faith = faithfulness.reconcile(
        inputs,
        faithfulness_dir_for(dhf_dir, faithfulness_dir),
        allure.find_tests_dir(dhf_dir),
    )
    result.faithful = faith.faithful
    result.blocking += _faithfulness_messages(faith)
    result.warnings += [
        f"faithfulness verdict for {tag} matches no declared design input"
        for tag in relevant_orphans(faith.orphan_ids, di_ids)
    ]

    # A user need with no design input is an unaddressed (hence unverified) need.
    addressed = {un for di in inputs for un in di["traces_to"]}
    for un in sorted(registry_user_needs(dhf_dir) - addressed):
        result.blocking.append(f"user need {un} is addressed by no design input")

    # Summative validation is human-evidenced (DI-33): a missing approved
    # record is named, loudly, but a machine cannot supply the judgment --
    # warning, not blocking.
    from rdm.record.validation import unvalidated_user_needs

    result.warnings += [
        f"user need {un} has no approved validation record "
        f"(add {dhf_dir.name}/validation/{un}-validation.json)"
        for un in unvalidated_user_needs(dhf_dir)
    ]

    result.warnings += [
        f"Allure result tag {tag} matches no design input"
        for tag in relevant_orphans(report.orphan_ids, di_ids)
    ]
    return result


def story_release_gate_command(
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    faithfulness_dir: Path | None = None,
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

    result = run_release_gate(dhf, results, faithfulness_dir)

    print("Release gate")
    print(f"DHF: {dhf}\n")

    design_state = "PASS" if result.design.passed else "FAIL"
    print(f"  [{design_state}] design controls (design document(s) + review approved)")
    if result.verified:
        print(f"  [OK]   verified design inputs: {', '.join(result.verified)}")
    if result.faithful:
        print(f"  [OK]   faithfully reviewed design inputs: {', '.join(result.faithful)}")

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
            "Release gate PASSED: design controls are approved, every design input "
            "is verified by a passing test AND independently confirmed to verify it, "
            "and every user need is addressed."
        )
        return 0

    print(
        "Release gate FAILED: do not release. Resolve every blocking item above "
        "(approve design controls; verify all design inputs; confirm test faithfulness; "
        "address all user needs)."
    )
    return 1


def replay_probes(report) -> tuple[int, int, list[str]]:
    """Re-execute every recorded KILLED mutation probe (DI-27).

    Returns ``(replayed, still_killed, failures)`` where ``failures`` describes
    probes that now survive or error — evidence the review no longer holds.
    """
    from rdm.story_audit.mutation import _pytest_runner, run_mutation_probe

    replayed = killed = 0
    failures: list[str] = []
    for di_id in sorted(report.by_id):
        for probe in report.by_id[di_id].probes:
            if str(probe.get("result", "")).strip().upper() != "KILLED":
                continue  # survived/equivalent probes are documentation, not claims
            replayed += 1
            file_path = Path(str(probe.get("file", "")))
            outcome = run_mutation_probe(
                file_path, str(probe.get("find", "")), str(probe.get("replace", "")),
                _pytest_runner(str(probe.get("test", ""))),
            )
            if outcome.get("killed"):
                killed += 1
                print(f"  [KILLED]   {di_id}: {file_path} :: {probe.get('test')}")
            elif "error" in outcome:
                failures.append(f"{di_id}: probe error -- {outcome['error']}")
                print(f"  [ERROR]    {di_id}: {outcome['error']}")
            else:
                failures.append(f"{di_id}: recorded killing probe now SURVIVES "
                                f"({file_path} :: {probe.get('test')})")
                print(f"  [SURVIVED] {di_id}: {file_path} :: {probe.get('test')}")
    return replayed, killed, failures


def story_faithfulness_command(
    dhf_dir: Path | None = None,
    faithfulness_dir: Path | None = None,
    stale_only: bool = False,
    replay: bool = False,
) -> int:
    """Run the `rdm story faithfulness` command: report each design input's
    independent faithfulness review (verifying test confirmed to verify it).

    ``stale_only`` filters the report to non-faithful inputs (the reviewer's
    worklist); ``replay`` re-executes every recorded killing mutation probe and
    fails if any no longer kills.
    """
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        print("Run `rdm init` first, or pass --dhf <path>.")
        return 2

    report = run_faithfulness_gate(dhf, faithfulness_dir)
    vdir = faithfulness_dir_for(dhf, faithfulness_dir)

    print("Faithfulness review (does each verifying test actually verify its input?)")
    print(f"DHF: {dhf}")
    print(f"Verdicts: {vdir} ({report.verdicts_found} found)\n")

    by_id = report.by_id
    shown = 0
    for di_id in sorted(by_id):
        agg = by_id[di_id]
        if stale_only and agg.status == faithfulness.FAITHFUL:
            continue
        shown += 1
        print(f"  {_FAITHFULNESS_MARKS.get(agg.status, '[????]')} {di_id}: {agg.status}"
              + (f" -- {agg.reviewer}" if agg.reviewer else ""))
        if agg.status != faithfulness.FAITHFUL and agg.rationale:
            print(f"            {agg.rationale}")
    if stale_only and shown == 0:
        print("  (none -- every design input is faithful)")

    replay_failures: list[str] = []
    if replay:
        print("\nReplaying recorded killing probes:")
        replayed, killed, replay_failures = replay_probes(report)
        print(f"\n  {killed}/{replayed} recorded killing probe(s) still kill.")
        if replayed == 0:
            print("  (no structured probes on record -- record them with `rdm story verdict --probe`)")

    blocking = _faithfulness_messages(report) + replay_failures
    print()
    if not blocking:
        print(
            "Faithfulness PASSED: every design input has a current, independent "
            "review confirming its verifying test actually verifies it."
        )
        return 0
    print(f"Faithfulness FAILED: {len(blocking)} item(s) unconfirmed. "
          "Run the `test-faithfulness` skill (or a human reviewer) to record verdicts.")
    return 1


def _realised_by(dhf_dir: Path) -> dict[str, list[str]]:
    """Map each design-input id to the context name(s) that ``realises`` it."""
    out: dict[str, list[str]] = {}
    for doc, refs in realises_by_context(dhf_dir).items():
        for ref in refs:
            out.setdefault(ref, []).append(context_of(doc))
    return out


def build_trace(
    dhf_dir: Path,
    target: str,
    allure_results_dir: Path | None = None,
    faithfulness_dir: Path | None = None,
) -> dict:
    """Return the traceability slice for one user need or design input.

    Pure read over the record (and, when given, executed Allure results +
    faithfulness verdicts). ``target`` is a user-need id (→ its design inputs) or
    a design-input id (→ its need(s), owner/realisers, tests, status, verdict).
    Returns ``{"error": …}`` if the target is not declared.
    """
    inputs = design_inputs(dhf_dir)
    by_id = {di["id"]: di for di in inputs}
    needs = registry_user_needs(dhf_dir)
    realised = _realised_by(dhf_dir)

    verif = allure.reconcile(set(by_id), Path(allure_results_dir)) if allure_results_dir else None
    fdir = faithfulness_dir_for(dhf_dir, faithfulness_dir)
    faith = (
        faithfulness.reconcile(inputs, fdir, allure.find_tests_dir(dhf_dir)) if fdir.exists() else None
    )

    def _di_slice(di: dict) -> dict:
        v = verif.by_id.get(di["id"]) if verif else None
        f = faith.by_id.get(di["id"]) if faith else None
        return {
            "design_input": di["id"],
            "text": di["text"],
            "traces_to": di["traces_to"],
            "owned_by": di["context"],
            "realised_by": sorted(realised.get(di["id"], [])),
            "status": v.status if v else None,
            "tests": sorted(v.tests) if v else [],
            "faithfulness": f.status if f else None,
        }

    if target in needs:
        members = [_di_slice(di) for di in inputs if target in di["traces_to"]]
        return {"kind": "user_need", "id": target, "design_inputs": members}
    if target in by_id:
        return {"kind": "design_input", **_di_slice(by_id[target])}
    return {"error": f"{target} is not a declared user need or design input"}


def story_trace_command(
    target: str,
    dhf_dir: Path | None = None,
    allure_results_dir: Path | None = None,
    faithfulness_dir: Path | None = None,
) -> int:
    """Run `rdm story trace <UN-/DI-id>`: print the traceability slice."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2

    trace = build_trace(dhf, target, allure_results_dir, faithfulness_dir)
    if "error" in trace:
        print(f"Error: {trace['error']}")
        return 2

    if trace["kind"] == "user_need":
        print(f"User need {trace['id']} — design inputs that refine it:")
        if not trace["design_inputs"]:
            print("  (none — this user need is addressed by no design input)")
        for di in trace["design_inputs"]:
            extra = " ".join(
                p for p in (
                    f"[{di['status']}]" if di["status"] else "",
                    f"faithful={di['faithfulness']}" if di["faithfulness"] else "",
                ) if p
            )
            print(f"  {di['design_input']} (owned by {di['owned_by']}) {extra}".rstrip())
            print(f"      {di['text']}")
            if di["tests"]:
                print(f"      verified by: {', '.join(di['tests'])}")
    else:
        print(f"Design input {trace['design_input']}")
        print(f"  text:        {trace['text']}")
        print(f"  traces_to:   {', '.join(trace['traces_to']) or '— (cross-cutting constraint)'}")
        print(f"  owned by:    {trace['owned_by']}")
        if trace["realised_by"]:
            print(f"  realised by: {', '.join(trace['realised_by'])}")
        if trace["status"]:
            print(f"  status:      {trace['status']}")
        if trace["tests"]:
            print(f"  verified by: {', '.join(trace['tests'])}")
        if trace["faithfulness"]:
            print(f"  faithfulness:{trace['faithfulness']}")
    return 0


# Verdict values a reviewer may record (anything other than `faithful` blocks).
_VERDICT_VALUES = (faithfulness.FAITHFUL, faithfulness.PARTIAL, faithfulness.UNFAITHFUL, "weak")


def record_verdict(
    dhf_dir: Path,
    design_input_id: str,
    verdict: str,
    *,
    reviewer: str,
    rationale: str,
    reviewed_tests: list[str] | None = None,
    uncovered_clauses: list[str] | None = None,
    faithfulness_dir: Path | None = None,
    hash_scope: str = faithfulness.DEFAULT_SCOPE,
    probes: list[dict] | None = None,
) -> Path | None:
    """Write a faithfulness verdict for one design input, hash-pinned to the
    CURRENT verifying-test source (so it is valid for exactly the test reviewed,
    and goes stale on any later edit). ``hash_scope`` selects what the pin
    covers (module scope by default -- helper edits re-open the review);
    ``probes`` records the reviewer's executed mutations so the review can be
    replayed. Returns the path, or ``None`` if the id is not a declared design
    input.
    """
    inputs = design_inputs(dhf_dir)
    if design_input_id not in {di["id"] for di in inputs}:
        return None
    test_hash = faithfulness.current_hashes(
        inputs, allure.find_tests_dir(dhf_dir), scope=hash_scope
    ).get(design_input_id, "")
    out_dir = faithfulness_dir_for(dhf_dir, faithfulness_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    record = {
        "design_input": design_input_id,
        "verdict": verdict,
        "reviewer": reviewer,
        "rationale": rationale,
        "test_hash": test_hash,
        "hash_scope": hash_scope,
        "reviewed_tests": reviewed_tests or [],
        "probes": probes or [],
        "uncovered_clauses": uncovered_clauses or [],
    }
    out = out_dir / f"{design_input_id}-faithfulness.json"
    out.write_text(json.dumps(record, indent=2), encoding="utf-8")
    return out


def story_verdict_command(
    target: str,
    verdict: str,
    reviewer: str,
    rationale: str,
    reviewed_tests: str | None = None,
    uncovered: str | None = None,
    dhf_dir: Path | None = None,
    faithfulness_dir: Path | None = None,
    hash_scope: str = faithfulness.DEFAULT_SCOPE,
    probe: list[str] | None = None,
) -> int:
    """Run `rdm story verdict <DI-id> …`: record an independent faithfulness verdict.

    Replaces the standalone write_verdict.py script so the `test-faithfulness`
    skill depends on the installed `rdm` binary, not a bundled file.
    """
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    if verdict not in _VERDICT_VALUES:
        print(f"Error: --verdict must be one of: {', '.join(_VERDICT_VALUES)}")
        return 2
    tests = [t.strip() for t in (reviewed_tests or "").split(",") if t.strip()]
    clauses = [c.strip() for c in (uncovered or "").split(";") if c.strip()]
    probes: list[dict] = []
    for raw in probe or []:
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError as error:
            print(f"Error: --probe is not valid JSON ({error}): {raw}")
            return 2
        missing = {"file", "find", "replace", "test"} - set(parsed)
        if not isinstance(parsed, dict) or missing:
            print(f"Error: --probe needs file/find/replace/test keys (missing: {', '.join(sorted(missing))})")
            return 2
        parsed.setdefault("result", "KILLED")
        probes.append(parsed)
    out = record_verdict(
        dhf, target, verdict,
        reviewer=reviewer, rationale=rationale,
        reviewed_tests=tests, uncovered_clauses=clauses,
        faithfulness_dir=faithfulness_dir,
        hash_scope=hash_scope, probes=probes,
    )
    if out is None:
        declared = ", ".join(sorted(design_input_ids(dhf)))
        print(f"Error: {target} is not a declared design input ({declared})")
        return 2
    print(f"wrote {out} ({verdict}" + (f", {len(clauses)} uncovered clause(s)" if clauses else "") + ")")
    return 0
