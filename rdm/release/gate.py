"""The release gate, and the trace slice (DI-3, DI-18).

Whether a release may go ahead: the design gate's checks, every design input
verified by a passing test, every user need refined by a design input, and no
blocking finding of the risk rules; a user need with no approved validation
record is a warning. The same reconciliation of executed results gives the
design gate its warnings, handed to it by the composition root, so the
specification never reads results.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from rdm.evidence import allure
from rdm.kernel.ids import relevant_orphans
from rdm.specification.design_gate import GateResult, design_artifacts
from rdm.specification.sdd import (
    context_of,
    design_input_ids,
    design_inputs,
    realises_by_context,
    registry_user_needs,
)


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


def verification_warnings(dhf_dir: Path, allure_results_dir: Path) -> list[str]:
    """Reconcile design inputs against *executed* Allure results.

    Reports whether each design input was actually verified (a passing test),
    failed, or never exercised: the design gate's warnings when it is given
    results (the release gate blocks on the same).
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


def run_release_gate(
    dhf_dir: Path,
    allure_results_dir: Path,
) -> ReleaseResult:
    """Run the release gate.

    A release requires, as hard conditions:
      1. the design gate passes (the per-context design document(s) + review
         present, complete, and approved in version control),
      2. at least one design input is declared,
      3. every declared design input is *verified* by a passing Allure test --
         any failed or untested design input blocks the release, and
      4. every user need is addressed by at least one design input, and
      5. every risk in the register is scored, controlled, and residually
         acceptable or accepted (DI-44).

    Whether a passing test genuinely verifies its input is judged by the
    human review of the pull request that changed it.

    Orphan Allure tags (no matching design input) are warnings, not blockers.
    """
    # Only the design gate's pass/fail checks: its warnings would reconcile the
    # results a second time, and the release gate reports its own.
    design = GateResult(artifacts=design_artifacts(dhf_dir))
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


    # The risk register's mechanical rules (DI-44).
    from rdm.risk.register import findings as risk_findings

    risk_blocking, risk_warnings = risk_findings(dhf_dir, di_ids, set(report.verified))
    result.blocking += risk_blocking
    result.warnings += risk_warnings

    # A user need with no design input is an unaddressed (hence unverified) need.
    addressed = {un for di in inputs for un in di["traces_to"]}
    for un in sorted(registry_user_needs(dhf_dir) - addressed):
        result.blocking.append(f"user need {un} is addressed by no design input")

    # Summative validation is human-evidenced (DI-33): a missing approved
    # record is named, loudly, but a machine cannot supply the judgment --
    # warning, not blocking.
    from rdm.specification.validation import unvalidated_user_needs

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
    print(f"  [{design_state}] design controls (design document(s) + review approved)")
    if result.verified:
        print(f"  [OK]   verified design inputs: {', '.join(result.verified)}")

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
            "is verified by a passing test, every user need is addressed, and every risk is evaluated, "
            "with its risk controls verified and its residual acceptable."
        )
        return 0

    print(
        "Release gate FAILED: do not release. Resolve every blocking item above "
        "(approve design controls; verify all design inputs; address all user needs)."
    )
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
) -> dict:
    """Return the traceability slice for one user need or design input.

    Pure read over the record (and, when given, executed Allure results).
    ``target`` is a user-need id (→ its design inputs) or a design-input id
    (→ its need(s), owner/realisers, tests, status).
    Returns ``{"error": …}`` if the target is not declared.
    """
    inputs = design_inputs(dhf_dir)
    by_id = {di["id"]: di for di in inputs}
    needs = registry_user_needs(dhf_dir)
    realised = _realised_by(dhf_dir)

    verif = allure.reconcile(set(by_id), Path(allure_results_dir)) if allure_results_dir else None

    def _di_slice(di: dict) -> dict:
        v = verif.by_id.get(di["id"]) if verif else None
        return {
            "design_input": di["id"],
            "text": di["text"],
            "traces_to": di["traces_to"],
            "owned_by": di["context"],
            "realised_by": sorted(realised.get(di["id"], [])),
            "status": v.status if v else None,
            "tests": sorted(v.tests) if v else [],
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
) -> int:
    """Run `rdm story trace <UN-/DI-id>`: print the traceability slice."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2

    trace = build_trace(dhf, target, allure_results_dir)
    if "error" in trace:
        print(f"Error: {trace['error']}")
        return 2

    if trace["kind"] == "user_need":
        print(f"User need {trace['id']} — design inputs that refine it:")
        if not trace["design_inputs"]:
            print("  (none — this user need is addressed by no design input)")
        for di in trace["design_inputs"]:
            extra = f"[{di['status']}]" if di["status"] else ""
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
    return 0
