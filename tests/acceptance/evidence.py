"""Evidence for acceptance tests: verification steps, and what each checked attached.

    with verification_step("unacceptable residual blocks"):
        gate = run_release_gate(dhf, results)
        attach("release gate blocking", gate.blocking)
        assert ...

A verification step is one named check within a test (an Allure step), with
its own result. It belongs to the test, never to the acceptance criterion: the
design input the test is tagged with is accepted as a whole (CONTEXT.md). The
step's name says what a failure was checking; an attachment keeps what the
assertion looked at, in the Allure results the release bundle retains (DI-30)
and the graph projects (DI-53).
"""

from __future__ import annotations

import json

import allure

verification_step = allure.step


def attach(name: str, content) -> None:
    """Attach text as text, anything else as JSON."""
    if isinstance(content, str):
        allure.attach(content, name=name, attachment_type=allure.attachment_type.TEXT)
    else:
        allure.attach(json.dumps(content, indent=2, sort_keys=True, default=str), name=name,
                      attachment_type=allure.attachment_type.JSON)
