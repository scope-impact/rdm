"""The release gate, and the trace slice (DI-3, DI-18).

Whether a release may go ahead, laid out as a command: fetch the state once,
derive every event by the rules, return them (``release.md``, "Commands and
events"). The same rules about executed results give the design gate its
warnings, handed to it by the composition root, so the specification never
reads results.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from rdm.evidence import allure
from rdm.kernel.events import Event
from rdm.kernel.ids import relevant_orphans
from rdm.risk.register import Finding, assess
from rdm.specification.design_gate import ArtifactCheck, GateResult, design_artifacts
from rdm.specification.sdd import (
    context_of,
    design_input_ids,
    design_inputs,
    realises_by_context,
    registry_user_needs,
)
from rdm.specification.validation import unvalidated_user_needs

# Events
# ------
# A blocked release names every reason, not the first: a reviewer fixes them
# together. A warning is named too, and never blocks.

RELEASE_PERMITTED = "Release Permitted"
DESIGN_CONTROL_UNMET = "Release Blocked / Design Control Unmet"
NO_DESIGN_INPUTS = "Release Blocked / No Design Inputs"
INPUT_FAILED = "Release Blocked / Input Failed"
INPUT_UNTESTED = "Release Blocked / Input Untested"
NEED_UNADDRESSED = "Release Blocked / Need Unaddressed"
RISK_FINDING = "Release Blocked / Risk Finding"
RISK_WARNING = "Release Warned / Risk Finding"
NEED_UNVALIDATED = "Release Warned / Need Unvalidated"
ORPHAN_TAG = "Release Warned / Orphan Tag"


# State
# -----
# Everything the rules read, fetched once.

@dataclass
class State:
    dhf_name: str
    artifacts: list[ArtifactCheck]
    inputs: list[dict]
    needs: set[str] = field(default_factory=set)
    report: allure.VerificationReport | None = None
    risk_findings: list[Finding] = field(default_factory=list)
    unvalidated: list[str] = field(default_factory=list)


def fetch_state(dhf_dir: Path, results_dir: Path) -> State:
    """The design gate's pass/fail checks (not its warnings, which would
    reconcile the results a second time), the record, and the results."""
    state = State(dhf_dir.name, design_artifacts(dhf_dir), design_inputs(dhf_dir))
    ids = {di["id"] for di in state.inputs}
    if ids:
        state.needs = registry_user_needs(dhf_dir)
        state.report = allure.reconcile(ids, results_dir)
        _, state.risk_findings = assess(dhf_dir, ids, set(state.report.verified))
        state.unvalidated = unvalidated_user_needs(dhf_dir)
    return state


# Rules
# -----
# Pure: the state in, the events out.

def result_events(report: allure.VerificationReport, ids: set[str]) -> list[Event]:
    """What the executed results say: every design input verified by a
    passing test, and no tag that names no design input (a warning)."""
    events = [Event(INPUT_FAILED, f"design input {i} FAILED verification ({report.by_id[i].failed} failing test(s))")
              for i in report.failed]
    events += [Event(INPUT_UNTESTED, f"design input {i} not verified by any passing Allure test")
               for i in report.untested]
    events += [Event(ORPHAN_TAG, f"Allure result tag {tag} matches no design input")
               for tag in relevant_orphans(report.orphan_ids, ids)]
    return events


def derive(state: State) -> list[Event]:
    """Every event the rules produce, and the verdict over them:
      1. the design gate passes;
      2. at least one design input is declared;
      3. every design input is verified by a passing test;
      4. every user need is addressed by a design input;
      5. no blocking finding of the risk rules (DI-44).
    A user need with no approved validation record (DI-33), a proposed risk
    and an orphan tag only warn. Whether a passing test genuinely verifies
    its input is judged by the review of the pull request."""
    events = [Event(DESIGN_CONTROL_UNMET, f"design control not met -- {a.name}: {'; '.join(a.reasons)}")
              for a in state.artifacts if not a.ok]
    ids = {di["id"] for di in state.inputs}
    if not ids:
        return events + [Event(NO_DESIGN_INPUTS, "no design inputs declared (nothing to verify)")]
    results = result_events(state.report, ids)
    events += [e for e in results if e.blocking]
    events += [Event(RISK_FINDING if f.blocking else RISK_WARNING, f.message) for f in state.risk_findings]
    addressed = {un for di in state.inputs for un in di["traces_to"]}
    events += [Event(NEED_UNADDRESSED, f"user need {un} is addressed by no design input")
               for un in sorted(state.needs - addressed)]
    events += [Event(NEED_UNVALIDATED, f"user need {un} has no approved validation record "
                                       f"(add {state.dhf_name}/validation/{un}-validation.json)")
               for un in state.unvalidated]
    events += [e for e in results if not e.blocking]
    if not any(e.blocking for e in events):  # a conclusion over all of them
        events.insert(0, Event(RELEASE_PERMITTED, "release permitted"))
    return events


# Command
# -------

@dataclass
class ReleaseResult:
    """The release gate's events, with the design gate and the verified
    inputs they were derived from."""

    design: GateResult
    verified: list[str] = field(default_factory=list)
    events: list[Event] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return any(e.name == RELEASE_PERMITTED for e in self.events)

    @property
    def blocking(self) -> list[str]:
        return [e.message for e in self.events if e.blocking]

    @property
    def warnings(self) -> list[str]:
        return [e.message for e in self.events if not e.blocking and e.name != RELEASE_PERMITTED]


def run_release_gate(dhf_dir: Path, allure_results_dir: Path) -> ReleaseResult:
    """Run the release gate: fetch the state, derive its events."""
    state = fetch_state(Path(dhf_dir), Path(allure_results_dir))
    return ReleaseResult(design=GateResult(artifacts=state.artifacts),
                         verified=state.report.verified if state.report else [], events=derive(state))


def verification_warnings(dhf_dir: Path, allure_results_dir: Path) -> list[Event]:
    """The design gate's warnings when it is given results: the same rules the
    release gate blocks on, as warnings of the design controls."""
    ids = design_input_ids(dhf_dir)
    if not ids:
        return []
    return [Event(f"Design Controls Warned / {e.name.split(' / ')[1]}", e.message)
            for e in result_events(allure.reconcile(ids, allure_results_dir), ids)]


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
        "(approve design controls; verify all design inputs; address all user needs; resolve the risk findings)."
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
