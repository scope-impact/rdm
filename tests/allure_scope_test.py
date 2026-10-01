"""Allure belongs to the acceptance tests only.

Acceptance (end-to-end) tests under ``tests/acceptance/`` are the evidence of
record: they carry ``@allure.story`` tags, steps and attachments. Unit tests
carry none, so nothing but an acceptance test can be counted as verifying a
design input. Read from the syntax tree, so fixture strings do not count.
"""

import ast
from pathlib import Path

TESTS = Path(__file__).resolve().parent


def _allure_uses(path: Path, root: Path = TESTS.parent) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    uses = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id == "allure":
            uses.append(f"{path.relative_to(root)}:{node.lineno} allure.{node.attr}")
        elif isinstance(node, (ast.Import, ast.ImportFrom)):
            names = [a.name for a in node.names] if isinstance(node, ast.Import) else [node.module or ""]
            if any(n == "allure" or n.startswith("allure.") for n in names):
                uses.append(f"{path.relative_to(root)}:{node.lineno} import allure")
    return uses


def test_unit_tests_carry_no_allure():
    offenders = [use for path in sorted(TESTS.rglob("*.py")) if "acceptance" not in path.parts
                 for use in _allure_uses(path)]
    assert offenders == [], "Allure is for acceptance tests only:\n" + "\n".join(offenders)


def test_the_guard_sees_a_real_use(tmp_path):
    sample = tmp_path / "sample_test.py"
    sample.write_text('import allure\n\nDOC = "@allure.story(\\"DI-1\\")"\n\n'
                      '@allure.story("DI-1")\ndef test_x():\n    pass\n')
    assert [u.split(" ", 1)[1] for u in _allure_uses(sample, tmp_path)] == ["import allure", "allure.story"]
