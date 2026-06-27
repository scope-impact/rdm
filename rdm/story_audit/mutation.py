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
"""

from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Callable


def run_mutation_probe(
    file_path: Path,
    find: str,
    replace: str,
    run_tests: Callable[[], bool],
) -> dict:
    """Apply ``find -> replace`` once in ``file_path``, run ``run_tests`` (returns
    True if the suite passed), then restore the file unconditionally.

    Returns ``{"killed": bool, "survived": bool, "restored": bool}`` or
    ``{"error": ...}`` if ``find`` does not occur exactly once (ambiguous or
    absent mutation site).
    """
    original = file_path.read_text(encoding="utf-8")
    occurrences = original.count(find)
    if occurrences != 1:
        return {"error": f"`find` text occurs {occurrences} time(s) in {file_path} (need exactly 1)"}
    try:
        file_path.write_text(original.replace(find, replace, 1), encoding="utf-8")
        tests_passed = run_tests()
    finally:
        file_path.write_text(original, encoding="utf-8")  # always revert
    return {
        "killed": not tests_passed,      # tests failed under the mutation -> the test catches it
        "survived": tests_passed,        # tests passed with the code broken -> the test is hollow
        "restored": file_path.read_text(encoding="utf-8") == original,
    }


def _pytest_runner(test_selector: str) -> Callable[[], bool]:
    """A run_tests callable that runs ``pytest -k <selector>`` and returns whether
    it passed. pytest is a dev/CI dependency, invoked as a subprocess (like git)."""
    def run() -> bool:
        proc = subprocess.run(
            ["python", "-m", "pytest", "-q", "-k", test_selector],
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
    if not result["restored"]:
        print(f"WARNING: {path} may not have been restored — check version control.")
    if result["killed"]:
        print(f"KILLED: `{test}` failed under the mutation — it verifies this behaviour. ✓")
        return 0
    print(f"SURVIVED: `{test}` PASSED with the code mutated — it does NOT verify this behaviour. ✗")
    return 1
