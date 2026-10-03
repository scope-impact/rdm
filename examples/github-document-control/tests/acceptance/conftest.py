"""RDM's pytest plugin labels each run from this example's record: the text of
its design input, the user need and context, the commit it ran at, and the
run's executor and environment. ``pytest.ini`` makes this example pytest's
root, so the plugin reads ``dhf/`` here wherever pytest is started from."""

from rdm.pytest_plugin import pytest_runtest_call  # noqa: F401
