"""Acceptance test for the mutation-probe design input (see dhf/).

Acceptance criterion ("live BDD") for DI-21, tagged `@allure.story`, over the
real `run_mutation_probe`. Uses an injected stub runner so the probe's mechanics
(apply / observe / restore) are tested without nesting pytest.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.story_audit.mutation import (
    TESTS_FAILED,
    TESTS_PASSED,
    _pytest_runner,
    run_mutation_probe,
)

allure = pytest.importorskip("allure")


@allure.story("DI-21")
@allure.label("output", "rdm/story_audit/mutation.py")
def test_mutation_probe_applies_reports_and_restores(tmp_path: Path) -> None:
    """DI-21: the probe applies the mutation while the test runs, reports
    killed/survived from the result, always restores the file, and rejects an
    ambiguous mutation site."""
    src = tmp_path / "m.py"
    original = "VALUE = 1\n"
    src.write_text(original)

    # A runner that "catches" the mutation (tests FAIL) → killed. It also records
    # what the file looked like *while it ran*, proving the mutation was applied.
    seen = {}

    def catching_runner() -> str:
        seen["during"] = src.read_text()
        return TESTS_FAILED  # tests ran and failed under the mutation

    res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", catching_runner)
    assert seen["during"] == "VALUE = 2\n"          # mutation was live during the run
    assert res["killed"] and not res["survived"]     # caught
    assert res["restored"] and src.read_text() == original  # always reverted

    # A runner that does NOT catch it (tests pass) → survived (a test hole).
    res2 = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", lambda: TESTS_PASSED)
    assert res2["survived"] and not res2["killed"]
    assert src.read_text() == original

    # An ambiguous mutation site (text not unique) is rejected; file untouched.
    src.write_text("x = 1\nx = 1\n")
    assert "error" in run_mutation_probe(src, "x = 1", "x = 2", lambda: TESTS_FAILED)
    assert src.read_text() == "x = 1\nx = 1\n"


@allure.story("DI-21")
@allure.label("output", "rdm/story_audit/mutation.py")
def test_mutation_probe_only_a_genuine_test_failure_is_a_kill(tmp_path: Path, monkeypatch) -> None:
    """DI-21: a run that errors or collects no tests is reported as an error,
    never as a kill — a typo'd selector must not manufacture killing evidence."""
    src = tmp_path / "m.py"
    original = "VALUE = 1\n"
    src.write_text(original)

    # A runner that did not execute cleanly → error, NOT killed; still restored.
    res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2",
                             lambda: "no tests matched the selector (exit 5)")
    assert "error" in res and "no tests matched" in res["error"]
    assert not res.get("killed") and not res.get("survived")
    assert res["restored"] and src.read_text() == original

    # The real pytest runner maps exit codes the same way: in a directory whose
    # suite would PASS, a selector matching nothing must come back as an error
    # (pytest exit 5: no tests collected), not as a failure — the old
    # `returncode != 0` logic counted exactly this as KILLED.
    (tmp_path / "test_trivial.py").write_text("def test_ok():\n    assert True\n")
    monkeypatch.chdir(tmp_path)
    assert _pytest_runner("test_ok")() == TESTS_PASSED
    outcome = _pytest_runner("no_such_test_anywhere_xyz")()
    assert outcome not in (TESTS_PASSED, TESTS_FAILED)
    assert "no tests matched" in outcome and "exit 5" in outcome


@allure.story("DI-21")
@allure.label("output", "rdm/story_audit/mutation.py")
def test_mutation_probe_restore_survives_interruption(tmp_path: Path) -> None:
    """DI-21 (defense in depth): the original is journaled before mutating and
    an interrupted probe is recovered on the next probe; SIGTERM mid-window
    still restores; every write advances the file's mtime to a fresh whole
    second so stale bytecode can never be served to a same-second,
    size-preserving mutation."""
    import signal

    from rdm.story_audit.mutation import JOURNAL_SUFFIX, recover_interrupted_probe

    src = tmp_path / "m.py"
    journal = tmp_path / ("m.py" + JOURNAL_SUFFIX)
    original = "VALUE = 1\n"

    # The journal exists (holding the original) exactly while the probe runs,
    # and is gone after a normal probe.
    src.write_text(original)
    seen = {}

    def observing_runner() -> str:
        seen["journal_during"] = journal.read_text()
        return TESTS_FAILED

    res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", observing_runner)
    assert seen["journal_during"] == original       # original journaled while mutated
    assert not journal.exists() and res["restored"]

    # An INTERRUPTED probe (process killed mid-window: file mutated, journal
    # left behind) is recovered by the next probe of that file.
    src.write_text("VALUE = 2\n")                    # the crash left the mutant live
    journal.write_text(original)                     # ...and the journal behind
    res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", lambda: TESTS_FAILED)
    assert res["recovered"] and res["killed"]        # recovered, then probed normally
    assert src.read_text() == original and not journal.exists()
    assert recover_interrupted_probe(src) is False   # nothing left to recover

    # SIGTERM during the probe window restores in-process (a shell timeout
    # sends TERM first — the incident this guards against).
    src.write_text(original)

    def terminating_runner() -> str:
        signal.raise_signal(signal.SIGTERM)
        return TESTS_PASSED  # unreachable

    with pytest.raises(KeyboardInterrupt):
        run_mutation_probe(src, "VALUE = 1", "VALUE = 2", terminating_runner)
    assert src.read_text() == original               # restored despite the TERM
    assert not journal.exists()

    # Every write advances the mtime to a FRESH WHOLE SECOND (the
    # pyc-staleness defense). CPython's bytecode-cache key is (mtime truncated
    # to whole seconds, size), so distinct nanoseconds within one second do
    # NOT invalidate the cache — the mtime SECONDS must strictly increase on
    # every write. Pinning the file's mtime into the future first makes this
    # deterministic: a clock-based bump (the disproven nanosecond scheme)
    # would move the mtime BACKWARD here and fail, in any timing.
    import os

    future = int(src.stat().st_mtime) + 100
    os.utime(src, (future, future))
    mtime_seconds = []

    def mtime_runner() -> str:
        mtime_seconds.append(int(src.stat().st_mtime))
        return TESTS_FAILED

    run_mutation_probe(src, "VALUE = 1", "VALUE = 2", mtime_runner)
    after = int(src.stat().st_mtime)
    assert future < mtime_seconds[0] < after         # strictly newer whole seconds
