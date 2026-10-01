"""Acceptance test for Python tag discovery (DI-40, see dhf/).

Tagged `@allure.story("DI-40")`, over the real source-tag scanner and the
design gate's tag-linkage check that consumes it. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.record.allure import scan_source_tags

allure = pytest.importorskip("allure")

FIXTURE_WRITER = '''import allure

FIXTURE = """
@allure.story("DI-7")
def test_inside_a_string():
    pass
"""
# @allure.story("DI-8") in a comment


@allure.story("DI-1")
def test_writes_a_fixture(tmp_path):
    (tmp_path / "t.py").write_text('@allure.story("DI-9")\\n' + FIXTURE)


@allure.feature("DI-2")
class TestGroup:
    @allure.story("DI-3")
    async def test_method(self):
        pass
'''


@allure.story("DI-40")
@allure.label("output", "rdm/record/allure.py")
def test_python_tags_come_from_decorators_only(tmp_path: Path) -> None:
    """DI-40: tags from allure story/feature decorators on functions (sync and
    async) and classes and from a module-level pytestmark; none from strings or
    comments; a file that does not parse falls back to the decorator pattern."""
    tests = tmp_path / "tests"
    tests.mkdir()
    (tests / "test_writer.py").write_text(FIXTURE_WRITER)
    (tests / "test_marked.py").write_text(
        'import allure\npytestmark = [allure.story("DI-4"), allure.feature("DI-5")]\n\n'
        'def test_a():\n    assert "@allure.story(\\"DI-6\\")"\n')
    (tests / "test_single_mark.py").write_text(
        'import allure\npytestmark = allure.story("DI-10")\n\ndef test_b():\n    pass\n')
    (tests / "test_broken.py").write_text('@allure.story("DI-11")\ndef test_c(:\n')

    tags = scan_source_tags(tests)
    files = {tag: sorted(Path(f).name for f in paths) for tag, paths in tags.items()}

    # Decorators on functions, async methods and classes; module pytestmark (list or single).
    assert files["DI-1"] == ["test_writer.py"]
    assert files["DI-2"] == ["test_writer.py"]
    assert files["DI-3"] == ["test_writer.py"]
    assert files["DI-4"] == files["DI-5"] == ["test_marked.py"]
    assert files["DI-10"] == ["test_single_mark.py"]
    # Text inside strings and comments claims nothing.
    for tag in ("DI-6", "DI-7", "DI-8", "DI-9"):
        assert tag not in files, tag
    # A file that does not parse still has its decorator-pattern tags read.
    assert files["DI-11"] == ["test_broken.py"]
    assert set(files) == {"DI-1", "DI-2", "DI-3", "DI-4", "DI-5", "DI-10", "DI-11"}
