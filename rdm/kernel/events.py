"""Domain events: the facts a command produced (CONTEXT.md, "Domain event").

A name with a slash is a fail event, the business rule it broke after the
slash (*Release Blocked / Input Untested*); one with *Warned* before the slash
is a warning, which never fails a command; a name with no slash is a success
event or a verdict (*Release Permitted*).
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Event:
    name: str
    message: str

    @property
    def warning(self) -> bool:
        return " Warned / " in self.name

    @property
    def blocking(self) -> bool:
        return " / " in self.name and not self.warning
