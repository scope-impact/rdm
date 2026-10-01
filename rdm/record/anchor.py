"""
Content anchors for design inputs and the design-input lock (DI-37).

A ``DI-n`` id names *which* requirement, not *what it said*. ``fingerprint``
gives each design input a short content hash of its whitespace-normalized text,
so a rendered matrix pins the exact wording it verified. ``write_lock`` records
every declared id's fingerprint in ``<dhf>/design_inputs.lock.json`` and keeps
ids that are no longer declared as ``retired`` -- forever, so an id is never
silently reused for a different requirement. ``lock_findings`` compares the
declared inputs with the lock for the design gate.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from rdm.record.fingerprint import text_fingerprint

LOCK_NAME = "design_inputs.lock.json"
_SHORT = len("sha256:") + 12


def fingerprint(text: str) -> str:
    """Short content anchor of a requirement's wording (whitespace-blind)."""
    return text_fingerprint(text)[:_SHORT]


def lock_path(dhf_dir: Path) -> Path:
    return Path(dhf_dir) / LOCK_NAME


def read_lock(dhf_dir: Path) -> dict | None:
    """The lock as ``{"inputs": {id: fp}, "retired": {id: fp}}``, or ``None``
    when no lock exists (the check is opt-in by presence)."""
    path = lock_path(dhf_dir)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        data = {}
    if not isinstance(data, dict):
        data = {}
    inputs = data.get("inputs") if isinstance(data.get("inputs"), dict) else {}
    retired = data.get("retired") if isinstance(data.get("retired"), dict) else {}
    return {"inputs": dict(inputs), "retired": dict(retired)}


def write_lock(dhf_dir: Path, design_inputs: list[dict]) -> dict:
    """Lock the current wording of every declared input; retire the rest."""
    previous = read_lock(dhf_dir) or {"inputs": {}, "retired": {}}
    inputs = {di["id"]: fingerprint(di.get("text", "")) for di in design_inputs}
    retired = dict(previous["retired"])
    for di_id, fp in previous["inputs"].items():
        if di_id not in inputs:
            retired.setdefault(di_id, fp)
    lock = {
        "inputs": dict(sorted(inputs.items(), key=lambda kv: _id_key(kv[0]))),
        "retired": dict(sorted(retired.items(), key=lambda kv: _id_key(kv[0]))),
    }
    lock_path(dhf_dir).write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    return lock


def _id_key(di_id: str) -> tuple:
    head, _, tail = di_id.rpartition("-")
    return (head, int(tail)) if tail.isdigit() else (di_id, 0)


@dataclass
class LockFindings:
    reworded: list[str] = field(default_factory=list)   # fingerprint differs from the lock
    reused: list[str] = field(default_factory=list)     # a retired id declared again
    unlocked: list[str] = field(default_factory=list)   # declared, not yet in the lock

    @property
    def failures(self) -> list[str]:
        return (
            [f"design input {i} was reworded since it was locked (fingerprint differs from "
             f"{LOCK_NAME}); re-run `rdm story lock` and commit to acknowledge it" for i in self.reworded]
            + [f"design input {i} reuses a retired id (an id must never name a different "
               "requirement); allocate a new id" for i in self.reused]
        )

    @property
    def warnings(self) -> list[str]:
        return [f"design input {i} is not yet in {LOCK_NAME} (run `rdm story lock`)" for i in self.unlocked]


def lock_findings(dhf_dir: Path, design_inputs: list[dict]) -> LockFindings | None:
    """Compare declared inputs with the lock; ``None`` when there is no lock."""
    lock = read_lock(dhf_dir)
    if lock is None:
        return None
    found = LockFindings()
    for di in design_inputs:
        di_id = di["id"]
        if di_id in lock["retired"]:
            found.reused.append(di_id)
        elif di_id not in lock["inputs"]:
            found.unlocked.append(di_id)
        elif lock["inputs"][di_id] != fingerprint(di.get("text", "")):
            found.reworded.append(di_id)
    return found


def lock_command(dhf_dir: Path | None = None) -> int:
    """Run ``rdm story lock``: write the design-input lock from the record."""
    from rdm.record.sdd import design_inputs

    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    lock = write_lock(dhf, design_inputs(dhf))
    print(f"wrote {lock_path(dhf)} ({len(lock['inputs'])} locked, {len(lock['retired'])} retired)")
    return 0
