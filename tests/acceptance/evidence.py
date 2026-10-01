"""Evidence for acceptance tests: one Allure step per clause, and what was checked attached.

    with clause("unacceptable residual blocks"):
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert ...

A step names the clause a failure belongs to; an attachment keeps what the
assertion looked at, in the Allure results the release bundle retains (DI-30)
and the graph projects (DI-53).
"""

from __future__ import annotations

import json

import allure

clause = allure.step


def attach(name: str, content) -> None:
    """Attach text as text, anything else as JSON."""
    if isinstance(content, str):
        allure.attach(content, name=name, attachment_type=allure.attachment_type.TEXT)
    else:
        allure.attach(json.dumps(content, indent=2, sort_keys=True, default=str), name=name,
                      attachment_type=allure.attachment_type.JSON)
