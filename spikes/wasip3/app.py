"""
Spike: RDM's command line as a WASI 0.3 component (see README.md).

The component cannot start programs. RDM's git questions go through the
``rdm:component/record-state`` import (``git_component.py``, from rdm-git:
gitoxide), and ``rdm c4 draw`` through ``rdm:component/c4`` (``c4_component.py``,
from rdm-c4: structurizrx). ``build.sh`` composes both in, so the host needs
Wasmtime only: no git, no Structurizr, no Java, no Graphviz.
"""

import os
import sys

import bundle
import c4_component
import git_component
from rdm.main import cli
from wit_world import exports
from wit_world.imports import exit as wasi_exit

# The standard library RDM's commands load (stdlib-cli.txt, recorded natively), not all of it: the build
# snapshots the interpreter's memory, so every module imported here is size in the component.
STDLIB = os.path.join(os.path.dirname(__file__), "stdlib-cli.txt")
bundle.everything("jinja2", "markupsafe", "yaml", "rdflib", "pyshacl", "owlrl", "pyparsing", "prettytable",
                  "packaging", "html5rdf", "pyoxigraph", "rdm", stdlib=STDLIB)


def _main(argv: list[str]) -> int:
    if argv[:1] == ["--skipped"]:
        print("\n".join(bundle.SKIPPED) or "none")
        return 0
    os.chdir(os.environ.get("RDM_REPO", "/"))  # the repository, mounted under its own name (rdm-wasm)
    git_component.install()  # git through the rdm:component/record-state import
    c4_component.install()  # rdm c4 draw through the rdm:component/c4 import
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
