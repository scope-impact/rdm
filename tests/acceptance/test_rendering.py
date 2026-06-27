"""Acceptance tests for the rendering context's design inputs (see dhf/).

Each test is the acceptance criterion ("live BDD") for a rendering design input,
tagged `@allure.story("DI-…")`. They exercise the real render engine / filters /
markdown extensions, so a passing tag is evidence the requirement is met.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

from rdm.render import invert_dependencies, join_to, md_indent
from tests.util import render_from_string

allure = pytest.importorskip("allure")


@allure.story("DI-7")
@allure.label("output", "rdm/render.py")
def test_renders_template_against_data_context() -> None:
    """DI-7: a Markdown template renders against a supplied data context."""
    out = render_from_string(
        "Device: {{ device.name }} v{{ device.version }}",
        context={"device": {"name": "VitalView", "version": "1.2"}},
    )
    assert "Device: VitalView v1.2" in out


@allure.story("DI-8")
@allure.label("output", "rdm/render.py")
def test_traceability_filters() -> None:
    """DI-8: the invert_dependencies / join_to / md_indent filters behave."""
    # invert_dependencies: edge A→B inverts to B being depended-on by {A}.
    assert invert_dependencies([{"id": "A", "deps": ["B"]}], "id", "deps") == [("B", {"A"})]
    # join_to: resolve foreign keys against a table by primary key.
    table = [{"id": "r1", "v": 1}, {"id": "r2", "v": 2}]
    assert join_to(["r2"], table) == [{"id": "r2", "v": 2}]
    # md_indent: shift headings deeper by header_shift.
    assert md_indent("# Title", header_shift=1) == "## Title"


@allure.story("DI-9")
@allure.label("output", "rdm/md_extensions/")
def test_markdown_post_processing() -> None:
    """DI-9: section numbering, vocabulary expansion, and auditor-note exclusion."""
    numbered = render_from_string(
        "## hello", config={"md_extensions": ["rdm.md_extensions.SectionNumberExtension"]}
    )
    assert numbered == "## 1.1 hello\n"

    excluded = render_from_string(
        "Spec [[1234:9.8.7.6]].",
        config={"md_extensions": ["rdm.md_extensions.AuditNoteExclusionExtension"]},
    )
    assert excluded == "Spec.\n"

    vocab = render_from_string(
        "apple\nbanana\n{% for v in first_pass_output.words | sort %}[{{ v }}]{% endfor %}",
        config={"md_extensions": ["rdm.md_extensions.VocabularyExtension"]},
    )
    assert "[apple][banana]" in vocab
