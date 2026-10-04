"""
Spike: import, at build time, everything a component may import later.

componentize-py bundles only the modules imported while it runs the app's
module. RDM imports its subcommands lazily, and Python, pytest and Jinja2
load modules on first use (codecs, ``tomllib``, ``importlib.resources``'
adapters, ``jinja2.debug``). So each app imports this first: the whole
standard library WASI's CPython has, and every submodule of the packages it
names. ``SKIPPED`` lists what could not be imported (the graph extra, say).
"""

import importlib
import pkgutil
import sys

# GUI, test and interactive parts of the standard library: never needed, some unimportable.
_NOT_FOR_WASI = {"antigravity", "idlelib", "test", "this", "tkinter", "turtle", "turtledemo", "lib2to3",
                 "ensurepip", "venv"}
_SKIP_PARTS = {"tests", "test", "idle_test", "__main__"}

SKIPPED: list[str] = []


def _walk(module, report: bool) -> None:
    for sub in pkgutil.walk_packages(getattr(module, "__path__", []), module.__name__ + ".", onerror=lambda _: None):
        if _SKIP_PARTS.isdisjoint(sub.name.split(".")[1:]):
            try:
                importlib.import_module(sub.name)
            except Exception as error:  # noqa: BLE001 - e.g. encodings.mbcs exists only on Windows
                if report:
                    SKIPPED.append(f"{sub.name}: {type(error).__name__}: {error}")


def everything(*packages: str) -> None:
    for name in sorted(set(sys.stdlib_module_names) - _NOT_FOR_WASI):
        try:
            _walk(importlib.import_module(name), report=False)
        except Exception:  # noqa: BLE001 - not every module exists on WASI
            pass
    for name in packages:
        _walk(importlib.import_module(name), report=True)
