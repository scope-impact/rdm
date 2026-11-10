"""The release gate (DI-3): over the requirements of the factory's spec (ADR 0002).

Whether a release may go ahead, laid out as a command: fetch the state once,
derive every event by the rules, return them (``release.md``, "Commands and
events").
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from rdm.evidence import allure
from rdm.kernel.events import Event
from rdm.kernel.ids import relevant_orphans
from rdm.risk.register import Finding, assess
from rdm.specification.sdd import load_spec, requirements
from rdm.specification.validation import unvalidated_user_needs

# Events
# ------
# A blocked release names every reason, not the first: a reviewer fixes them
# together. A warning is named too, and never blocks.

RELEASE_PERMITTED = "Release Permitted"
NO_SPEC = "Release Blocked / No Spec"
NO_REQUIREMENTS = "Release Blocked / No Requirements"
UNSTAMPED = "Release Blocked / Unstamped Scenario"
STAMPED_TWICE = "Release Blocked / Item Stamped Twice"
UNREADABLE_SPEC = "Release Blocked / Unreadable Spec"
REQUIREMENT_FAILED = "Release Blocked / Requirement Failed"
REQUIREMENT_UNTESTED = "Release Blocked / Requirement Untested"
UNREADABLE_RESULT = "Release Blocked / Unreadable Result"
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
    requirements: list[dict]
    needs: dict[str, list[str]] = field(default_factory=dict)   # need -> requirements covering it directly
    report: allure.VerificationReport | None = None
    risk_findings: list[Finding] = field(default_factory=list)
    unvalidated: list[str] = field(default_factory=list)
    spec_found: bool = True
    unstamped: list[tuple[str, str]] = field(default_factory=list)
    duplicates: dict[str, list[str]] = field(default_factory=dict)
    problems: list[str] = field(default_factory=list)


def fetch_state(dhf_dir: Path, results_dir: Path | None) -> State:
    """The spec, the record and the results."""
    spec = load_spec(dhf_dir)
    if spec is None:
        return State(dhf_dir.name, [], spec_found=False)
    state = State(dhf_dir.name, requirements(dhf_dir, spec), unstamped=spec.unstamped,
                  duplicates=spec.duplicates(), problems=spec.problems)
    state.needs = {n.id: spec.directly_addressed(n) for n in spec.needs}
    ids = {r["id"] for r in state.requirements}
    if ids:
        state.report = allure.reconcile(ids, results_dir)
        _, state.risk_findings = assess(dhf_dir, ids, set(state.report.verified))
        state.unvalidated = unvalidated_user_needs(dhf_dir, set(state.needs))
    return state


# Rules
# -----
# Pure: the state in, the events out.

def result_events(report: allure.VerificationReport, ids: set[str]) -> list[Event]:
    """What the executed results say: every requirement verified by a
    passing test, and no tag that names no requirement (a warning)."""
    events = [Event(REQUIREMENT_FAILED, f"requirement {i} FAILED verification ({report.by_id[i].failed} failing "
                                        "test(s))") for i in report.failed]
    events += [Event(REQUIREMENT_UNTESTED, f"requirement {i} not verified by any passing Allure test")
               for i in report.untested]
    events += [Event(UNREADABLE_RESULT, f"result file {name} cannot be read: it could hold a failed run")
               for name in report.unreadable]
    events += [Event(ORPHAN_TAG, f"Allure result tag {tag} matches no requirement")
               for tag in relevant_orphans(report.orphan_ids, ids)]
    return events


def derive(state: State) -> list[Event]:
    """Every event the rules produce, and the verdict over them:
      1. the spec is found and read whole: no unstamped scenario, no item
         stamped twice, no file it cannot read;
      2. at least one requirement;
      3. every requirement is verified by a passing test, and every result
         file can be read;
      4. every user need is addressed by a requirement that covers one of its
         rules or guarantees;
      5. no blocking finding of the risk rules (DI-44).
    A user need with no approved validation record (DI-33), a proposed risk
    and an orphan tag only warn. Whether a passing test genuinely verifies
    its requirement is judged by the review of the pull request."""
    if not state.spec_found:
        return [Event(NO_SPEC, "no spec found: name its plan directory as `spec` in the DHF's config.yml, "
                               "or keep it at .pi/plan in the repository")]
    events = [Event(UNSTAMPED, f"scenario {title!r} at {source} carries no @item id: stamp it")
              for source, title in state.unstamped]
    events += [Event(STAMPED_TWICE, f"{ident} is stamped on more than one scenario: {', '.join(sources)}")
               for ident, sources in sorted(state.duplicates.items())]
    events += [Event(UNREADABLE_SPEC, f"spec file cannot be read: {problem}") for problem in state.problems]
    ids = {r["id"] for r in state.requirements}
    if not ids:
        return events + [Event(NO_REQUIREMENTS, "the spec holds no stamped scenario (nothing to verify)")]
    results = result_events(state.report, ids)
    events += [e for e in results if e.blocking]
    events += [Event(RISK_FINDING if f.blocking else RISK_WARNING, f.message) for f in state.risk_findings]
    events += [Event(NEED_UNADDRESSED, f"user need {need} is addressed by no requirement covering its rules or "
                                       "guarantees")
               for need, covering in state.needs.items() if not covering]
    events += [Event(NEED_UNVALIDATED, f"user need {un} has no approved validation record "
                                       f"(add {state.dhf_name}/validation/<name>-validation.json "
                                       f"with \"user_need\": \"{un}\")")
               for un in state.unvalidated]
    events += [e for e in results if not e.blocking]
    if not any(e.blocking for e in events):  # a conclusion over all of them
        events.insert(0, Event(RELEASE_PERMITTED, "release permitted"))
    return events


# Command
# -------

@dataclass
class ReleaseResult:
    """The release gate's events, with the verified requirements they were derived from."""

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
        return [e.message for e in self.events if e.warning]


def run_release_gate(dhf_dir: Path, allure_results_dir: Path) -> ReleaseResult:
    """Run the release gate: fetch the state, derive its events."""
    state = fetch_state(Path(dhf_dir), Path(allure_results_dir))
    return ReleaseResult(verified=state.report.verified if state.report else [], events=derive(state))


# What the release gate blocks about the record as a whole, not about one
# requirement, user need or risk: the graph puts these on the record (DI-38).
WHOLE_RECORD = (NO_SPEC, NO_REQUIREMENTS, UNSTAMPED, STAMPED_TWICE, UNREADABLE_SPEC, UNREADABLE_RESULT)


def record_findings(dhf_dir: Path, allure_results_dir: Path | None = None) -> list[str]:
    """The release gate's blocking findings about the whole record, as it words them."""
    return [e.message for e in derive(fetch_state(Path(dhf_dir), allure_results_dir)) if e.name in WHOLE_RECORD]


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

    if result.verified:
        print(f"  [OK]   verified requirements: {', '.join(result.verified)}")

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
            "Release gate PASSED: every requirement of the spec "
            "is verified by a passing test, every user need is addressed, and every risk is evaluated, "
            "with its risk controls verified and its residual acceptable."
        )
        return 0

    print(
        "Release gate FAILED: do not release. Resolve every blocking item above "
        "(verify all requirements; address all user needs; resolve the risk findings)."
    )
    return 1
