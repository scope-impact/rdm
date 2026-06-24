"""`rdm story persona` -- report formative usability evidence from persona runs."""

from __future__ import annotations

from pathlib import Path

from rdm.record import persona
from rdm.record.sdd import user_needs_from_doc

_DISCLAIMER = (
    "NOTE: AI-persona runs are FORMATIVE evidence only -- they surface usability "
    "problems and act as simulated-use regression. They are NOT summative IEC "
    "62366-1 validation, which requires real representative users. The human "
    "summative study remains the validation record."
)


def persona_command(
    vv_plan: Path | None = None,
    persona_results: Path | None = None,
) -> int:
    """Reconcile persona runs against the V&V-plan user needs and report.

    Informational by design (always returns 0 on a successful run): formative
    findings inform the use-related risk analysis; they do not gate release.
    """
    plan = Path(vv_plan or "dhf/documents/verification_and_validation_plan.md")
    if not plan.exists():
        print(f"Error: V&V plan not found: {plan}")
        print("Pass --vv-plan <path> (it carries the user_needs registry).")
        return 2
    if not persona_results:
        print("Error: --persona-results <dir> is required")
        return 2
    results = Path(persona_results)
    if not results.exists():
        print(f"Error: persona results directory not found: {results}")
        return 2

    user_need_ids = user_needs_from_doc(plan)
    report = persona.reconcile(user_need_ids, results)

    print("AI-persona formative usability evidence")
    print(f"V&V plan: {plan}")
    print(f"Runs: {report.runs_found}\n")

    if report.clean:
        print(f"  [clean]   no issues observed: {', '.join(report.clean)}")
    for uid in report.with_issues:
        need = report.by_user_need[uid]
        print(f"  [issues]  {uid}: {len(need.issues)} usability issue(s) observed")
    for uid in report.failed:
        print(f"  [FAILED]  {uid}: a persona could not complete the journey")
    for uid in report.not_run:
        print(f"  [not-run] {uid}: no persona simulated-use run")
    for tag in report.orphan_ids:
        print(f"  [orphan]  persona run tagged {tag} matches no user need")

    print(f"\n{_DISCLAIMER}")
    return 0
