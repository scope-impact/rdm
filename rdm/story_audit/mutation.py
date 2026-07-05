"""
Mutation probe: prove a test would fail if the code were wrong.

Apply a one-line source mutation, run a test, and report whether the test
**catches** it (killed -> the test verifies that behaviour) or not (survived ->
the test has a hole), then ALWAYS restore the file. This is the enforcement half
of the test-faithfulness review: it turns a reviewer's claim ("this test would
fail if X were broken") into executed evidence.

    rdm story mutation-probe --file rdm/gaps.py \
        --find 'include_files, reduced = _split_out_include_files(...)' \
        --replace 'include_files, reduced = set(), raw' \
        --test test_ships_composable_builtin_checklists

A SURVIVED mutation is the proof of a hollow or partial test -- exactly what
DI-11's original test would have been.

The restore guarantee is defended in depth (DI-21): a bare ``finally`` dies
with the process, so the original is journaled to a sidecar (recovered on the
next probe of that file -- survives SIGKILL), SIGTERM during the probe window
is converted to an exception (a shell timeout still restores in-process), and
every write advances the file's mtime to a fresh whole second so CPython's
(mtime-seconds, size) ``.pyc`` key can never serve stale bytecode to a
same-second, size-preserving mutation. (Sub-second bumps are NOT enough: the
pyc key truncates mtime to whole seconds -- an independent review proved a
unique-nanosecond scheme let exactly such a mutant run stale.)
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Callable

# Test-run outcomes. A probe verdict is only meaningful when the run executed
# cleanly: anything other than these two values is an error description --
# reported as an error, never as a kill (DI-21).
TESTS_PASSED = "passed"
TESTS_FAILED = "failed"

# Sidecar journal suffix: `<file>.rdm-probe-orig` holds the pre-mutation
# original for crash recovery. Left behind only if the probe process died
# mid-window; the next probe of the same file restores from it first.
JOURNAL_SUFFIX = ".rdm-probe-orig"


def _journal_path(file_path: Path) -> Path:
    return file_path.with_name(file_path.name + JOURNAL_SUFFIX)


def recover_interrupted_probe(file_path: Path) -> bool:
    """Restore ``file_path`` from a leftover probe journal, if one exists.

    Returns True when a recovery happened. Called automatically at the start
    of every probe; safe to call any time.
    """
    journal = _journal_path(file_path)
    if not journal.exists():
        return False
    _write(file_path, journal.read_text(encoding="utf-8"))
    journal.unlink()
    return True


def _advance_mtime(file_path: Path, previous: int) -> None:
    """Stamp the file with a fresh WHOLE-second mtime, strictly beyond
    ``previous`` (its mtime before the write). CPython's pyc validation key is
    (source mtime truncated to whole seconds, source size), so a sub-second
    bump is invisible to it; and because every write lands strictly beyond
    every earlier one, no two versions of the file can ever share a cache key
    — even back-to-back writes within one wall-clock second."""
    fresh = max(previous + 1, int(time.time()))
    os.utime(file_path, (fresh, fresh))


def _write(file_path: Path, content: str) -> None:
    # The pre-write mtime must be captured BEFORE write_text restamps the
    # file with the current clock, or monotonicity is lost.
    previous = int(file_path.stat().st_mtime)
    file_path.write_text(content, encoding="utf-8")
    _advance_mtime(file_path, previous)


@contextmanager
def _restore_on_sigterm():
    """Convert SIGTERM into an exception for the duration of the probe window,
    so a shell timeout (TERM) unwinds through ``finally`` and restores. Only
    possible from the main thread; elsewhere the journal still covers us."""
    def _raise(signum, frame):
        raise KeyboardInterrupt("SIGTERM during mutation probe")

    try:
        previous = signal.signal(signal.SIGTERM, _raise)
    except ValueError:  # not the main thread
        yield
        return
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous)


def run_mutation_probe(
    file_path: Path,
    find: str,
    replace: str,
    run_tests: Callable[[], str],
) -> dict:
    """Apply ``find -> replace`` once in ``file_path``, run ``run_tests``
    (returns ``TESTS_PASSED``, ``TESTS_FAILED``, or an error description), then
    restore the file unconditionally.

    Returns ``{"killed": bool, "survived": bool, "restored": bool, "recovered":
    bool}``, or ``{"error": ..., "restored": ..., "recovered": ...}`` when the
    run did not execute cleanly, or ``{"error": ...}`` if ``find`` does not
    occur exactly once (ambiguous or absent mutation site). Only a genuine test
    failure counts as a kill -- a run that errored or collected no tests must
    not manufacture killing evidence (DI-21). ``recovered`` reports that a
    leftover journal from an interrupted earlier probe was restored first.
    """
    recovered = recover_interrupted_probe(file_path)
    original = file_path.read_text(encoding="utf-8")
    occurrences = original.count(find)
    if occurrences != 1:
        return {"error": f"`find` text occurs {occurrences} time(s) in {file_path} (need exactly 1)"}

    journal = _journal_path(file_path)
    journal.write_text(original, encoding="utf-8")
    with _restore_on_sigterm():
        try:
            _write(file_path, original.replace(find, replace, 1))
            outcome = run_tests()
        finally:
            _write(file_path, original)  # always revert
            journal.unlink(missing_ok=True)
    restored = file_path.read_text(encoding="utf-8") == original
    if outcome not in (TESTS_PASSED, TESTS_FAILED):
        return {"error": f"test run did not execute cleanly: {outcome}",
                "restored": restored, "recovered": recovered}
    return {
        "killed": outcome == TESTS_FAILED,   # tests ran and failed -> the test catches it
        "survived": outcome == TESTS_PASSED,  # tests passed with the code broken -> the test is hollow
        "restored": restored,
        "recovered": recovered,
    }


def _pytest_runner(test_selector: str) -> Callable[[], str]:
    """A run_tests callable that runs ``pytest -k <selector>`` and maps its exit
    code to a probe outcome. pytest is a dev/CI dependency, invoked as a
    subprocess (like git) under the current interpreter.

    Only exit code 1 ("tests were collected and run, some failed") counts as
    ``TESTS_FAILED``. Exit 5 means no tests matched the selector (e.g. a typo'd
    test name) and 2/3/4 mean the run was interrupted or errored -- in all of
    those the mutation was never actually exercised, so the outcome is an error
    description, never a kill (DI-21).

    Stale-bytecode defense lives in the probe's writes (unique-ns mtime bumps),
    so the repo's warm ``__pycache__`` stays usable -- no cold-cache rerun per
    probe (the slowdown that caused the timeout incident)."""
    def run() -> str:
        proc = subprocess.run(
            [sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider", "-k", test_selector],
            capture_output=True,
            text=True,
        )
        if proc.returncode == 0:
            return TESTS_PASSED
        if proc.returncode == 1:
            return TESTS_FAILED
        what = ("no tests matched the selector" if proc.returncode == 5
                else "pytest did not run cleanly")
        tail = (proc.stdout.strip() or proc.stderr.strip()).splitlines()
        detail = f": {tail[-1]}" if tail else ""
        return f"{what} (exit {proc.returncode}, -k {test_selector!r}{detail})"
    return run


def story_mutation_probe_command(
    file: str,
    find: str,
    replace: str,
    test: str,
) -> int:
    """Run `rdm story mutation-probe …`. Exit 0 if the mutation is KILLED (the
    test caught it), 1 if it SURVIVED (the test has a hole), 2 on bad invocation."""
    path = Path(file)
    if not path.exists():
        print(f"Error: file not found: {path}")
        return 2
    result = run_mutation_probe(path, find, replace, _pytest_runner(test))
    if "error" in result:
        print(f"Error: {result['error']}")
        return 2
    if result.get("recovered"):
        print(f"NOTE: recovered {path} from an interrupted earlier probe (journal restored).")
    if not result["restored"]:
        print(f"WARNING: {path} may not have been restored — check version control.")
    if result["killed"]:
        print(f"KILLED: `{test}` failed under the mutation — it verifies this behaviour. ✓")
        return 0
    print(f"SURVIVED: `{test}` PASSED with the code mutated — it does NOT verify this behaviour. ✗")
    return 1
