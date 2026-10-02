"""Single source of truth for the version: the installed package metadata
(driven by pyproject.toml). Falls back gracefully when running from an
uninstalled source tree."""

import re
from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("rdm")
except PackageNotFoundError:  # not installed (e.g. raw source checkout)
    __version__ = "0.0.0+unknown"

_PRE = {"a": "alpha", "b": "beta", "rc": "rc"}


def release_version(pep440: str = __version__) -> str:
    """The version as its release is tagged (semantic versioning): ``2.0.0a0`` is ``2.0.0-alpha``,
    ``2.0.0b2`` is ``2.0.0-beta.2``; a final or unrecognised version is unchanged."""
    match = re.fullmatch(r"(\d+\.\d+\.\d+)(a|b|rc)(\d+)", pep440)
    if not match:
        return pep440
    base, pre, n = match.groups()
    return f"{base}-{_PRE[pre]}" + (f".{n}" if n != "0" else "")
