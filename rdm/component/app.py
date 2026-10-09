"""The entry of ``rdm.wasm``: RDM's command line as a WASI component (DI-87).

The component cannot start programs: git and the drawing tool are the two
providers the build composed in, reached through the ports. The host needs
Wasmtime only. ``RDM_REPO`` names where the host mounted the repository (the
launcher mounts it under its own name, so RDM names the project as it does
natively); the component starts there.
"""

from __future__ import annotations

import os
import sys
from importlib.resources import files

from wit_world import exports
from wit_world.imports import exit as wasi_exit

from rdm.component import bundle

# The standard library RDM's commands load (stdlib.txt, recorded natively by
# scripts/component-stdlib.sh), not all of it: the build snapshots the
# interpreter's memory, so every module imported here is size in the component.
bundle.everything("jinja2", "markupsafe", "yaml", "rdflib", "pyshacl", "owlrl", "pyparsing", "prettytable",
                  "packaging", "html5rdf", "markdown_it", "mdit_py_plugins", "mdurl", "rdm",
                  stdlib=str(files("rdm.component").joinpath("stdlib.txt")))


def _main(argv: list[str]) -> int:
    if argv[:1] == ["--skipped"]:  # what the build could not bundle (for the build's own check)
        print("\n".join(bundle.SKIPPED) or "none")
        return 0
    from rdm.architecture import draw
    from rdm.component.c4 import WitC4
    from rdm.component.record_state import WitRecordState
    from rdm.component.typeset import WitTypeset
    from rdm.kernel import record_state
    from rdm.main import cli
    from rdm.publishing import report

    os.chdir(os.environ.get("RDM_REPO", "/"))
    record_state.use(WitRecordState)
    draw.use(lambda: (WitC4(), None))
    report.use(WitTypeset)
    try:
        return cli(argv)
    except SystemExit as stop:  # argparse exits on a usage error
        return stop.code if isinstance(stop.code, int) else 1


class Run(exports.Run):
    async def run(self) -> None:
        code = _main(sys.argv[1:])
        sys.stdout.flush()
        sys.stderr.flush()
        if code:
            wasi_exit.exit_with_code(code)  # wasi:cli/exit, so a usage error stays 2
