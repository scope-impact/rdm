"""Acceptance tests for the rendering context's design inputs (see dhf/).

Each test ("live BDD") verifies a rendering design input,
tagged with `@allure.story` and its DI id. They exercise the real render engine / filters /
markdown extensions, so a passing tag is evidence the requirement is met.

    uv run pytest tests/acceptance --alluredir=dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from rdm.publishing.render import invert_dependencies, join_to, md_indent
from tests.util import render_from_string

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]


@allure.story("DI-7")
@allure.label("output", "rdm/publishing/render.py")
def test_renders_template_against_data_context() -> None:
    """DI-7: a Markdown template renders against a supplied data context."""
    out = render_from_string(
        "Device: {{ device.name }} v{{ device.version }}",
        context={"device": {"name": "Acme Monitor", "version": "1.2"}},
    )
    assert "Device: Acme Monitor v1.2" in out


@allure.story("DI-8")
@allure.label("output", "rdm/publishing/render.py")
def test_traceability_filters() -> None:
    """DI-8: the invert_dependencies / join_to / md_indent filters behave."""
    with verification_step("invert_dependencies: edge A→B inverts to B being depended-on by {A}"):
        assert invert_dependencies([{"id": "A", "deps": ["B"]}], "id", "deps") == [("B", {"A"})]
    with verification_step("join_to: resolve foreign keys against a table by primary key"):
        table = [{"id": "r1", "v": 1}, {"id": "r2", "v": 2}]
        assert join_to(["r2"], table) == [{"id": "r2", "v": 2}]
    with verification_step("md_indent: shift headings deeper by header_shift"):
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
    with verification_step("a note still open at the paragraph's end is kept as text"):
        assert render_from_string("Stray [[ here\n\nNext.", config=both) == "Stray [[ here\n\nNext.\n"


FAKE_VIEWS = ("C1", "C3_core")
FAKE_STRUCTURIZR = """
import json, sys
from pathlib import Path
args = sys.argv[1:]
workspace, fmt, out = (args[args.index(flag) + 1] for flag in ("-w", "-f", "-o"))
if "BROKEN" in Path(workspace).read_text():
    sys.exit("Error: unexpected token at line 3")
views = %r
Path(out).mkdir(parents=True)
if fmt == "json":
    Path(out, "workspace.json").write_text(json.dumps({
        "name": "Device", "properties": {"structurizr.dsl": "d29ya3NwYWNl"},
        "model": {"people": [{"id": "1", "name": "Clinician"}]},
        "views": {"systemContextViews": [{"key": views[0]}], "componentViews": [{"key": views[1]}]}}))
else:
    for key in views:
        Path(out, "structurizr-" + key + ".dot").write_text('digraph { 1 [label=<<font>Design, V&V and risk</font>>] }')
""" % (FAKE_VIEWS,)

FAKE_DOT = """
import re, sys
source = sys.stdin.read()
if re.search(r"&(?!(?:[a-zA-Z]+|#[0-9]+);)", source):
    sys.exit("Error: not well-formed (invalid token)")
label = source.split("<font>")[1].split("</font>")[0]
print('<?xml version="1.0" encoding="UTF-8"?>')
print("<svg xmlns='http://www.w3.org/2000/svg'><text>" + label + "</text></svg>")
"""


def _tool(tmp_path: Path, name: str, source: str) -> Path:
    tool = tmp_path / name
    tool.write_text(f"#!{sys.executable}" + source)
    tool.chmod(0o755)
    return tool


@allure.story("DI-70")
@allure.label("output", "rdm/architecture/draw.py")
@allure.label("output", "rdm/architecture/model.py")
@allure.label("output", "rdm/specification/design_gate.py")
def test_architecture_views_are_drawn_from_the_workspace_and_kept_current(tmp_path, monkeypatch) -> None:
    """DI-70: each view of the architecture workspace is drawn to an image in
    the record, stamped with the workspace it was drawn from, and the design
    gate fails when the model or a view's image was not drawn from the current
    workspace, or a view has no image."""
    from rdm.architecture.draw import DrawError, draw
    from rdm.architecture.model import stale
    from rdm.architecture.model import workspace_digest as digest
    from rdm.specification.design_gate import check_architecture_views, run_design_gate

    dhf = tmp_path / "dhf"
    (dhf / "c4" / "views").mkdir(parents=True)
    (dhf / "c4" / "workspace.dsl").write_text('workspace "Device" {\n  model {\n  }\n}\n')
    (dhf / "c4" / "views" / "C3_retired.svg").write_text("<svg/>")
    monkeypatch.setenv("RDM_STRUCTURIZR", str(_tool(tmp_path, "structurizr.sh", FAKE_STRUCTURIZR)))
    monkeypatch.setenv("RDM_DOT", str(_tool(tmp_path, "dot", FAKE_DOT)))

    with verification_step("rdm c4 draw writes the model and an image of each view, each stamped with the workspace"):
        assert draw(dhf) == ["C3_core", "C1"]
        model = json.loads((dhf / "c4" / "workspace.json").read_text())
        attach("workspace.json", model)
        assert model["rdm"]["workspace_sha256"] == digest(dhf)
        assert "structurizr.dsl" not in model["properties"]  # not the workspace again, base64
        for key in ("C1", "C3_core"):
            svg = (dhf / "c4" / "views" / f"{key}.svg").read_text()
            assert f"<!-- rdm c4 draw: view {key}, workspace sha256:{digest(dhf)} -->" in svg.splitlines()[1]
    with verification_step("a bare & in Structurizr's DOT is escaped, so Graphviz draws the view"):
        assert "Design, V&amp;V and risk" in (dhf / "c4" / "views" / "C1.svg").read_text()
    with verification_step("the image of a view the workspace no longer has is removed"):
        assert not (dhf / "c4" / "views" / "C3_retired.svg").exists()
    with verification_step("drawn from the current workspace, nothing is stale and the gate's check passes"):
        assert stale(dhf) == [] and check_architecture_views(dhf)[0].ok

    with verification_step("a workspace changed and not redrawn fails the design gate"):
        (dhf / "c4" / "workspace.dsl").write_text('workspace "Device" {\n  model {\n    a = person "A"\n  }\n}\n')
        reasons = stale(dhf)
        attach("stale, workspace changed", reasons)
        assert "c4/workspace.json was not drawn from the current workspace.dsl: run rdm c4 draw" in reasons
        assert "view C1's image was not drawn from the current workspace.dsl: run rdm c4 draw" in reasons
        assert not check_architecture_views(dhf)[0].ok
        gate = {a.name: a for a in run_design_gate(dhf).artifacts}
        assert not gate["Architecture views"].ok and gate["Architecture views"].reasons == reasons
    with verification_step("a view with no image, or an image of no view, fails the design gate"):
        draw(dhf)
        (dhf / "c4" / "views" / "C1.svg").unlink()
        (dhf / "c4" / "views" / "C9.svg").write_text("<svg/>")
        reasons = stale(dhf)
        attach("stale, images", reasons)
        assert "view C1 has no image: run rdm c4 draw" in reasons
        assert "c4/views/C9.svg is the image of no view: run rdm c4 draw" in reasons
    with verification_step("a workspace Structurizr rejects is not drawn, and says why"):
        (dhf / "c4" / "workspace.dsl").write_text("workspace {\n  BROKEN\n}\n")
        with pytest.raises(DrawError, match="could not export json: Error: unexpected token at line 3"):
            draw(dhf)

    with verification_step("RDM's own model and views are drawn from its workspace as it is now"):
        assert stale(ROOT / "dhf") == []
        assert check_architecture_views(ROOT / "dhf")
