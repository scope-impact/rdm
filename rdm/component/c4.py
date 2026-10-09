"""The c4 port answered by the ``rdm:component/c4`` import (structurizrx, the
rdm-c4 provider composed into the component)."""

from __future__ import annotations

import json

from componentize_py_types import Err
from wit_world.imports import c4 as wit

from rdm.architecture.draw import DrawError, Drawing


class WitC4:
    name = "rdm-c4"

    def draw(self, dsl: str) -> Drawing:
        try:
            drawn = wit.draw(dsl)
        except Err as error:
            raise DrawError(f"{self.name} could not export the workspace: {error.value}") from None
        return Drawing(model=json.loads(drawn.workspace_json), views={v.key: v.svg for v in drawn.views})
