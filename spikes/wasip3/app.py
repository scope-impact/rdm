"""
Spike: RDM's command line as a WASI 0.3 component (see README.md).

The component cannot start ``git``, so the host answers RDM's git questions
ahead of time (``git_facts.py record``) and the component replays them
(``RDM_GIT_FACTS``). A question the host did not answer gets ``None``: the
same answer as "git is not installed", which the gates already treat as
"approval could not be verified", never as approved.
"""

import encodings.ascii  # noqa: F401 - codecs load lazily; the build bundles only what is imported
import encodings.latin_1  # noqa: F401
import encodings.utf_8_sig  # noqa: F401
import importlib
import os
import pkgutil
import sys

import rdm
from rdm.main import cli
from wit_world import exports
from wit_world.imports import exit as wasi_exit

import git_facts

# RDM imports its subcommands lazily, and the build bundles only the modules
# imported while it runs this file: import them all now. What cannot be
# imported here (the graph extra, the pytest plugin) is listed by --skipped.
SKIPPED = []
for module in pkgutil.walk_packages(rdm.__path__, "rdm."):
    try:
        importlib.import_module(module.name)
    except Exception as error:  # noqa: BLE001 - report every module that cannot be bundled
        SKIPPED.append(f"{module.name}: {type(error).__name__}: {error}")


def _main(argv: list[str]) -> int:
    if argv[:1] == ["--skipped"]:
        print("\n".join(SKIPPED) or "none")
        return 0
    if os.environ.get("RDM_GIT_FACTS"):
        git_facts.replay(os.environ["RDM_GIT_FACTS"])
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
