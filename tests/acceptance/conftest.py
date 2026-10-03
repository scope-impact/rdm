"""RDM's acceptance suite labels every run from RDM's own record (DI-57):
epic, feature, link to the design document, severity, and the requirement
text. Acceptance tests only: unit tests carry no Allure
(``tests/allure_scope_test.py``).

The plugin's hook is imported rather than listed in ``pytest_plugins``,
which pytest accepts only in a top-level conftest; this keeps it scoped to
the acceptance tests.
"""

from rdm.pytest_plugin import pytest_runtest_call  # noqa: F401
