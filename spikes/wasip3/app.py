"""
Spike: RDM's command line as a WASI 0.3 component (see README.md).

The component cannot start ``git``, so ``rdm-wasm`` takes a snapshot of the
repository with git on the host and the component answers RDM's git
questions from it (``git_snapshot.py``, ``RDM_GIT_SNAPSHOT``). A question it
cannot answer gets ``None``: the same answer as "git is not installed", which
the gates already treat as "approval could not be verified", never as approved.
"""

import os
import sys

import bundle
import git_snapshot
from rdm.main import cli
from wit_world import exports
from wit_world.imports import exit as wasi_exit

bundle.everything("jinja2", "markupsafe", "yaml", "rdm")


def _main(argv: list[str]) -> int:
    if argv[:1] == ["--skipped"]:
        print("\n".join(bundle.SKIPPED) or "none")
        return 0
    if os.environ.get("RDM_GIT_SNAPSHOT"):
        git_snapshot.replay(os.environ["RDM_GIT_SNAPSHOT"])
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
