"""Tests for design-doc discovery (kind: design), design inputs and the user-need registry."""

from __future__ import annotations

from pathlib import Path

from rdm.specification.sdd import design_inputs
from tests.util import write_design_doc


class TestDesignInputs:
    def test_an_id_declared_twice_keeps_its_first_declaration(self, tmp_path: Path) -> None:
        # The record reader keeps an id's first declaration by sorted path
        # (DI-46 makes a duplicate fail the design gate; this is what every
        # other reader sees meanwhile).
        docs = tmp_path / "dhf" / "design"
        write_design_doc(docs, "alpha", design_inputs=(("DI-1", ["UN-001"]),))
        write_design_doc(docs, "beta", design_inputs=(("DI-1", ["UN-002"]), ("DI-2", ["UN-002"])))
        inputs = design_inputs(tmp_path / "dhf")
        assert [(di["id"], di["context"], di["traces_to"]) for di in inputs] == [
            ("DI-1", "alpha", ["UN-001"]), ("DI-2", "beta", ["UN-002"])]
