"""
Spike: RDM's acceptance tests run by pytest inside a WASI 0.3 component, with
allure-pytest writing the Allure results the gates read (see ../README.md).

The test files are read from the mounted repository when the component runs;
pytest, allure-pytest and RDM are bundled. Entry points are not bundled, so
the Allure plugin is named with -p.
"""

import encodings.ascii  # noqa: F401 - codecs load lazily; the build bundles only what is imported
import encodings.latin_1  # noqa: F401
import encodings.utf_8_sig  # noqa: F401
import importlib
import pkgutil
import sys

import os

import _pytest
import allure_commons
import jinja2
import markupsafe
import yaml
import allure_pytest
import pluggy
import pytest
import git_facts
import rdm
from wit_world import exports
from wit_world.imports import exit as wasi_exit

SKIPPED = []
# The standard library loads lazily too (pytest reads tomllib only for a pyproject.toml): import all of it
# that WASI's CPython has. GUI, test and interactive modules are left out.
_NOT_FOR_WASI = {"antigravity", "idlelib", "test", "this", "tkinter", "turtle", "turtledemo", "lib2to3",
                 "ensurepip", "venv"}
for name in sorted(set(sys.stdlib_module_names) - _NOT_FOR_WASI):
    try:
        module = importlib.import_module(name)
    except Exception:  # noqa: BLE001 - not every module exists on WASI
        continue
    for sub in pkgutil.walk_packages(getattr(module, "__path__", []), name + ".", onerror=lambda _: None):
        if not sub.name.split(".")[1:2] or sub.name.split(".")[1] not in ("tests", "test", "idle_test", "__main__"):
            try:
                importlib.import_module(sub.name)
            except Exception:  # noqa: BLE001 - e.g. encodings.mbcs exists only on Windows
                pass
for package in (_pytest, pluggy, allure_pytest, allure_commons, jinja2, markupsafe, yaml, rdm):
    for module in pkgutil.walk_packages(package.__path__, package.__name__ + "."):
        try:
            importlib.import_module(module.name)
        except Exception as error:  # noqa: BLE001 - report every module that cannot be bundled
            SKIPPED.append(f"{module.name}: {type(error).__name__}: {error}")


class Run(exports.Run):
    async def run(self) -> None:
        if sys.argv[1:2] == ["--skipped"]:
            print("\n".join(SKIPPED) or "none")
            return
        if os.environ.get("RDM_GIT_FACTS"):  # the host's answers for the run's commit and worktree labels
            git_facts.replay(os.environ["RDM_GIT_FACTS"])
        sys.dont_write_bytecode = True  # the repository is not ours to write .pyc into
        # --capture=sys and no faulthandler: both duplicate file descriptors, which WASI cannot.
        options = ["-p", "allure_pytest.plugin", "-p", "no:cacheprovider", "-p", "no:faulthandler", "--capture=sys"]
        code = int(pytest.main([*options, *sys.argv[1:]]))
        sys.stdout.flush()
        if code:
            wasi_exit.exit_with_code(code)
