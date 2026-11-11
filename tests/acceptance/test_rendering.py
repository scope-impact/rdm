"""Acceptance tests for the rendering context's requirements (see spec/).

Each test ("live BDD") verifies a rendering requirement,
tagged with `@allure.tag` and its requirement id. They exercise the real render engine / filters /
markdown extensions, so a passing tag is evidence the requirement is met.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from tests.util import render_from_string

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]


@allure.tag("item-07-001")
@allure.label("output", "rdm/publishing/render.py")
def test_renders_template_against_data_context(tmp_path: Path, capsys, monkeypatch) -> None:
    """item-07-001: a Markdown template file rendered with Jinja2 against the data
    files supplied, each under its file name; a value the data does not supply
    is refused, naming it."""
    from rdm.main import cli

    monkeypatch.chdir(tmp_path)
    (tmp_path / "doc.md").write_text("# {{ device.name }}\n\nVersion {{ device.version }}, by {{ other.maker }}.\n")
    (tmp_path / "empty.yml").write_text("")
    (tmp_path / "device.yml").write_text("name: Acme Monitor\nversion: '1.2'\n")
    (tmp_path / "other.yml").write_text("maker: Acme Inc\n")
    capsys.readouterr()
    with verification_step("rdm render fills a Markdown template file from data files, each under its file name"):
        assert cli(["render", "doc.md", "empty.yml", "device.yml", "other.yml"]) == 0
        out = capsys.readouterr().out
        attach("rendered", out)
        assert out == "# Acme Monitor\n\nVersion 1.2, by Acme Inc.\n", out
    with verification_step("a value the template uses that the data does not supply is refused, naming it"):
        assert cli(["render", "doc.md", "empty.yml", "device.yml"]) == 2
        err = capsys.readouterr().err
        attach("refusal", err)
        assert "other" in err and "doc.md" in err, err
    with verification_step("a template that does not exist is refused, naming it"):
        assert cli(["render", "nope.md", "empty.yml"]) == 2
        assert "nope.md" in capsys.readouterr().err


@allure.tag("item-07-002")
@allure.label("output", "rdm/publishing/render.py")
def test_traceability_filters(tmp_path: Path, capsys, monkeypatch) -> None:
    """item-07-002: the invert_dependencies, join_to, md_indent, glob, include and
    gherkin filters, used from a template."""
    with verification_step("invert_dependencies: edge A→B inverts to B being depended-on by {A}"):
        out = render_from_string("{% for b, a in items | invert_dependencies('id', 'deps') %}{{ b }}<-{{ a | sort }}"
                                 "{% endfor %}", context={"items": [{"id": "A", "deps": ["B"]}]})
        assert out.strip() == "B<-['A']", out
    with verification_step("join_to: resolve foreign keys against a table by primary key"):
        table = [{"id": "r1", "v": 1}, {"id": "r2", "v": 2}]
        out = render_from_string("{% for r in ['r2', 'r1'] | join_to(table) %}{{ r.v }};{% endfor %}",
                                 context={"table": table})
        assert out.strip() == "2;1;", out
    with verification_step("md_indent: shift headings deeper by header_shift"):
        out = render_from_string("{{ snippet | md_indent(header_shift=1) }}", context={"snippet": "# Title"})
        assert out.strip() == "## Title", out
    with verification_step("glob and include: every feature file of a spec, carried into a document as "
                           "written, a document's frontmatter left out, paths from where rdm render runs"):
        monkeypatch.chdir(tmp_path)
        (tmp_path / "spec" / "b").mkdir(parents=True)
        (tmp_path / "spec" / "a").mkdir(parents=True)
        (tmp_path / "spec" / "b" / "spec.feature").write_text("Feature: B\n")
        (tmp_path / "spec" / "a" / "spec.feature").write_text("Feature: A\n  Scenario: one\n")
        (tmp_path / "arch.md").write_text("---\nid: SDS-1\n---\n# Architecture\n")
        out = render_from_string("{% for f in 'spec/*/spec.feature' | glob %}[{{ f }}]{{ f | include }}{% endfor %}"
                                 "|{{ 'arch.md' | include | md_indent(1) }}")
        attach("rendered", out)
        assert out.strip() == ("[spec/a/spec.feature]Feature: A\n  Scenario: one[spec/b/spec.feature]Feature: B"
                               "|## Architecture"), out
    with verification_step("gherkin: a feature file laid out as a document reads it: its Rule a bold line, each "
                           "scenario a bold title led by its id with its other tags beneath, each step with its "
                           "keyword in bold, an outline's Examples a table, Markdown's characters escaped, no "
                           "heading of its own"):
        (tmp_path / "f.feature").write_text(
            "Feature: Alarms\n  A nurse is alerted.\n\n  Rule: Within 2 s\n\n"
            "    # a comment, left out\n    @smoke @REQ-12\n    Scenario: Alarm raised\n"
            "      Given the limit is $140\n      When a reading of <value> arrives\n      Then an alarm sounds\n\n"
            "    @REQ-13\n    Scenario Outline: Quiet\n      Given <x>\n\n      Examples: values\n"
            "        | x |\n        | a_b |\n")
        out = render_from_string("{{ 'f.feature' | gherkin }}")
        attach("rendered", out)
        assert "**Feature: Alarms**" in out and "A nurse is alerted." in out
        assert "**Rule: Within 2 s**" in out and "`REQ-12` **Alarm raised**" in out and "*@smoke*" in out
        assert "**Given** the limit is \\$140" in out and "**When** a reading of \\<value\\> arrives" in out
        assert "`REQ-13` **Quiet**" in out and "*Examples: values*" in out and "| a\\_b |" in out
        assert "a comment" not in out and "```" not in out
        assert not any(line.startswith("#") for line in out.splitlines())
    with verification_step("join_to refuses an id its table lacks, naming it: rdm render exits 2"):
        from rdm.main import cli

        monkeypatch.chdir(tmp_path)
        (tmp_path / "doc.md").write_text("{% for r in ['r9'] | join_to(table.rows) %}{{ r.v }}{% endfor %}\n")
        (tmp_path / "empty.yml").write_text("")
        (tmp_path / "table.yml").write_text("rows: [{id: r1, v: 1}]\n")
        capsys.readouterr()
        assert cli(["render", "doc.md", "empty.yml", "table.yml"]) == 2
        err = capsys.readouterr().err
        attach("refusal", err)
        assert "r9" in err, err


@allure.tag("item-07-003")
@allure.label("output", "rdm/md_extensions/")
def test_markdown_post_processing() -> None:
    """item-07-003: section numbering, a second pass that gives a template the words
    its first render produced, and auditor-note exclusion."""
    with verification_step("sections are numbered"):
        numbered = render_from_string(
            "## hello", config={"md_extensions": ["rdm.md_extensions.SectionNumberExtension"]}
        )
        assert numbered == "## 1.1 hello\n"
    with verification_step("auditor-only notes are removed"):
        excluded = render_from_string(
            "Spec [[1234:9.8.7.6]].",
            config={"md_extensions": ["rdm.md_extensions.AuditNoteExclusionExtension"]},
        )
        assert excluded == "Spec.\n"
    with verification_step("a second render gives the template the words its first render produced"):
        vocab = render_from_string(
            "apple\nbanana\n{% for v in first_pass_output.words | sort %}[{{ v }}]{% endfor %}",
            config={"md_extensions": ["rdm.md_extensions.VocabularyExtension"]},
        )
        assert "[apple][banana]" in vocab

    both = {"md_extensions": ["rdm.md_extensions.SectionNumberExtension",
                              "rdm.md_extensions.AuditNoteExclusionExtension"]}
    with verification_step("code is left as written: no note removed, no comment numbered"):
        code = "```bash\nif [[ -f x ]]; then\n# a comment\n```\n~~~\n# also code\n~~~\nUse `[[1, 2]]` here.\n"
        assert render_from_string(code, config=both) == code
    with verification_step("only an ATX heading is numbered"):
        assert render_from_string("#hashtag\n#\n# Top\n## Sub", config=both) == "#hashtag\n#\n# 1 Top\n## 1.1 Sub\n"
    with verification_step("a note over two lines of a paragraph, or nested, is removed whole"):
        notes = "A [[auditor\nonly]] B.\nC [[outer [[inner]] tail]] D.\n"
        assert render_from_string(notes, config=both) == "A B.\nC D.\n"
    with verification_step("a code fence inside a blockquote or a list item is code too"):
        nested = "> ```python\n> x = data[[0]]\n> ```\n\n- step\n\n    ```bash\n    echo [[ok]]\n    ```\n"
        assert render_from_string(nested, config=both) == nested
    with verification_step("a note still open at the paragraph's end is kept as text"):
        assert render_from_string("Stray [[ here\n\nNext.", config=both) == "Stray [[ here\n\nNext.\n"
