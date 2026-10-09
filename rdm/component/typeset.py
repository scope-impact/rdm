"""The typeset port answered by the ``rdm:component/typeset`` import (Typst's
crates, the rdm-typst provider composed into the component)."""

from __future__ import annotations

from componentize_py_types import Err
from wit_world.imports import typeset as wit

from rdm.publishing.report import ReportUnavailable


class WitTypeset:
    name = "rdm-typst"

    def typeset(self, main: str, files: dict[str, bytes]) -> bytes:
        try:
            return bytes(wit.typeset(main, [wit.File(path, data) for path, data in files.items()]))
        except Err as error:
            raise ReportUnavailable(f"{self.name} could not compile the report: {error.value}") from None
