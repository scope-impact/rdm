"""The release a build of the docs is for (DI-80, DI-82).

A page writes ``{{ rdm.release }}`` for the release it describes and
``{{ rdm.ref }}`` for the git ref to install it from. A release build (the docs
workflow sets ``RDM_DOCS_VERSION`` to its tag, or HEAD carries a tag) fills in
that tag for both; any other build is the development version, installed from
``main``. Nothing else in a page names a version, so every published copy
agrees with itself.
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEVELOPMENT = "dev"


def _git(root: Path, *args: str) -> str:
    done = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
    return done.stdout.strip() if done.returncode == 0 else ""


def release(env=None, root: Path = ROOT) -> tuple[str, str]:
    """What the docs name as their release, and the ref to install it from."""
    env = os.environ if env is None else env
    tag = env.get("RDM_DOCS_VERSION", "").strip() or _git(root, "describe", "--tags", "--exact-match")
    if tag and tag != DEVELOPMENT:
        return tag, tag
    last = _git(root, "describe", "--tags", "--abbrev=0")
    return (f"development version (after {last})" if last else "development version"), "main"


def fill(markdown: str, env=None, root: Path = ROOT) -> str:
    name, ref = release(env, root)
    return markdown.replace("{{ rdm.release }}", name).replace("{{ rdm.ref }}", ref)


def on_page_markdown(markdown, **kwargs):
    return fill(markdown)
