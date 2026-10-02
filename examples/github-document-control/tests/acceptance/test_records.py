"""Acceptance tests for the records context (dhf/documents/design/records.md):
the controlled documents themselves. See dc_support.py."""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest
import yaml

allure = pytest.importorskip("allure")

from dc_support import (  # noqa: E402
    CHECKLIST, DHF, DMR_INDEX, EXAMPLE, PROCEDURES, SHAPES, SOP, attach, frontmatter, rdm, render, verification_step,
)

from rdm.gaps import audit_for_gaps  # noqa: E402


def _graph_rules(dhf: Path) -> str:
    """This system's own graph rules over a DHF: the violations they report."""
    pytest.importorskip("pyshacl")
    done = rdm("graph", "validate", "--dhf", str(dhf), "--checklist", str(CHECKLIST), "--shapes", str(SHAPES))
    return "\n".join(line for line in done.stdout.splitlines()
                     if "procedure declares no revision" in line or "SOP cites no checklist clause" in line)


def _copy_dhf(tmp_path: Path) -> Path:
    copy = tmp_path / "dhf"
    shutil.copytree(DHF, copy, ignore=shutil.ignore_patterns("allure-results", "release", "tmp"))
    return copy


@allure.story("DI-2")
@allure.label("output", "dhf/documents/procedures/document_control_procedure.md")
def test_controlled_documents_declare_identity_and_revision(tmp_path: Path) -> None:
    """DI-2: every controlled document carries id + revision frontmatter."""
    docs = sorted(PROCEDURES.glob("*.md"))
    with verification_step("there are controlled documents"):
        assert docs, "no controlled documents found"
    identities = {}
    for doc in docs:
        with verification_step(f"{doc.name} declares its id and revision"):
            assert doc.read_text().startswith("---"), f"{doc.name}: no frontmatter"
            front = frontmatter(doc)
            identities[doc.name] = {"id": front.get("id"), "revision": front.get("revision")}
            assert str(front.get("id", "")).strip(), f"{doc.name}: missing id"
            assert isinstance(front.get("revision"), int), f"{doc.name}: missing revision"
    attach("identity and revision", identities)
    with verification_step("the graph rule finds a procedure without a revision, and none here"):
        assert _graph_rules(DHF) == ""
        copy = _copy_dhf(tmp_path)
        sop = copy / SOP.relative_to(DHF)
        sop.write_text(sop.read_text().replace(f"revision: {frontmatter(SOP)['revision']}\n", "", 1))
        found = _graph_rules(copy)
        attach("graph rules, revision removed", found)
        assert "SOP-DC-001: procedure declares no revision" in found


@allure.story("DI-4")
@allure.label("output", "dhf/documents/procedures/document_control_procedure.md")
def test_rendered_sop_embeds_generated_revision_history() -> None:
    """DI-4: the rendered SOP's history table comes from repository data."""
    rendered = render(SOP)
    history = yaml.safe_load((DHF / "data" / "history.yml").read_text())
    attach("data/history.yml", history)
    with verification_step("every entry of the history data is a row of the rendered SOP"):
        for entry in history["entries"]:
            assert f"| {entry['revision']} | {entry['date']} | {entry['author']} | {entry['change']}" in rendered
    with verification_step("the table is a template loop in the source, not a hand-written table"):
        assert "{%- for entry in history.entries %}" in SOP.read_text()
        assert "{%- for entry in history.entries %}" not in rendered
    with verification_step("the latest history entry is the SOP's declared revision"):
        assert history["entries"][-1]["revision"] == frontmatter(SOP)["revision"]


@allure.story("DI-5")
@allure.label("output", "checklists/part11_document_control.txt")
def test_sop_addresses_every_part11_checklist_item(tmp_path: Path) -> None:
    """DI-5: gap analysis over the SOP with the Part 11 checklist reports full
    coverage — and the check is falsifiable (a stripped SOP fails it)."""
    attach("part11_document_control.txt", CHECKLIST.read_text())
    with verification_step("the gap analysis reports no missing checklist item"):
        assert audit_for_gaps(str(CHECKLIST), [str(SOP)], coverage=False) == 0
    with verification_step("removing one Part 11 reference makes the gap analysis fail"):
        stripped = tmp_path / "sop_missing_audit_trail.md"
        shutil.copy(SOP, stripped)
        stripped.write_text(stripped.read_text().replace("[[P11:11.10e]]", ""))
        assert audit_for_gaps(str(CHECKLIST), [str(stripped)], coverage=False) == 3
    with verification_step("the graph rule finds an SOP that cites no clause, and none here"):
        copy = _copy_dhf(tmp_path)
        sop = copy / SOP.relative_to(DHF)
        sop.write_text("\n".join(line for line in sop.read_text().splitlines() if "[[P11:" not in line))
        found = _graph_rules(copy)
        attach("graph rules, Part 11 citations removed", found)
        assert "SOP-DC-001: SOP cites no checklist clause" in found
        assert "SOP cites no checklist clause" not in _graph_rules(DHF)


@allure.story("DI-7")
@allure.label("output", "dhf/documents/procedures/device_master_record_index.md")
def test_dmr_index_lists_the_specification_set_from_generated_data(tmp_path: Path) -> None:
    """DI-7: the DMR index is a controlled document rendered from index data
    generated from the procedures' frontmatter, listing each with its identity
    and revision."""
    committed = (DHF / "data" / "dmr.yml").read_text()
    dmr = yaml.safe_load(committed)
    attach("data/dmr.yml", dmr)

    with verification_step("the index data is what rdm story dmr generates from the procedures now"):
        generated = tmp_path / "dmr.yml"
        done = rdm("story", "dmr", "-o", str(generated), str(PROCEDURES.relative_to(EXAMPLE)))
        assert done.returncode == 0, done.stderr
        assert generated.read_text() == committed
    rendered = render(DMR_INDEX)
    for entry in dmr["entries"]:
        with verification_step(f"{entry['id']} is listed with its revision, and agrees with its own frontmatter"):
            assert f"| {entry['id']} | {entry['title']} | `{entry['path']}` | {entry['revision']} |" in rendered
            front = frontmatter(DHF / "documents" / entry["path"])
            assert (front["id"], front["revision"]) == (entry["id"], entry["revision"])
    with verification_step("every procedure is indexed"):
        indexed = {entry["id"] for entry in dmr["entries"]}
        assert {frontmatter(doc)["id"] for doc in PROCEDURES.glob("*.md")} == indexed
    with verification_step("the index is rendered from the data, not hand-written"):
        assert "{%- for entry in dmr.entries %}" in DMR_INDEX.read_text()
        assert "{%- for" not in rendered
