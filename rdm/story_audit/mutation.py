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
every write bumps the file's mtime by a unique nanosecond so CPython's
(mtime-seconds, size) ``.pyc`` key can never serve stale bytecode to a
same-second, size-preserving mutation.
"""

from __future__ import annotations

import itertools
import os
import signal
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Callable

# Sidecar journal suffix: `<file>.rdm-probe-orig` holds the pre-mutation
# original for crash recovery. Left behind only if the probe process died
# mid-window; the next probe of the same file restores from it first.
JOURNAL_SUFFIX = ".rdm-probe-orig"

_write_counter = itertools.count()


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
    file_path.write_text(journal.read_text(encoding="utf-8"), encoding="utf-8")
    _bump_mtime(file_path)
    journal.unlink()
    return True


def _bump_mtime(file_path: Path) -> None:
    """Give the file a unique nanosecond mtime so the bytecode cache key
    (mtime-seconds is too coarse) always invalidates after a write."""
    unique_ns = time.time_ns() + next(_write_counter)
    os.utime(file_path, ns=(unique_ns, unique_ns))


def _write(file_path: Path, content: str) -> None:
    file_path.write_text(content, encoding="utf-8")
    _bump_mtime(file_path)


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
    run_tests: Callable[[], bool],
) -> dict:
    """Apply ``find -> replace`` once in ``file_path``, run ``run_tests`` (returns
    True if the suite passed), then restore the file unconditionally.

    Returns ``{"killed": bool, "survived": bool, "restored": bool, "recovered":
    bool}`` or ``{"error": ...}`` if ``find`` does not occur exactly once
    (ambiguous or absent mutation site). ``recovered`` reports that a leftover
    journal from an interrupted earlier probe was restored first.
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
            tests_passed = run_tests()
        finally:
            _write(file_path, original)  # always revert
            journal.unlink(missing_ok=True)
    return {
        "killed": not tests_passed,      # tests failed under the mutation -> the test catches it
        "survived": tests_passed,        # tests passed with the code broken -> the test is hollow
        "restored": file_path.read_text(encoding="utf-8") == original,
        "recovered": recovered,
    }


def _pytest_runner(test_selector: str) -> Callable[[], bool]:
    """A run_tests callable that runs ``pytest -k <selector>`` and returns whether
    it passed. pytest is a dev/CI dependency, invoked as a subprocess (like git).

    Stale-bytecode defense lives in the probe's writes (unique-ns mtime bumps),
    so the repo's warm ``__pycache__`` stays usable -- no cold-cache rerun per
    probe (the slowdown that caused the timeout incident)."""
    def run() -> bool:
        proc = subprocess.run(
            ["python", "-m", "pytest", "-q", "-p", "no:cacheprovider", "-k", test_selector],
            capture_output=True,
            text=True,
        )
        return proc.returncode == 0
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
