"""
Append-only, hash-chained event journal for the DHF (DI-34).

``<dhf>/journal.jsonl`` holds one JSON event per line:

    {"seq": 1, "timestamp": "...", "type": "verdict", "payload": {...},
     "prev_hash": "sha256:000…", "event_hash": "sha256:…"}

``event_hash`` is the SHA-256 of the event's canonical JSON body (every field
except ``event_hash`` itself, keys sorted). Because the body includes
``prev_hash``, each event commits to the entire history before it: editing,
deleting, inserting, or reordering any event breaks the chain at that point,
and ``verify_journal`` names the first broken ``seq``.

Git history stays the primary audit trail; the journal makes the record's own
sequence of events verifiable without trusting that history was never
rewritten. Stdlib only.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

JOURNAL_NAME = "journal.jsonl"
GENESIS_HASH = "sha256:" + "0" * 64


def journal_path(dhf_dir: Path) -> Path:
    return Path(dhf_dir) / JOURNAL_NAME


def _event_hash(event: dict) -> str:
    body = {k: v for k, v in event.items() if k != "event_hash"}
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def read_events(path: Path) -> list[dict]:
    """Every event in the journal, in file order (malformed lines become ``{}``
    so verification reports them rather than skipping them)."""
    if not path.exists():
        return []
    events: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            event = {}
        events.append(event if isinstance(event, dict) else {})
    return events


def append_event(dhf_dir: Path, event_type: str, payload: dict) -> dict:
    """Append one event chained to the current head; returns the event."""
    path = journal_path(dhf_dir)
    events = read_events(path)
    last = events[-1] if events else None
    event = {
        "seq": (int(last.get("seq", 0)) + 1) if last else 1,
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "type": event_type,
        "payload": payload,
        "prev_hash": last.get("event_hash", GENESIS_HASH) if last else GENESIS_HASH,
    }
    event["event_hash"] = _event_hash(event)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, sort_keys=True, ensure_ascii=False) + "\n")
    return event


@dataclass
class JournalCheck:
    """Outcome of verifying a journal: ``broken_seq`` is the first bad event."""

    events: int
    ok: bool
    broken_seq: int | None = None
    reason: str = ""


def file_sha256(path: Path) -> str:
    return "sha256:" + hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_journal(path: Path, verdicts_dir: Path | None = None) -> JournalCheck:
    """Recompute the chain; report the first event that does not follow.

    An absent journal is valid (nothing recorded yet). The reported sequence
    number is the position the broken event *should* hold, so a deleted or
    inserted event is located even though its own ``seq`` is wrong.

    A chain alone cannot see its own tail being cut off, so when
    ``verdicts_dir`` is given the newest ``verdict`` event of each design input
    is also checked against the verdict file on disk: a mismatch means a later
    event was deleted (or the verdict was edited outside ``rdm story verdict``).
    """
    events = read_events(Path(path))
    prev = GENESIS_HASH
    for index, event in enumerate(events):
        expected_seq = index + 1
        if event.get("seq") != expected_seq:
            return JournalCheck(len(events), False, expected_seq,
                                f"expected seq {expected_seq}, found {event.get('seq')!r} "
                                "(an event was deleted, inserted, or reordered)")
        if event.get("prev_hash") != prev:
            return JournalCheck(len(events), False, expected_seq,
                                "prev_hash does not match the preceding event "
                                "(an event was deleted, inserted, or reordered)")
        if event.get("event_hash") != _event_hash(event):
            return JournalCheck(len(events), False, expected_seq,
                                "event_hash does not match the event body (the event was edited)")
        prev = event["event_hash"]
    if verdicts_dir is not None:
        latest: dict[str, dict] = {}
        for event in events:
            payload = event.get("payload") or {}
            if event.get("type") == "verdict" and payload.get("design_input"):
                latest[payload["design_input"]] = event
        for di_id, event in sorted(latest.items(), key=lambda item: item[1]["seq"]):
            verdict_file = Path(verdicts_dir) / f"{di_id}-faithfulness.json"
            on_disk = file_sha256(verdict_file) if verdict_file.exists() else "(missing)"
            if on_disk != event["payload"].get("verdict_sha256"):
                return JournalCheck(len(events), False, event["seq"],
                                    f"{verdict_file.name} does not match its latest journal event "
                                    "(a later event was deleted, or the verdict was edited outside "
                                    "`rdm story verdict`)")
    return JournalCheck(len(events), True)


def journal_command(dhf_dir: Path | None = None, verify: bool = False,
                    faithfulness_dir: Path | None = None) -> int:
    """Run ``rdm story journal [--verify]``: list or verify the event chain."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2
    path = journal_path(dhf)
    if not verify:
        events = read_events(path)
        print(f"Journal: {path} ({len(events)} event(s))")
        for event in events:
            payload = event.get("payload") or {}
            summary = " ".join(f"{k}={payload[k]}" for k in ("design_input", "verdict") if k in payload)
            print(f"  #{event.get('seq')} {event.get('timestamp', '')} {event.get('type', '?')} {summary}".rstrip())
        return 0
    check = verify_journal(path, Path(faithfulness_dir) if faithfulness_dir else dhf / "faithfulness")
    if check.ok:
        print(f"Journal PASSED: {check.events} event(s), hash chain intact ({path})")
        return 0
    print(f"Journal FAILED at seq {check.broken_seq}: {check.reason} ({path})")
    return 1
