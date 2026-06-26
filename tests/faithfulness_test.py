"""Tests for the faithfulness gate: independent confirmation that each design
input's verifying test actually verifies it (hash-pinned, re-opens on edit)."""

from __future__ import annotations

import json
from pathlib import Path

from rdm.record import faithfulness as f
from rdm.record.allure import scan_tagged_sources


def _di(di_id: str, text: str = "a requirement", traces: list[str] | None = None) -> dict:
    return {"id": di_id, "text": text, "traces_to": traces or []}


def _write_verdict(d: Path, di_id: str, verdict: str, test_hash: str = "", **kw) -> None:
    d.mkdir(parents=True, exist_ok=True)
    body = {"design_input": di_id, "verdict": verdict, "test_hash": test_hash}
    body.update(kw)
    (d / f"{di_id}-faithfulness.json").write_text(json.dumps(body))


def _tests_dir(tmp_path: Path, *funcs: str) -> Path:
    """A tests/ dir with one test_*.py containing the given function sources."""
    t = tmp_path / "tests"
    t.mkdir(parents=True, exist_ok=True)
    (t / "test_x.py").write_text("import allure\n\n\n" + "\n\n\n".join(funcs) + "\n")
    return t


class TestScanTaggedSources:
    def test_extracts_function_body_for_tag(self, tmp_path: Path) -> None:
        td = _tests_dir(tmp_path, '@allure.story("DI-1")\ndef test_a():\n    assert 1 == 1')
        sources = scan_tagged_sources(td)
        assert "DI-1" in sources
        assert "assert 1 == 1" in sources["DI-1"][0]

    def test_ignores_untagged_functions(self, tmp_path: Path) -> None:
        td = _tests_dir(tmp_path, "def test_plain():\n    pass")
        assert scan_tagged_sources(td) == {}

    def test_missing_dir_is_empty(self, tmp_path: Path) -> None:
        assert scan_tagged_sources(tmp_path / "nope") == {}


class TestReconcile:
    def test_unreviewed_when_no_verdict(self, tmp_path: Path) -> None:
        report = f.reconcile([_di("DI-1")], tmp_path / "v", None)
        assert report.unreviewed == ["DI-1"]

    def test_faithful_when_verdict_matches_current_hash(self, tmp_path: Path) -> None:
        inputs = [_di("DI-1", "req text")]
        current = f.current_hashes(inputs, None)["DI-1"]
        vdir = tmp_path / "v"
        _write_verdict(vdir, "DI-1", "faithful", test_hash=current)
        report = f.reconcile(inputs, vdir, None)
        assert report.faithful == ["DI-1"]

    def test_stale_when_hash_differs(self, tmp_path: Path) -> None:
        vdir = tmp_path / "v"
        _write_verdict(vdir, "DI-1", "faithful", test_hash="sha256:stale")
        report = f.reconcile([_di("DI-1")], vdir, None)
        assert report.stale == ["DI-1"]

    def test_unfaithful_when_verdict_negative(self, tmp_path: Path) -> None:
        inputs = [_di("DI-1")]
        current = f.current_hashes(inputs, None)["DI-1"]
        vdir = tmp_path / "v"
        _write_verdict(vdir, "DI-1", "unfaithful", test_hash=current, rationale="tautology")
        report = f.reconcile(inputs, vdir, None)
        assert report.unfaithful == ["DI-1"]

    def test_partial_when_verdict_partial(self, tmp_path: Path) -> None:
        inputs = [_di("DI-1")]
        current = f.current_hashes(inputs, None)["DI-1"]
        vdir = tmp_path / "v"
        _write_verdict(vdir, "DI-1", "partial", test_hash=current)
        assert f.reconcile(inputs, vdir, None).partial == ["DI-1"]

    def test_faithful_with_uncovered_clauses_downgrades_to_partial(self, tmp_path: Path) -> None:
        # A "faithful" verdict that still lists an uncovered clause is inconsistent
        # -> the gate downgrades it to partial (overclaim can't slip through).
        inputs = [_di("DI-1")]
        current = f.current_hashes(inputs, None)["DI-1"]
        vdir = tmp_path / "v"
        _write_verdict(vdir, "DI-1", "faithful", test_hash=current,
                       uncovered_clauses=["the second clause is untested"])
        report = f.reconcile(inputs, vdir, None)
        assert report.partial == ["DI-1"] and report.faithful == []

    def test_editing_the_test_restales_a_faithful_verdict(self, tmp_path: Path) -> None:
        inputs = [_di("DI-1", "req")]
        td = _tests_dir(tmp_path, '@allure.story("DI-1")\ndef test_a():\n    assert real_check()')
        current = f.current_hashes(inputs, td)["DI-1"]
        vdir = tmp_path / "v"
        _write_verdict(vdir, "DI-1", "faithful", test_hash=current)
        assert f.reconcile(inputs, vdir, td).faithful == ["DI-1"]
        # Weaken the test -> hash changes -> the verdict goes stale.
        (td / "test_x.py").write_text(
            'import allure\n\n\n@allure.story("DI-1")\ndef test_a():\n    assert True\n'
        )
        assert f.reconcile(inputs, vdir, td).stale == ["DI-1"]

    def test_orphan_verdict_reported(self, tmp_path: Path) -> None:
        vdir = tmp_path / "v"
        _write_verdict(vdir, "DI-9", "faithful")
        report = f.reconcile([_di("DI-1")], vdir, None)
        assert report.orphan_ids == ["DI-9"]
