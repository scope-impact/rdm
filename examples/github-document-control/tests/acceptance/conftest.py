"""RDM's pytest plugin labels each run that claims a requirement of this
example's spec with the commit it ran at, and records the run's executor and
environment. ``pytest.ini`` makes this example pytest's
root, so the plugin reads ``dhf/`` here wherever pytest is started from."""

from rdm.pytest_plugin import pytest_runtest_call  # noqa: F401
