"""Acceptance test for Python tag discovery (DI-40, see dhf/).

Tagged `@allure.story("DI-40")`, over the real source-tag scanner and the
design gate's tag-linkage check that consumes it. Skips cleanly if
allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from rdm.specification.tags import scan_source_tags

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402

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
@allure.story("DI-12")
class TestGroup:
    @allure.story("DI-3")
    async def test_method(self):
        pass
'''


@allure.story("DI-40")
@allure.label("output", "rdm/specification/tags.py")
def test_python_tags_come_from_decorators_only(tmp_path: Path) -> None:
    """DI-40: tags from allure story decorators on functions (sync and async)
    and classes and from a module-level pytestmark; none from strings,
    comments or feature decorators; a file that does not parse falls back to
    the decorator pattern."""
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

    with verification_step("Story decorators on functions, async methods and classes; module pytestmark (list or "
                           "single)"):
        assert files["DI-1"] == ["test_writer.py"]
        assert files["DI-3"] == ["test_writer.py"]
        assert files["DI-12"] == ["test_writer.py"]  # the story on the test class
        assert files["DI-4"] == ["test_marked.py"]
        assert files["DI-10"] == ["test_single_mark.py"]
    with verification_step("Only the story names a design input: a feature (the context) does not"):
        assert "DI-2" not in files and "DI-5" not in files
    with verification_step("Text inside strings and comments claims nothing"):
        for tag in ("DI-6", "DI-7", "DI-8", "DI-9"):
            assert tag not in files, tag
    with verification_step("A file that does not parse still has its decorator-pattern tags read"):
        assert files["DI-11"] == ["test_broken.py"]
        assert set(files) == {"DI-1", "DI-3", "DI-4", "DI-10", "DI-11", "DI-12"}
    with verification_step("Only tests claim: a story on a helper function or class is no tag, and the story "
                           "decorator counts under any name the file imports it as"):
        (tests / "test_helpers.py").write_text(
            'import allure\n\n\n@allure.story("DI-20")\ndef make_fixture():\n    pass\n\n\n'
            '@allure.story("DI-21")\nclass Helper:\n    pass\n')
        (tests / "test_imported.py").write_text(
            'from allure import story\nfrom allure import story as s\nimport allure as a\n\n\n'
            '@story("DI-22")\ndef test_d():\n    pass\n\n\n@s("DI-23")\ndef test_e():\n    pass\n\n\n'
            '@a.story("DI-24")\ndef test_f():\n    pass\n')
        files = {tag: sorted(Path(f).name for f in paths) for tag, paths in scan_source_tags(tests).items()}
        assert "DI-20" not in files and "DI-21" not in files
        assert all(files.get(tag) == ["test_imported.py"] for tag in ("DI-22", "DI-23", "DI-24")), files
