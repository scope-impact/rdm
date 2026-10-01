"""
Release-gate self-test by fault injection (DI-38).

A gate that never fires looks exactly like a gate that is never needed. This
measures the release gate itself, the way the Phoenix architecture measures
its trust dashboard: build a synthetic DHF that should pass (precision -- the
gate must not block a clean record), then inject one fault at a time into a
fresh copy and confirm the gate blocks it *and names it* (recall -- a fault
blocked for an unrelated reason is not counted as caught).

    rdm story gate-selftest

Exit codes: 0 every fault caught and the baseline passes; 1 otherwise.
Needs ``git`` (the design gate's approval check is a git status check).
"""

from __future__ import annotations

import json
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from rdm.record import anchor, journal
from rdm.record.sdd import design_inputs

_DESIGN_DOC = """---
id: SDS-SYN-001
kind: design
context: core
satisfies: [UN-1]
design_inputs:
  - id: DI-1
    text: "The system shall add two numbers."
    traces_to: [UN-1]
  - id: DI-2
    text: "The system shall double a number."
    traces_to: [UN-1]
---

# Core — Software Design

Synthetic design document for the release-gate self-test.
"""

_VV_PLAN = """---
id: VVP-SYN
user_needs:
  - id: UN-1
    text: "A user can do arithmetic."
---

# Verification and Validation Plan (synthetic)
"""

_DESIGN_REVIEW = """---
id: DR-SYN
---

# Design review (synthetic)

Approved.
"""

_TESTS = '''import allure


@allure.story("DI-1")
def test_add():
    assert 1 + 1 == 2


@allure.story("DI-2")
def test_double():
    assert 2 * 2 == 4
'''

_REVIEWER = "gate-selftest (synthetic reviewer)"


def _git(repo: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-c", "user.name=rdm-selftest", "-c", "user.email=selftest@rdm.invalid",
         "-c", "commit.gpgsign=false", "-C", str(repo), *args],
        check=True, capture_output=True, text=True,
    )


def _write_result(results: Path, di_id: str, status: str = "passed") -> None:
    results.mkdir(parents=True, exist_ok=True)
    body = {
        "name": f"test for {di_id}",
        "status": status,
        "labels": [{"name": "story", "value": di_id}],
    }
    (results / f"{di_id}-result.json").write_text(json.dumps(body), encoding="utf-8")


@dataclass
class Fixture:
    repo: Path
    dhf: Path
    results: Path

    @property
    def design_doc(self) -> Path:
        return self.dhf / "documents" / "design" / "core.md"

    @property
    def tests_file(self) -> Path:
        return self.repo / "tests" / "test_core.py"

    def commit(self, message: str) -> None:
        _git(self.repo, "add", "-A")
        _git(self.repo, "commit", "-q", "-m", message)

    def record(self, di_id: str, verdict: str = "faithful", uncovered: list[str] | None = None) -> None:
        from rdm.story_audit.design_gate import record_verdict

        record_verdict(self.dhf, di_id, verdict, reviewer=_REVIEWER,
                       rationale="synthetic verdict", reviewed_tests=[],
                       uncovered_clauses=uncovered or [])


def build_fixture(root: Path) -> Fixture:
    """A synthetic DHF the release gate should pass: approved docs, every input
    verified and faithfully reviewed, an intact journal, and a current lock."""
    repo = Path(root)
    dhf = repo / "dhf"
    (dhf / "documents" / "design").mkdir(parents=True)
    (repo / "tests").mkdir()
    fx = Fixture(repo=repo, dhf=dhf, results=repo / "allure-results")
    fx.design_doc.write_text(_DESIGN_DOC, encoding="utf-8")
    (dhf / "documents" / "verification_and_validation_plan.md").write_text(_VV_PLAN, encoding="utf-8")
    (dhf / "documents" / "design_review.md").write_text(_DESIGN_REVIEW, encoding="utf-8")
    fx.tests_file.write_text(_TESTS, encoding="utf-8")
    _git(repo, "init", "-q")
    for di in design_inputs(dhf):
        _write_result(fx.results, di["id"])
        fx.record(di["id"])
    anchor.write_lock(dhf, design_inputs(dhf))
    fx.commit("synthetic approved record")
    return fx


# --- fault injectors: each breaks exactly one link of the chain -------------

def _untested(fx: Fixture) -> None:
    (fx.results / "DI-2-result.json").unlink()


def _failing(fx: Fixture) -> None:
    _write_result(fx.results, "DI-2", status="failed")


def _unreviewed(fx: Fixture) -> None:
    (fx.dhf / "faithfulness" / "DI-2-faithfulness.json").unlink()


def _unfaithful(fx: Fixture) -> None:
    fx.record("DI-2", verdict="unfaithful")


def _partial(fx: Fixture) -> None:
    fx.record("DI-2", uncovered=["doubling a negative number"])


def _stale(fx: Fixture) -> None:
    fx.tests_file.write_text(_TESTS.replace("assert 2 * 2 == 4", "assert 2 * 3 == 6"), encoding="utf-8")


def _orphan_need(fx: Fixture) -> None:
    plan = fx.dhf / "documents" / "verification_and_validation_plan.md"
    plan.write_text(_VV_PLAN.replace(
        '    text: "A user can do arithmetic."\n',
        '    text: "A user can do arithmetic."\n  - id: UN-2\n    text: "A user can do trigonometry."\n',
    ), encoding="utf-8")
    fx.commit("register a user need nothing addresses")


def _uncommitted(fx: Fixture) -> None:
    with fx.design_doc.open("a", encoding="utf-8") as handle:
        handle.write("\nAn unapproved edit.\n")


def _broken_journal(fx: Fixture) -> None:
    path = journal.journal_path(fx.dhf)
    lines = path.read_text(encoding="utf-8").splitlines()
    first = json.loads(lines[0])
    first["payload"]["verdict"] = "unfaithful"  # edit history, keep the old hash
    lines[0] = json.dumps(first, sort_keys=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _reworded(fx: Fixture) -> None:
    fx.design_doc.write_text(_DESIGN_DOC.replace("add two numbers", "add two or more numbers"),
                             encoding="utf-8")
    fx.commit("reword DI-1 without re-locking")


# (name, injector, text the release gate must name for the fault to count)
FAULTS: list[tuple[str, Callable[[Fixture], None], str]] = [
    ("untested design input", _untested, "DI-2 not verified by any passing"),
    ("failing test", _failing, "DI-2 FAILED verification"),
    ("unreviewed verdict", _unreviewed, "DI-2 has no faithfulness review"),
    ("unfaithful verdict", _unfaithful, "DI-2 FAILED faithfulness review"),
    ("partial verdict", _partial, "DI-2 is only PARTIALLY verified"),
    ("stale verdict", _stale, "DI-2 faithfulness review is STALE"),
    ("user need no input traces to", _orphan_need, "user need UN-2 is addressed by no design input"),
    ("uncommitted design-document edit", _uncommitted, "design control not met"),
    ("broken journal", _broken_journal, "journal fails verification"),
    ("reworded locked design input", _reworded, "DI-1 was reworded since it was locked"),
]


@dataclass
class FaultOutcome:
    name: str
    caught: bool
    blocking: list[str] = field(default_factory=list)


@dataclass
class SelftestResult:
    baseline_blocking: list[str] = field(default_factory=list)
    faults: list[FaultOutcome] = field(default_factory=list)

    @property
    def missed(self) -> list[str]:
        return [f.name for f in self.faults if not f.caught]

    @property
    def passed(self) -> bool:
        return not self.baseline_blocking and not self.missed


def _gate(fx: Fixture) -> list[str]:
    from rdm.story_audit.design_gate import run_release_gate

    return run_release_gate(fx.dhf, fx.results).blocking


def run_gate_selftest(
    faults: list[tuple[str, Callable[[Fixture], None], str]] | None = None,
) -> SelftestResult:
    """Run the baseline and every fault, each in its own scratch repository."""
    result = SelftestResult()
    with tempfile.TemporaryDirectory(prefix="rdm-gate-selftest-") as scratch:
        result.baseline_blocking = _gate(build_fixture(Path(scratch) / "baseline"))
        for index, (name, inject, expected) in enumerate(FAULTS if faults is None else faults):
            fx = build_fixture(Path(scratch) / f"fault-{index}")
            inject(fx)
            blocking = _gate(fx)
            result.faults.append(FaultOutcome(name, any(expected in m for m in blocking), blocking))
    return result


def gate_selftest_command() -> int:
    """Run ``rdm story gate-selftest`` and print the caught/missed table."""
    result = run_gate_selftest()
    print("Release-gate self-test (fault injection on a synthetic DHF)\n")
    if result.baseline_blocking:
        print("  [FAIL]   clean baseline was BLOCKED (false positive):")
        for reason in result.baseline_blocking:
            print(f"             - {reason}")
    else:
        print("  [OK]     clean baseline passes")
    for outcome in result.faults:
        print(f"  {'[CAUGHT]' if outcome.caught else '[MISSED]'} {outcome.name}")
    caught = len(result.faults) - len(result.missed)
    print(f"\n  recall: {caught}/{len(result.faults)} fault(s) caught")
    print()
    if result.passed:
        print("Gate self-test PASSED: the clean record passes and every injected fault is blocked.")
        return 0
    print("Gate self-test FAILED: "
          + ("; ".join(filter(None, [
              "the clean baseline is blocked" if result.baseline_blocking else "",
              f"escaped: {', '.join(result.missed)}" if result.missed else "",
          ]))))
    return 1
