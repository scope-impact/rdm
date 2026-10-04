"""
Spike: RDM's acceptance tests run by pytest inside a WASI 0.3 component, with
allure-pytest writing the Allure results the gates read (see ../README.md).

The test files are read from the mounted repository when the component runs;
pytest, allure-pytest and RDM are bundled. Entry points are not bundled, so
the Allure plugin is named with -p.
"""

import os
import sys

import bundle
import git_snapshot
import pytest
from wit_world import exports
from wit_world.imports import exit as wasi_exit

bundle.everything("_pytest", "pluggy", "allure_pytest", "allure_commons", "jinja2", "markupsafe", "yaml", "rdm")


class Run(exports.Run):
    async def run(self) -> None:
        if sys.argv[1:2] == ["--skipped"]:
            print("\n".join(bundle.SKIPPED) or "none")
            return
        if os.environ.get("RDM_GIT_SNAPSHOT"):  # the host's git state, for the run's commit and worktree labels
            git_snapshot.replay(os.environ["RDM_GIT_SNAPSHOT"])
        sys.dont_write_bytecode = True  # the repository is not ours to write .pyc into
        # --capture=sys and no faulthandler: both duplicate file descriptors, which WASI cannot.
        options = ["-p", "allure_pytest.plugin", "-p", "no:cacheprovider", "-p", "no:faulthandler", "--capture=sys"]
        code = int(pytest.main([*options, *sys.argv[1:]]))
        sys.stdout.flush()
        if code:
            wasi_exit.exit_with_code(code)
