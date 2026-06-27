"""Single source of truth for the version: the installed package metadata
(driven by pyproject.toml). Falls back gracefully when running from an
uninstalled source tree."""

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("rdm")
except PackageNotFoundError:  # not installed (e.g. raw source checkout)
    __version__ = "0.0.0+unknown"
