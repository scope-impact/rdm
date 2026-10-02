"""Acceptance test for the mutation-probe design input (see dhf/).

The test ("live BDD") that verifies DI-34, tagged `@allure.story`, over the
real `run_mutation_probe`. Uses an injected stub runner so the probe's mechanics
(apply / observe / restore) are tested without nesting pytest.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.evidence.mutation import (
    TESTS_FAILED,
    TESTS_PASSED,
    _pytest_runner,
    run_mutation_probe,
)

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402


def _runner(src: Path, original: str, mutated: str, seen: list | None = None):
    """Passes on the unmutated file, returns ``mutated`` under the mutation."""
    def run() -> str:
        if seen is not None:
            seen.append(src.read_text())
        return TESTS_PASSED if src.read_text() == original else mutated
    return run


@allure.story("DI-34")
@allure.label("output", "rdm/evidence/mutation.py")
def test_mutation_probe_applies_reports_and_restores(tmp_path: Path) -> None:
    """DI-34: the probe applies the mutation while the test runs, reports
    killed/survived from the result, always restores the file, and rejects an
    ambiguous mutation site."""
    src = tmp_path / "m.py"
    original = "VALUE = 1\n"
    src.write_text(original)

    # A runner that "catches" the mutation (tests FAIL) → killed. It also records
    # what the file looked like *while it ran*, proving the mutation was applied.
    with verification_step("A runner that \"catches\" the mutation (tests FAIL) → killed. It also records what the "
                           "file looked…"):
        seen: list[str] = []
        res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", _runner(src, original, TESTS_FAILED, seen))
        assert seen == [original, "VALUE = 2\n"]        # run unmutated first, then with the mutation live
        assert res["killed"] and not res["survived"]     # caught
        assert res["restored"] and src.read_text() == original  # always reverted

    with verification_step("A runner that does NOT catch it (tests pass) → survived (a test hole)"):
        res2 = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", lambda: TESTS_PASSED)
        assert res2["survived"] and not res2["killed"]
        assert src.read_text() == original

    with verification_step("An ambiguous mutation site (text not unique) is rejected; file untouched"):
        src.write_text("x = 1\nx = 1\n")
        assert "error" in run_mutation_probe(src, "x = 1", "x = 2", lambda: TESTS_FAILED)
        assert src.read_text() == "x = 1\nx = 1\n"

    # A test that does not pass unmutated is refused: it would "catch" any
    # mutation. Error, never KILLED, and the mutation is never applied.
    with verification_step("A test that does not pass unmutated is refused: it would \"catch\" any mutation. Error, "
                           "never…"):
        src.write_text(original)
        seen = []
        res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", lambda: seen.append(src.read_text()) or TESTS_FAILED)
        assert "error" in res and "fails before any mutation" in res["error"] and not res.get("killed")
        assert seen == [original] and src.read_text() == original
        res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", lambda: "pytest did not run cleanly (exit 2)")
        assert "error" in res and "did not execute cleanly" in res["error"] and not res.get("killed")


@allure.story("DI-34")
@allure.label("output", "rdm/evidence/mutation.py")
def test_mutation_probe_only_a_genuine_test_failure_is_a_kill(tmp_path: Path, monkeypatch) -> None:
    """DI-34: a run that errors or collects no tests is reported as an error,
    never as a kill — a typo'd selector must not manufacture killing evidence."""
    src = tmp_path / "m.py"
    original = "VALUE = 1\n"
    src.write_text(original)

    with verification_step("A runner that did not execute cleanly → error, NOT killed; still restored"):
        res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2",
                                 _runner(src, original, "no tests matched the selector (exit 5)"))
        assert "error" in res and "no tests matched" in res["error"]
        assert not res.get("killed") and not res.get("survived")
        assert res["restored"] and src.read_text() == original

    # The real pytest runner maps exit codes the same way: in a directory whose
    # suite would PASS, a selector matching nothing must come back as an error
    # (pytest exit 5: no tests collected), not as a failure — the old
    # `returncode != 0` logic counted exactly this as KILLED.
    with verification_step("The real pytest runner maps exit codes the same way: in a directory whose suite would "
                           "PASS, a…"):
        (tmp_path / "test_trivial.py").write_text("def test_ok():\n    assert True\n")
        monkeypatch.chdir(tmp_path)
        assert _pytest_runner("test_ok")() == TESTS_PASSED
        outcome = _pytest_runner("no_such_test_anywhere_xyz")()
        assert outcome not in (TESTS_PASSED, TESTS_FAILED)
        assert "no tests matched" in outcome and "exit 5" in outcome


@allure.story("DI-47")
@allure.label("output", "rdm/evidence/mutation.py")
def test_mutation_probe_restore_survives_interruption(tmp_path: Path) -> None:
    """DI-47: the original is journaled before mutating and
    an interrupted probe is recovered on the next probe; SIGTERM mid-window
    still restores; every write advances the file's mtime to a fresh whole
    second so stale bytecode can never be served to a same-second,
    size-preserving mutation."""
    import signal

    from rdm.evidence.mutation import JOURNAL_SUFFIX, recover_interrupted_probe

    src = tmp_path / "m.py"
    journal = tmp_path / ("m.py" + JOURNAL_SUFFIX)
    original = "VALUE = 1\n"

    # The journal exists (holding the original) exactly while the probe runs,
    # and is gone after a normal probe.
    with verification_step("The journal exists (holding the original) exactly while the probe runs, and is gone "
                           "after a…"):
        src.write_text(original)
        seen = {}

        def observing_runner() -> str:
            if src.read_text() == original:
                return TESTS_PASSED
            seen["journal_during"] = journal.read_text()
            return TESTS_FAILED

        res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", observing_runner)
        assert seen["journal_during"] == original       # original journaled while mutated
        assert not journal.exists() and res["restored"]

    # An INTERRUPTED probe (process killed mid-window: file mutated, journal
    # left behind) is recovered by the next probe of that file.
    with verification_step("An INTERRUPTED probe (process killed mid-window: file mutated, journal left behind) is "
                           "recovered…"):
        src.write_text("VALUE = 2\n")                    # the crash left the mutant live
        journal.write_text(original)                     # ...and the journal behind
        res = run_mutation_probe(src, "VALUE = 1", "VALUE = 2", _runner(src, original, TESTS_FAILED))
        assert res["recovered"] and res["killed"]        # recovered, then probed normally
        assert src.read_text() == original and not journal.exists()
        assert recover_interrupted_probe(src) is False   # nothing left to recover
    with verification_step("A leftover journal is restored only while the file holds that probe's mutant"):
        from rdm.evidence.mutation import MUTANT_SUFFIX
        src.write_text("VALUE = 3\n")                    # edited since the probe died
        journal.write_text(original)
        (tmp_path / ("m.py" + MUTANT_SUFFIX)).write_text("VALUE = 2\n")
        assert recover_interrupted_probe(src) is False
        assert src.read_text() == "VALUE = 3\n" and not journal.exists()
        assert not (tmp_path / ("m.py" + MUTANT_SUFFIX)).exists()
    with verification_step("The file is restored to its exact bytes, its line endings included"):
        crlf = b"VALUE = 1\r\nOTHER = 0\r\n"
        src.write_bytes(crlf)
        res = run_mutation_probe(src, "VALUE = 1\nOTHER", "VALUE = 2\nOTHER",
                                 lambda: TESTS_PASSED if src.read_bytes() == crlf else TESTS_FAILED)
        assert res["killed"] and res["restored"] and src.read_bytes() == crlf
        src.write_text(original)

    # SIGTERM during the probe window restores in-process (a shell timeout
    # sends TERM first — the incident this guards against).
    with verification_step("SIGTERM during the probe window restores in-process (a shell timeout sends TERM first — "
                           "the…"):
        src.write_text(original)

        def terminating_runner() -> str:
            if src.read_text() == original:
                return TESTS_PASSED
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
    with verification_step("Every write advances the mtime to a FRESH WHOLE SECOND (the pyc-staleness defense). "
                           "CPython's…"):
        import os

        future = int(src.stat().st_mtime) + 100
        os.utime(src, (future, future))
        mtime_seconds = []

        def mtime_runner() -> str:
            if src.read_text() == original:
                return TESTS_PASSED
            mtime_seconds.append(int(src.stat().st_mtime))
            return TESTS_FAILED

        run_mutation_probe(src, "VALUE = 1", "VALUE = 2", mtime_runner)
        after = int(src.stat().st_mtime)
        assert future < mtime_seconds[0] < after         # strictly newer whole seconds
