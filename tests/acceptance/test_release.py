"""Acceptance tests for the release context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import attach, verification_step  # noqa: E402

ROOT = Path(__file__).parents[2]
BUILD = ROOT / "build" / "component"


@allure.story("DI-87")
@allure.label("component", "Component root (rdm.wasm)")
def test_the_component_gives_the_command_lines_results() -> None:
    """DI-87: rdm.wasm, one WASI 0.3 component composed from the core and its two
    providers, imports no network interface of its own and gives, for the listed
    commands, the command line's exit code, output and files on the same record."""
    if not (BUILD / "rdm.wasm").is_file():
        pytest.skip("rdm.wasm is not built: scripts/build-component.sh")

    with verification_step("the composed component imports WASI only, and no network interface"):
        wit = subprocess.run([BUILD / "wasm-tools", "component", "wit", BUILD / "rdm.wasm"],
                             capture_output=True, text=True, check=True).stdout
        imports = sorted({line.strip() for line in wit.splitlines() if line.strip().startswith("import ")})
        attach("imports", "\n".join(imports))
        assert imports and all(i.startswith("import wasi:") for i in imports), imports
        # No rdm:component import is left unplugged, and RDM asks for no network: the only socket
        # imports are the bundled CPython's (wasi-libc, WASI 0.2), which the launcher denies.
        assert not any(name in i for i in imports for name in ("wasi:http", "rdm:component"))
        assert all("@0.2." in i for i in imports if "wasi:sockets" in i), imports
        assert not any("wasi:sockets" in i and "@0.3." in i for i in imports)
        exports = [line.strip() for line in wit.splitlines() if line.strip().startswith("export ")]
        assert "export wasi:cli/run@0.3.0;" in exports and not any("rdm:component" in e for e in exports), exports

    with verification_step("each listed command, run natively and in the component on RDM's own record, "
                           "gives the same exit code, output and files"):
        done = subprocess.run([ROOT / "scripts" / "compare-component.sh"], cwd=ROOT, capture_output=True, text=True)
        attach("comparison", done.stdout + done.stderr)
        assert done.returncode == 0, done.stdout + done.stderr
        assert "NO" not in done.stdout and done.stdout.count(" yes") >= 20
